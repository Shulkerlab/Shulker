#!/usr/bin/env python3
"""Minimal Minecraft protocol client for smoke tests.

    mc-client.py status [host] [port]   print the server list ping JSON
    mc-client.py login  [host] [port]   log in (offline mode) and wait for Login Success

Exits non-zero on any protocol failure. Only what the smoke test needs is implemented.
"""
import json
import socket
import struct
import sys
import uuid
import zlib


def varint(value):
    out = bytearray()
    value &= 0xFFFFFFFF
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def string(value):
    data = value.encode("utf-8")
    return varint(len(data)) + data


def read_varint(stream):
    result = shift = 0
    while True:
        byte = stream.read(1)
        if not byte:
            raise EOFError("connection closed")
        result |= (byte[0] & 0x7F) << shift
        if not byte[0] & 0x80:
            return result
        shift += 7


def read_varint_bytes(data, pos):
    result = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


class Connection:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=15)
        self.stream = self.sock.makefile("rb")
        self.threshold = -1

    def send(self, packet_id, payload=b""):
        body = varint(packet_id) + payload
        if self.threshold >= 0:
            body = varint(0) + body  # never compress outgoing; allowed below or above threshold
        self.sock.sendall(varint(len(body)) + body)

    def receive(self):
        length = read_varint(self.stream)
        data = self.stream.read(length)
        if self.threshold >= 0:
            data_length, pos = read_varint_bytes(data, 0)
            data = zlib.decompress(data[pos:]) if data_length else data[pos:]
        packet_id, pos = read_varint_bytes(data, 0)
        return packet_id, data[pos:]

    def handshake(self, host, port, protocol, intent):
        self.send(0x00, varint(protocol) + string(host) + struct.pack(">H", port) + varint(intent))


def status(host, port):
    conn = Connection(host, port)
    conn.handshake(host, port, -1, 1)
    conn.send(0x00)
    packet_id, payload = conn.receive()
    if packet_id != 0x00:
        raise RuntimeError(f"unexpected status packet {packet_id:#x}")
    length, pos = read_varint_bytes(payload, 0)
    return json.loads(payload[pos:pos + length].decode("utf-8"))


def login(host, port, name="ShulkerSmoke"):
    protocol = status(host, port)["version"]["protocol"]
    conn = Connection(host, port)
    conn.handshake(host, port, protocol, 2)
    player_uuid = uuid.uuid3(uuid.NAMESPACE_OID, "OfflinePlayer:" + name)
    conn.send(0x00, string(name) + player_uuid.bytes)
    while True:
        packet_id, payload = conn.receive()
        if packet_id == 0x03:  # Set Compression
            conn.threshold, _ = read_varint_bytes(payload, 0)
        elif packet_id == 0x02:  # Login Success
            return protocol
        elif packet_id == 0x00:  # Disconnect
            raise RuntimeError("disconnected during login: " + payload.decode("utf-8", "replace"))
        elif packet_id == 0x01:
            raise RuntimeError("server asked for encryption; set online-mode=false for smoke tests")
        # Ignore anything else (login plugin requests, cookies).


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "status"
    host = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 25565
    if mode == "status":
        info = status(host, port)
        print(f"status: {info['version']['name']} (protocol {info['version']['protocol']}), "
              f"{info['players']['online']}/{info['players']['max']} players")
    elif mode == "login":
        protocol = login(host, port)
        print(f"login: success (protocol {protocol})")
    else:
        sys.exit(f"unknown mode {mode}")


if __name__ == "__main__":
    main()
