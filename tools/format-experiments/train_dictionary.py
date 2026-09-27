#!/usr/bin/env python3
"""Train a .shulk zstd dictionary from existing .mca region files.

    train_dictionary.py <output.zdict> <region dir> [<region dir> ...]

Every chunk in every r.*.mca under the given directories becomes one training sample.
Needs the zstd CLI. Dictionaries are trained per storage kind (chunk / entities / poi),
since their NBT has little in common.
"""
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

output, dirs = Path(sys.argv[1]), [Path(d) for d in sys.argv[2:]]
with tempfile.TemporaryDirectory() as tmp:
    count = total = 0
    for directory in dirs:
        for mca in sorted(directory.glob("r.*.mca")):
            data = mca.read_bytes()
            for i in range(1024):
                offset = int.from_bytes(data[i * 4:i * 4 + 3], "big")
                if offset == 0:
                    continue
                pos = offset * 4096
                length, ctype = struct.unpack(">IB", data[pos:pos + 5])
                payload = data[pos + 5:pos + 4 + length]
                nbt = zlib.decompress(payload) if ctype == 2 else payload if ctype == 3 else None
                if nbt is None:
                    continue
                Path(tmp, f"{count}.nbt").write_bytes(nbt)
                count += 1
                total += len(nbt)
    print(f"{count} samples, {total / 2**20:.1f} MiB")
    if count < 100:
        raise SystemExit("too few samples for a useful dictionary")
    # zstd wants at least 10x (better 100x) more samples than dictionary.
    max_dict = min(112640, max(4096, total // 20))
    subprocess.run(["zstd", "--train", "-q", f"--maxdict={max_dict}", "-o", str(output),
                    *[str(p) for p in Path(tmp).glob("*.nbt")]], check=True)
    print(f"wrote {output} ({output.stat().st_size / 1024:.0f} KiB)")
