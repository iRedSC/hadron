# Runs console commands on the dev server: python3 scripts/rcon.py "plugins" "hello world"
import socket, struct, sys
def pkt(i,t,body):
    b=body.encode()+b'\x00\x00'
    return struct.pack('<iii',len(b)+8,i,t)+b
s=socket.create_connection(('127.0.0.1',25598),timeout=10)
def recv():
    n=struct.unpack('<i',s.recv(4))[0]; d=b''
    while len(d)<n: d+=s.recv(n-len(d))
    return struct.unpack('<ii',d[:8]), d[8:-2].decode(errors='replace')
s.send(pkt(1,3,'hadron')); recv()
for cmd in sys.argv[1:]:
    s.send(pkt(2,2,cmd)); print('>',cmd,'\n ',recv()[1])
