#!/usr/bin/env python3
"""Runs console commands on the dev server: python3 scripts/rcon.py "plugins" "hello world"

Prints each command and the server's whole reply. Exits 0 when every command ran, 1 when one could not (no server,
wrong password, the server hung up), with a message saying the fix. Port and password come from
neoforge/run/server.properties when it exists, else 25598 and "hadron"; set HADRON_RCON_PORT or HADRON_RCON_PASSWORD
to talk to another server. Dependency-free.
"""
import os
import socket
import struct
import sys

LOGIN, COMMAND, UNKNOWN = 3, 2, 0
MAX_PACKET = 8192
HERE = os.path.dirname(os.path.abspath(__file__))
PROPERTIES = os.path.join(HERE, "..", "neoforge", "run", "server.properties")


class RconError(Exception):
    """Something the reader can fix; the message says what."""


def settings():
    port, password = 25598, "hadron"
    try:
        with open(PROPERTIES, encoding="utf-8") as f:
            for line in f:
                key, _, value = line.strip().partition("=")
                if key == "rcon.port" and value.isdigit():
                    port = int(value)
                elif key == "rcon.password" and value:
                    password = value
    except OSError:
        pass  # a server that was never started: the defaults are what it will use
    port = int(os.environ.get("HADRON_RCON_PORT", port))
    return port, os.environ.get("HADRON_RCON_PASSWORD", password)


def packet(request_id, kind, body):
    payload = body.encode() + b"\x00\x00"
    return struct.pack("<iii", len(payload) + 8, request_id, kind) + payload


def read_exactly(sock, count):
    """Exactly count bytes, however the network splits them; EOF or a stall is an RconError."""
    data = b""
    while len(data) < count:
        try:
            chunk = sock.recv(count - len(data))
        except socket.timeout:
            raise RconError("The server stopped answering (no reply within 10 s). Check its log, and restart it "
                            "with ./gradlew :neoforge:runServer if it hung.")
        if not chunk:
            raise RconError("The server closed the connection. It may have stopped: check its log, and start it "
                            "again with ./gradlew :neoforge:runServer.")
        data += chunk
    return data


def read_packet(sock):
    (length,) = struct.unpack("<i", read_exactly(sock, 4))
    if length < 10 or length > MAX_PACKET:
        raise RconError(f"The server sent a packet of {length} bytes, which isn't RCON. Is rcon.port in "
                        "neoforge/run/server.properties really the RCON port, not the game's?")
    data = read_exactly(sock, length)
    request_id, kind = struct.unpack("<ii", data[:8])
    return request_id, kind, data[8:-2].decode(errors="replace")


def connect(port, password):
    try:
        sock = socket.create_connection(("127.0.0.1", port), timeout=10)
    except OSError as e:
        raise RconError(f"No dev server answered on rcon port {port} ({e.strerror or e}): start it with "
                        "./gradlew :neoforge:runServer, and wait for it to finish starting.")
    try:
        sock.settimeout(10)
        sock.sendall(packet(1, LOGIN, password))
        while True:
            request_id, kind, _ = read_packet(sock)
            if kind != COMMAND:
                continue  # some servers send an empty response before the login answer
            if request_id == -1:
                raise RconError("The dev server refused the RCON password. Set HADRON_RCON_PASSWORD, or fix "
                                "rcon.password in neoforge/run/server.properties and restart the server.")
            return sock
    except (RconError, OSError):
        sock.close()
        raise


def run(sock, command):
    """The command's whole reply. A long one arrives as several packets with no end marker, so an invalid request
    follows the command: the server answers it only after the whole reply."""
    sock.sendall(packet(2, COMMAND, command))
    sock.sendall(packet(3, UNKNOWN, ""))
    reply = []
    while True:
        request_id, _, body = read_packet(sock)
        if request_id == 3:
            return "".join(reply)
        if request_id == 2:
            reply.append(body)


def main(commands):
    if not commands:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        return 1
    try:
        sock = connect(*settings())
        with sock:
            for command in commands:
                print(">", command, "\n ", run(sock, command))
    except RconError as e:
        print(f"rcon: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
