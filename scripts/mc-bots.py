#!/usr/bin/env python3
"""Headless players for load tests (Minecraft 26.2, protocol 776, offline-mode servers).

    mc-bots.py <count> <seconds> [host] [port] [radius]

Each bot logs in, finishes configuration, answers keep-alives and teleports, accepts
chunks, and walks randomly within <radius> blocks of 0,0 on a flat world. Prints how
many bots reached the play state and exits non-zero if none did.
"""
import asyncio
import math
import random
import struct
import sys
import uuid
import zlib

PROTOCOL = 776
FLOOR_Y = -60.0

# Packet ids from the 26.2 data generator (java -DbundlerMainClass=net.minecraft.data.Main --reports).
LOGIN_CB_DISCONNECT, LOGIN_CB_SUCCESS, LOGIN_CB_COMPRESSION, LOGIN_CB_HELLO, LOGIN_CB_QUERY = 0, 2, 3, 1, 4
LOGIN_SB_HELLO, LOGIN_SB_QUERY_ANSWER, LOGIN_SB_ACK = 0, 2, 3
CFG_CB_DISCONNECT, CFG_CB_FINISH, CFG_CB_KEEP_ALIVE, CFG_CB_PING, CFG_CB_KNOWN_PACKS, CFG_CB_CODE_OF_CONDUCT = 2, 3, 4, 5, 14, 19
CFG_SB_CLIENT_INFO, CFG_SB_FINISH, CFG_SB_KEEP_ALIVE, CFG_SB_PONG, CFG_SB_KNOWN_PACKS, CFG_SB_ACCEPT_COC = 0, 3, 4, 5, 7, 9
PLAY_CB_BATCH_FINISHED, PLAY_CB_DISCONNECT, PLAY_CB_KEEP_ALIVE, PLAY_CB_PING, PLAY_CB_POSITION, PLAY_CB_START_CONFIG = 11, 32, 44, 61, 72, 118
PLAY_SB_ACCEPT_TELEPORT, PLAY_SB_BATCH_RECEIVED, PLAY_SB_CONFIG_ACK, PLAY_SB_KEEP_ALIVE, PLAY_SB_MOVE_POS, PLAY_SB_LOADED, PLAY_SB_PONG = 0, 11, 16, 28, 30, 44, 45


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


def read_varint(data, pos):
    result = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            if result & 0x80000000:
                result -= 1 << 32
            return result, pos
        shift += 7


async def read_varint_stream(reader):
    result = shift = 0
    while True:
        byte = (await reader.readexactly(1))[0]
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result
        shift += 7


