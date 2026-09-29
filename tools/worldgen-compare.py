#!/usr/bin/env python3
"""Compares the generated terrain of two worlds.

    tools/worldgen-compare.py <world A> <world B> [dimension ...]

Reads the .mca region files of each dimension (default: overworld, the_nether, the_end) and
decodes every chunk that is fully generated in both worlds. Biomes must match exactly; the
terrain shape (air, water, lava or solid for every block, ignoring vegetation) must match to
within MAX_TERRAIN_PPM of the compared blocks. Exact blocks are only reported: even vanilla does
not reproduce them run to run, because where features overlap (ore blobs, trees, mineshafts,
springs) the result depends on the order chunks were decorated in. Two runs of the same build
differ in 1-10 ppm of terrain blocks (more with Leaf's asynchronous structure generation); a
change to terrain, water or structure layouts moved 2000-8000 ppm in testing. Exits non-zero on a mismatch. Used by scripts/worldgen-parity-test.sh.
"""
import gzip
import os
import struct
import sys
import zlib

DIMENSIONS = ["overworld", "the_nether", "the_end"]
MAX_TERRAIN_PPM = float(os.environ.get("MAX_TERRAIN_PPM", "50"))
LEGACY_FOLDERS = {"overworld": "", "the_nether": "DIM-1", "the_end": "DIM1"}  # before 26.x


# ---- NBT ---------------------------------------------------------------------------------

class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, n):
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def unpack(self, fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))[0]

    def string(self):
        return self.take(self.unpack(">H")).decode("utf-8", "replace")

    def payload(self, tag):
        if tag == 1: return self.unpack(">b")
        if tag == 2: return self.unpack(">h")
        if tag == 3: return self.unpack(">i")
        if tag == 4: return self.unpack(">q")
        if tag == 5: return self.unpack(">f")
        if tag == 6: return self.unpack(">d")
        if tag == 7: return self.take(self.unpack(">i"))
        if tag == 8: return self.string()
        if tag == 9:
            inner, length = self.unpack(">b"), self.unpack(">i")
            return [self.payload(inner) for _ in range(length)]
        if tag == 10:
            out = {}
            while True:
                inner = self.unpack(">b")
                if inner == 0:
                    return out
                name = self.string()
                out[name] = self.payload(inner)
        if tag == 11:
            length = self.unpack(">i")
            return list(struct.unpack(f">{length}i", self.take(4 * length)))
        if tag == 12:
            length = self.unpack(">i")
            return list(struct.unpack(f">{length}q", self.take(8 * length)))
        raise ValueError(f"unknown NBT tag {tag}")


def parse_nbt(data):
    reader = Reader(data)
    tag = reader.unpack(">b")
    reader.string()
    return reader.payload(tag)


# ---- regions -----------------------------------------------------------------------------

def read_region(path):
    """Yields (chunk x, chunk z, nbt) for every chunk in an .mca file."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 8192:
        return
    rx, rz = map(int, os.path.basename(path).split(".")[1:3])
    for index in range(1024):
        location = struct.unpack_from(">I", data, index * 4)[0]
        offset, sectors = (location >> 8) * 4096, location & 0xFF
        if offset == 0 or sectors == 0:
            continue
        length, compression = struct.unpack_from(">IB", data, offset)
        payload = data[offset + 5:offset + 4 + length]
        external = compression & 128
        compression &= 127
        if external:
            with open(os.path.join(os.path.dirname(path), f"c.{rx * 32 + index % 32}.{rz * 32 + index // 32}.mcc"), "rb") as ext:
                payload = ext.read()
        if compression == 1:
            raw = gzip.decompress(payload)
        elif compression == 2:
            raw = zlib.decompress(payload)
        elif compression == 3:
            raw = payload
        else:
            raise ValueError(f"{path}: unsupported compression {compression}")
        yield rx * 32 + index % 32, rz * 32 + index // 32, parse_nbt(raw)


def unpack(longs, bits, count):
    """Minecraft's paletted storage: entries never span two longs."""
    if bits == 0:
        return [0] * count
    per_long, mask, out = 64 // bits, (1 << bits) - 1, []
    for value in longs:
        value &= (1 << 64) - 1
        for i in range(per_long):
            out.append((value >> (i * bits)) & mask)
            if len(out) == count:
                return out
    return out + [0] * (count - len(out))


def block_name(entry):
    props = entry.get("Properties")
    if not props:
        return entry["Name"]
    return entry["Name"] + "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]"


AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
# Blocks that features place on top of the terrain; whether they land depends on the order
# chunks are decorated in, so they count as air.
DECORATION = ("grass", "fern", "flower", "dandelion", "poppy", "orchid", "allium", "bluet", "tulip",
              "daisy", "cornflower", "lily", "rose", "peony", "lilac", "bush", "sapling", "vine", "lichen",
              "leaf_litter", "carpet", "leaves", "_log", "_wood", "mushroom", "snow", "sugar_cane",
              "pumpkin", "melon", "berry", "petals", "cactus", "dripleaf", "azalea", "roots", "cocoa",
              "bamboo", "sporeblossom", "spore_blossom", "hanging_moss", "eyeblossom", "firefly",
              "wildflowers", "cactus_flower", "bee_nest", "pointed_dripstone", "amethyst_bud", "amethyst_cluster")
WATER_PLANTS = ("seagrass", "kelp", "sea_pickle", "coral")


def terrain(block):
    """The terrain class of a block: what noise and carvers decide, not which feature won."""
    name = block.split("[", 1)[0]
    if name == "minecraft:water" or "waterlogged=true" in block or any(p in name for p in WATER_PLANTS):
        return "water"
    if name == "minecraft:lava":
        return "lava"
    if name in AIR or (any(d in name for d in DECORATION) and not name.endswith("grass_block")
                       and "mushroom_block" not in name and "mushroom_stem" not in name):
        return "air"
    return "solid"


def decode_section(section):
    blocks = section.get("block_states", {})
    palette = [block_name(p) for p in blocks.get("palette", [{"Name": "minecraft:air"}])]
    bits = 0 if len(palette) == 1 else max(4, (len(palette) - 1).bit_length())
    block_ids = unpack(blocks.get("data", []), bits, 4096)
    biomes = section.get("biomes", {})
    biome_palette = biomes.get("palette", ["minecraft:plains"])
    biome_bits = 0 if len(biome_palette) == 1 else (len(biome_palette) - 1).bit_length()
    biome_ids = unpack(biomes.get("data", []), biome_bits, 64)
    return [palette[i] for i in block_ids], [biome_palette[i] for i in biome_ids]


def load_chunks(world, dimension):
    folder = os.path.join(world, "dimensions", "minecraft", dimension, "region")
    if not os.path.isdir(folder):
        folder = os.path.join(world, LEGACY_FOLDERS[dimension], "region")
    chunks = {}
    if not os.path.isdir(folder):
        return chunks
    for name in os.listdir(folder):
        if name.endswith(".mca"):
            for x, z, nbt in read_region(os.path.join(folder, name)):
                if nbt.get("Status") in ("minecraft:full", "full"):
                    chunks[(x, z)] = {s["Y"]: s for s in nbt.get("sections", [])}
    return chunks


def main():
    world_a, world_b = sys.argv[1], sys.argv[2]
    dimensions = sys.argv[3:] or DIMENSIONS
    failed = False
    for dimension in dimensions:
        a, b = load_chunks(world_a, dimension), load_chunks(world_b, dimension)
        common = sorted(set(a) & set(b))
        block_diffs = terrain_diffs = biome_diffs = chunks_differing = blocks_compared = 0
        examples = []
        for key in common:
            chunk_blocks = chunk_terrain = chunk_biomes = 0
            for y in sorted(set(a[key]) | set(b[key])):
                blocks_compared += 4096
                empty = {"block_states": {"palette": [{"Name": "minecraft:air"}]}}
                blocks_a, biomes_a = decode_section(a[key].get(y, empty))
                blocks_b, biomes_b = decode_section(b[key].get(y, empty))
                for i in range(4096):
                    if blocks_a[i] != blocks_b[i]:
                        chunk_blocks += 1
                        if terrain(blocks_a[i]) == terrain(blocks_b[i]):
                            continue
                        chunk_terrain += 1
                        if len(examples) < 5:
                            x, zz, yy = i & 15, (i >> 4) & 15, i >> 8
                            examples.append(f"{key[0] * 16 + x} {y * 16 + yy} {key[1] * 16 + zz}: {blocks_a[i]} vs {blocks_b[i]}")
                if a[key].get(y) and b[key].get(y):
                    chunk_biomes += sum(1 for i in range(64) if biomes_a[i] != biomes_b[i])
            block_diffs += chunk_blocks
            terrain_diffs += chunk_terrain
            biome_diffs += chunk_biomes
            chunks_differing += 1 if chunk_terrain or chunk_biomes else 0
        ppm = 1e6 * terrain_diffs / max(1, blocks_compared)
        verdict = "ok" if common and biome_diffs == 0 and ppm <= MAX_TERRAIN_PPM else "MISMATCH"
        print(f"{dimension}: {len(common)} chunks, {verdict}: terrain differs in {terrain_diffs} blocks "
              f"({ppm:.1f} ppm, limit {MAX_TERRAIN_PPM:g}), biomes in {biome_diffs} cells; exact blocks differ: {block_diffs}")
        if verdict != "ok":
            for example in examples:
                print(f"    {example}")
        failed |= verdict != "ok"
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
