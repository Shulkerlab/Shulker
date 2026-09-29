#!/usr/bin/env python3
"""Draws the README benchmark charts from the fork comparison results.

    tools/bench-charts.py <results.csv> <output dir>

The CSV holds "run,scenario,server,<benchmark.sh output line>" rows (build/fork-bench.sh writes
them). Several runs of a scenario are averaged. Writes tick-time.svg and world-generation.svg.
"""
import collections
import csv
import os
import re
import sys

SERVERS = ["shulker", "leaf", "divinemc", "purpur", "paper"]
LABELS = {"shulker": "Shulker", "leaf": "Leaf", "divinemc": "DivineMC", "purpur": "Purpur", "paper": "Paper"}
TICK_SCENARIOS = [
    ("villagers", "1500 villagers, cows and sheep"),
    ("pathfinding", "1000 husks pathfinding"),
    ("bots", "40 players + 1500 mobs"),
    ("natural", "16 players in a normal world"),
]
FONT = "font-family=\"-apple-system, 'Segoe UI', Helvetica, Arial, sans-serif\""
TEXT, MUTED, GRID = "#768390", "#8b949e", "#8b949e"
ACCENT, OTHER = "#8b5cf6", "#9aa1ab"


def load(path):
    values = collections.defaultdict(list)
    with open(path, newline="") as f:
        for row in csv.reader(f):
            if len(row) < 4:
                continue
            _, scenario, server, line = row[0], row[1], row[2], ",".join(row[3:])
            if scenario == "worldgen":
                m = re.search(r"\(([0-9.]+) chunks/s\), overworld regions ([0-9.]+) MiB", line)
                if m:
                    values[("worldgen-speed", server)].append(float(m.group(1)))
                    values[("worldgen-size", server)].append(float(m.group(2)))
            else:
                m = re.search(r"mspt\(avg/min/max, 1m\)=([0-9.]+)/", line)
                if m:
                    values[(scenario, server)].append(float(m.group(1)))
    return {key: sum(v) / len(v) for key, v in values.items()}


def panel(out, x, y, width, title, subtitle, rows, unit, decimals):
    """A titled horizontal bar panel; rows are (server, value). Returns the panel height."""
    out.append(f'<text x="{x}" y="{y + 16}" {FONT} font-size="15" font-weight="600" fill="{TEXT}">{title}</text>')
    out.append(f'<text x="{x}" y="{y + 34}" {FONT} font-size="12" fill="{MUTED}">{subtitle}</text>')
    label_w, bar_x = 78, x + 78
    bar_max = width - label_w - 70
    top = max(v for _, v in rows) or 1
    row_y = y + 50
    for server, value in rows:
        w = max(2.0, bar_max * value / top)
        colour = ACCENT if server == "shulker" else OTHER
        weight = "700" if server == "shulker" else "400"
        out.append(f'<text x="{x}" y="{row_y + 13}" {FONT} font-size="13" font-weight="{weight}" fill="{TEXT}">{LABELS[server]}</text>')
        out.append(f'<rect x="{bar_x}" y="{row_y + 1}" width="{w:.1f}" height="16" rx="3" fill="{colour}"/>')
        out.append(f'<text x="{bar_x + w + 6:.1f}" y="{row_y + 13}" {FONT} font-size="12" font-weight="{weight}" fill="{TEXT}">{value:.{decimals}f} {unit}</text>')
        row_y += 24
    return row_y - y


def svg(width, height, body, label):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" '
            f'role="img" aria-label="{label}">\n' + "\n".join(body) + "\n</svg>\n")


def tick_chart(data):
    width, col_w, body = 960, 460, []
    row_heights = []
    for i, (scenario, title) in enumerate(TICK_SCENARIOS):
        rows = [(s, data[(scenario, s)]) for s in SERVERS if (scenario, s) in data]
        if not rows:
            continue
        rows.sort(key=lambda r: r[1])
        x = (i % 2) * (col_w + 40)
        y = (i // 2) * 200
        row_heights.append(y + panel(body, x, y, col_w, title, "average tick time, lower is better", rows, "ms", 1))
    return svg(width, max(row_heights) + 10, body, "Average tick time of Shulker, Leaf, DivineMC, Purpur and Paper")


def worldgen_chart(data):
    body = []
    speed = sorted([(s, data[("worldgen-speed", s)]) for s in SERVERS if ("worldgen-speed", s) in data], key=lambda r: -r[1])
    size = sorted([(s, data[("worldgen-size", s)]) for s in SERVERS if ("worldgen-size", s) in data], key=lambda r: r[1])
    h1 = panel(body, 0, 0, 460, "World generation", "4096 new chunks, higher is better", speed, "chunks/s", 1)
    h2 = panel(body, 500, 0, 460, "Disk space for those chunks", "overworld region files, lower is better", size, "MiB", 1)
    return svg(960, max(h1, h2) + 10, body, "World generation speed and region file size")


def main():
    data = load(sys.argv[1])
    out_dir = sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "tick-time.svg"), "w") as f:
        f.write(tick_chart(data))
    with open(os.path.join(out_dir, "world-generation.svg"), "w") as f:
        f.write(worldgen_chart(data))
    for key in sorted(data):
        print(f"{key[0]:15s} {key[1]:9s} {data[key]:.2f}")


if __name__ == "__main__":
    main()
