#!/usr/bin/env python3
"""Compare region storage strategies on real chunk data.

    compress_experiment.py <region dir> <work dir>

Extracts every chunk's raw NBT from .mca files, then measures total size for:
  mca        what is on disk now (4 KiB sectors, zlib per chunk)
  zlib       zlib per chunk without sector padding
  zstd-N     zstd level N per chunk
  zstd-dict  zstd level 3 per chunk with a dictionary trained on *other* regions
  zstd-region  whole region as one zstd stream (Linear-style, no random access)
"""
import os
import struct
import subprocess
import sys
import zlib
from pathlib import Path

region_dir, work = Path(sys.argv[1]), Path(sys.argv[2])
raw = work / "raw"
raw.mkdir(parents=True, exist_ok=True)

regions = {}
mca_bytes = 0
for mca in sorted(region_dir.glob("r.*.mca")):
    data = mca.read_bytes()
    mca_bytes += len(data)
    chunks = []
    for i in range(1024):
        offset = int.from_bytes(data[i * 4:i * 4 + 3], "big")
        if offset == 0:
            continue
        pos = offset * 4096
        length, ctype = struct.unpack(">IB", data[pos:pos + 5])
        payload = data[pos + 5:pos + 4 + length]
        if ctype == 2:
            nbt = zlib.decompress(payload)
        elif ctype == 3:
            nbt = payload
        else:
            raise SystemExit(f"unsupported compression {ctype} in {mca}")
        name = raw / f"{mca.stem}.{i}.nbt"
        name.write_bytes(nbt)
        chunks.append(name)
    regions[mca.stem] = chunks

files = [f for chunks in regions.values() for f in chunks]
raw_total = sum(f.stat().st_size for f in files)
print(f"{len(regions)} regions, {len(files)} chunks, raw NBT {raw_total / 2**20:.1f} MiB, "
      f"avg chunk {raw_total / len(files) / 1024:.1f} KiB")


def zstd_size(path, *args):
    return len(subprocess.run(["zstd", "-c", "-q", *args, str(path)], check=True, capture_output=True).stdout)


def timed(label, total):
    print(f"{label:<28} {total / 2**20:8.2f} MiB   ratio vs raw {raw_total / total:5.2f}x   vs mca {mca_bytes / total:5.2f}x")


timed("mca (on disk)", mca_bytes)
timed("zlib per chunk", sum(len(zlib.compress(f.read_bytes(), 6)) for f in files))
for level in (3, 9, 19):
    timed(f"zstd-{level} per chunk", sum(zstd_size(f, f"-{level}") for f in files))

# Dictionary: train on every region except the one being compressed (no leakage).
dict_total = 0
for name, chunks in regions.items():
    others = [str(f) for n, c in regions.items() if n != name for f in c]
    dict_path = work / f"dict-{name}"
    subprocess.run(["zstd", "--train", "-q", "--maxdict=65536", "-o", str(dict_path), *others], check=True)
    dict_total += sum(zstd_size(f, "-3", "-D", str(dict_path)) for f in chunks)
timed("zstd-3 per chunk + dict", dict_total)
print(f"{'':<28} (dictionary itself: {(work / f'dict-{next(iter(regions))}').stat().st_size / 1024:.0f} KiB, stored once)")

region_total = 0
for name, chunks in regions.items():
    blob = work / f"{name}.all"
    blob.write_bytes(b"".join(f.read_bytes() for f in chunks))
    region_total += zstd_size(blob, "-3")
timed("zstd-3 whole region", region_total)
