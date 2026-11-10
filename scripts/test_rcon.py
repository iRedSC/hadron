"""Tests for rcon.py against a stand-in server: python3 -m unittest scripts/test_rcon.py (no server, no dependencies)."""
import os
import select
import socketserver
import struct
import sys
import threading
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402


def frame(request_id, kind, body):
    return rcon.packet(request_id, kind, body)


class Server:
    """Answers one connection like Minecraft's RCON server, by a script: behavior is 'normal', 'dribble' (one byte at a time), 'long' (a reply in
    three packets), or 'hangup' (closes after login)."""

    def __init__(self, behavior="normal", password="pw"):
        outer = self

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                sock = self.request
                while True:
                    # As Minecraft's RconClient: one request per socket read, and a read holding anything but exactly
                    # one frame disconnects. A busy server's read finds whatever the client has sent by then, so a
                    # client that sends a second frame before the reply to the first is caught here: the stand-in
                    # takes a short moment to read, and a second frame arriving meanwhile is read together with it.
                    data = sock.recv(1460)
                    if select.select([sock], [], [], 0.15)[0]:
                        return  # more bytes arrived: the same read would have held them
                    if len(data) < 10 or struct.unpack("<i", data[:4])[0] != len(data) - 4:
                        return
                    data = data[4:]
                    request_id, kind = struct.unpack("<ii", data[:8])
                    body = data[8:-2].decode()
                    if kind == rcon.LOGIN:
                        sock.sendall(frame(request_id if body == password else -1, rcon.COMMAND, ""))
                    elif kind == rcon.COMMAND:
                        if behavior == "hangup":
                            return
                        parts = ["a" * 3000, "b" * 3000, "c"] if behavior == "long" else ["echo " + body]
                        out = b"".join(frame(request_id, 0, p) for p in parts)
                        if behavior == "dribble":
                            for i in range(len(out)):
                                sock.sendall(out[i:i + 1])
                        else:
                            sock.sendall(out)
                    else:
                        sock.sendall(frame(request_id, 0, "Unknown request " + format(kind, "x")))

        self.server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class RconTest(unittest.TestCase):
    def talk(self, behavior="normal", password="pw", sent="pw", command="list"):
        server = Server(behavior, password)
        self.addCleanup(server.close)
        with rcon.connect(server.port, sent) as sock:
            return rcon.run(sock, command)

    def test_a_reply_is_the_commands_answer(self):
        self.assertEqual(self.talk(command="plugins"), "echo plugins")

    def test_a_reply_arriving_a_byte_at_a_time_is_read_whole(self):
        self.assertEqual(self.talk("dribble", command="hello"), "echo hello")

    def test_a_reply_in_several_packets_is_joined(self):
        self.assertEqual(self.talk("long"), "a" * 3000 + "b" * 3000 + "c")

    def test_a_command_is_not_followed_by_a_second_frame_until_its_reply_arrives(self):
        # Vanilla hangs up on two frames in one read; the stand-in does too, so sending both at once fails here.
        self.assertEqual(self.talk(command="list"), "echo list")

    def test_a_wrong_password_says_how_to_fix_it(self):
        with self.assertRaisesRegex(rcon.RconError, "refused the RCON password.*HADRON_RCON_PASSWORD"):
            self.talk(sent="nope")

    def test_a_server_that_hangs_up_is_an_error_not_a_hang(self):
        with self.assertRaisesRegex(rcon.RconError, "closed the connection"):
            self.talk("hangup")

    def test_no_server_says_how_to_start_one(self):
        server = Server()
        port = server.port
        server.close()
        with self.assertRaisesRegex(rcon.RconError, f"No dev server answered on rcon port {port}.*runServer"):
            rcon.connect(port, "pw")

    def test_a_server_that_isnt_speaking_rcon_is_refused(self):
        # The game port answers a stranger with something that isn't a length-prefixed packet.
        listener = rcon.socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        self.addCleanup(listener.close)

        def answer():
            conn, _ = listener.accept()
            with conn:
                conn.sendall(struct.pack("<i", 999999))
                conn.recv(1)

        threading.Thread(target=answer, daemon=True).start()
        with self.assertRaisesRegex(rcon.RconError, "isn't RCON"):
            rcon.connect(listener.getsockname()[1], "pw")

    def test_main_exits_nonzero_with_the_fix_when_nothing_listens(self):
        server = Server()
        port = server.port
        server.close()
        os.environ["HADRON_RCON_PORT"] = str(port)
        self.addCleanup(os.environ.pop, "HADRON_RCON_PORT")
        self.assertEqual(rcon.main(["plugins"]), 1)


if __name__ == "__main__":
    unittest.main()