class Bot:
    def __init__(self, name, host, port, radius, stats):
        self.name, self.host, self.port, self.radius, self.stats = name, host, port, radius, stats
        self.threshold = -1
        self.x = self.z = 0.0
        self.y = FLOOR_Y
        self.heading = random.uniform(0, 2 * math.pi)
        self.playing = False

    async def send(self, packet_id, payload=b""):
        body = varint(packet_id) + payload
        if self.threshold >= 0:
            body = varint(0) + body  # small packets go uncompressed
        self.writer.write(varint(len(body)) + body)

    async def receive(self):
        length = await read_varint_stream(self.reader)
        data = await self.reader.readexactly(length)
        if self.threshold >= 0:
            data_length, pos = read_varint(data, 0)
            data = zlib.decompress(data[pos:]) if data_length else data[pos:]
        packet_id, pos = read_varint(data, 0)
        return packet_id, data[pos:]

    async def run(self, until):
        self.reader, self.writer = await asyncio.open_connection(self.host, self.port)
        await self.send(0x00, varint(PROTOCOL) + string(self.host) + struct.pack(">H", self.port) + varint(2))
        await self.send(LOGIN_SB_HELLO, string(self.name) + uuid.uuid3(uuid.NAMESPACE_OID, "OfflinePlayer:" + self.name).bytes)
        await self.login()
        await self.configuration()
        self.playing = True
        self.stats["playing"] += 1
        walker = asyncio.create_task(self.walk(until))
        try:
            await self.play(until)
        finally:
            walker.cancel()
            self.writer.close()

    async def login(self):
        while True:
            packet_id, payload = await self.receive()
            if packet_id == LOGIN_CB_COMPRESSION:
                self.threshold, _ = read_varint(payload, 0)
            elif packet_id == LOGIN_CB_SUCCESS:
                await self.send(LOGIN_SB_ACK)
                return
            elif packet_id == LOGIN_CB_QUERY:
                message_id, _ = read_varint(payload, 0)
                await self.send(LOGIN_SB_QUERY_ANSWER, varint(message_id) + b"\x00")  # not understood
            elif packet_id == LOGIN_CB_DISCONNECT:
                raise RuntimeError("login refused: " + payload.decode("utf-8", "replace"))
            elif packet_id == LOGIN_CB_HELLO:
                raise RuntimeError("server is in online mode")

    async def configuration(self):
        # locale, view distance 10, chat enabled, colours, all skin parts, right hand,
        # no text filtering, allow listing, particles: all
        await self.send(CFG_SB_CLIENT_INFO, string("en_us") + bytes([10]) + varint(0) + b"\x01" + bytes([0x7F])
                        + varint(1) + b"\x00" + b"\x01" + varint(0))
        while True:
            packet_id, payload = await self.receive()
            if packet_id == CFG_CB_KNOWN_PACKS:
                await self.send(CFG_SB_KNOWN_PACKS, payload)  # we "know" exactly what the server offers
            elif packet_id == CFG_CB_KEEP_ALIVE:
                await self.send(CFG_SB_KEEP_ALIVE, payload)
            elif packet_id == CFG_CB_PING:
                await self.send(CFG_SB_PONG, payload)
            elif packet_id == CFG_CB_CODE_OF_CONDUCT:
                await self.send(CFG_SB_ACCEPT_COC)
            elif packet_id == CFG_CB_FINISH:
                await self.send(CFG_SB_FINISH)
                return
            elif packet_id == CFG_CB_DISCONNECT:
                raise RuntimeError("disconnected during configuration")

    async def play_packets(self, until):
        """Yields the play packets the bot has to answer, reading the socket in bulk.

        A bot near many moving mobs receives thousands of packets per tick. Parsing each
        one in Python falls behind, and the server then buffers the backlog without limit.
        Every packet a bot must answer is tiny, so compressed packets are skipped without
        being inflated and only uncompressed ones have their id read.
        """
        loop = asyncio.get_running_loop()
        buffer = bytearray()
        wanted = {PLAY_CB_KEEP_ALIVE, PLAY_CB_PING, PLAY_CB_POSITION, PLAY_CB_BATCH_FINISHED,
                  PLAY_CB_START_CONFIG, PLAY_CB_DISCONNECT}
        while loop.time() < until:
            try:
                chunk = await asyncio.wait_for(self.reader.read(1 << 20), timeout=max(0.1, until - loop.time()))
            except asyncio.TimeoutError:
                return
            if not chunk:
                raise EOFError("connection closed")
            buffer += chunk
            pos = 0
            while True:
                try:
                    length, start = read_varint(buffer, pos)
                except IndexError:
                    break
                end = start + length
                if end > len(buffer):
                    break
                frame_pos = start
                if self.threshold >= 0:
                    data_length, frame_pos = read_varint(buffer, frame_pos)
                    if data_length:
                        pos = end
                        continue
                packet_id, frame_pos = read_varint(buffer, frame_pos)
                if packet_id in wanted:
                    yield packet_id, bytes(buffer[frame_pos:end])
                pos = end
            del buffer[:pos]

    async def play(self, until):
        loaded = False
        async for packet_id, payload in self.play_packets(until):
            if packet_id == PLAY_CB_KEEP_ALIVE:
                await self.send(PLAY_SB_KEEP_ALIVE, payload)
            elif packet_id == PLAY_CB_PING:
                await self.send(PLAY_SB_PONG, payload)
            elif packet_id == PLAY_CB_POSITION:
                teleport_id, pos = read_varint(payload, 0)
                self.x, self.y, self.z = struct.unpack_from(">ddd", payload, pos)
                await self.send(PLAY_SB_ACCEPT_TELEPORT, varint(teleport_id))
                if not loaded:
                    await self.send(PLAY_SB_LOADED)
                    loaded = True
            elif packet_id == PLAY_CB_BATCH_FINISHED:
                await self.send(PLAY_SB_BATCH_RECEIVED, struct.pack(">f", 25.0))
            elif packet_id == PLAY_CB_START_CONFIG:
                raise RuntimeError("server started reconfiguration; not supported")
            elif packet_id == PLAY_CB_DISCONNECT:
                self.stats["kicked"] += 1
                raise RuntimeError("kicked")

    async def walk(self, until):
        loop = asyncio.get_running_loop()
        while loop.time() < until:
            await asyncio.sleep(0.05)
            self.heading += random.uniform(-0.3, 0.3)
            nx = self.x + math.cos(self.heading) * 0.2
            nz = self.z + math.sin(self.heading) * 0.2
            if abs(nx) > self.radius or abs(nz) > self.radius:
                self.heading += math.pi
                continue
            self.x, self.z = nx, nz
            await self.send(PLAY_SB_MOVE_POS, struct.pack(">ddd", self.x, self.y, self.z) + b"\x01")
            await self.writer.drain()


async def main():
    count = int(sys.argv[1])
    seconds = float(sys.argv[2])
    host = sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1"
    port = int(sys.argv[4]) if len(sys.argv) > 4 else 25565
    radius = float(sys.argv[5]) if len(sys.argv) > 5 else 40.0
    stats = {"playing": 0, "kicked": 0, "errors": 0}
    loop = asyncio.get_running_loop()
    until = loop.time() + seconds

    async def one(i):
        await asyncio.sleep(i * 0.2)  # stagger logins
        try:
            await Bot(f"bot{i:03d}", host, port, radius, stats).run(until)
        except Exception as e:  # noqa: BLE001 - report and keep the others running
            stats["errors"] += 1
            if stats["errors"] <= 3:
                print(f"bot{i:03d}: {type(e).__name__}: {e}", file=sys.stderr)

    await asyncio.gather(*(one(i) for i in range(count)))
    print(f"bots: {stats['playing']}/{count} reached play, {stats['kicked']} kicked, {stats['errors']} errors")
    if stats["playing"] == 0:
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
