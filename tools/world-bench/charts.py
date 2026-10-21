#!/usr/bin/env python3
"""Draws the README world-systems chart from WorldBench results.

    tools/world-bench/charts.py <results.csv>... <output.svg>

Each CSV holds WorldBench rows (server,approach,scenario,metric,value,unit); several runs of
the same row are averaged. The chart compares a Shulk World on Shulker with the two usual ways
of running islands and arenas on Paper: pasting into one shared world, and one world folder
per island.
"""
import collections
import csv
import sys

FONT = "font-family=\"-apple-system, 'Segoe UI', Helvetica, Arial, sans-serif\""
TEXT, MUTED = "#768390", "#8b949e"
ACCENT, OTHER = "#8b5cf6", "#9aa1ab"

# (server, approach) -> label; the first one is Shulker's.
SUBJECTS = [
    (("shulker", "shulk"), "Shulk World (Shulker)"),
    (("paper", "paste"), "Shared world + paste (Paper)"),
    (("paper", "folder"), "World per island (Paper)"),
]
PANELS = [
    ("skyblock_create", "main_cpu_ms_per_op", "New island: main-thread time", "CPU per island from a template, lower is better", "ms", 1),
    ("skyblock_create", "worst_tick_gap_ms", "New island: worst tick", "longest tick while 50 islands are created, lower is better", "ms", 0),
    ("minigame_reset", "wall_ms_per_op", "Arena reset", "until playable again after a game; a Shulk World in place, lower is better", "ms", 0),
    ("skyblock", "disk_kib_per_island", "Disk space per island", "after saving, lower is better", "KiB", 1),
    ("cross_server", "transfer_kib", "Moving an island to another server", "data to copy, lower is better", "KiB", 1),
    ("cross_server_load", "wall_ms_per_op", "Loading a moved island", "time until it is playable, lower is better", "ms", 0),
]


def load(paths):
    values = collections.defaultdict(list)
    for path in paths:
        with open(path, newline="") as f:
            for row in csv.DictReader(f):
                values[(row["server"], row["approach"], row["scenario"], row["metric"])].append(float(row["value"]))
    return {key: sum(v) / len(v) for key, v in values.items()}


def panel(out, x, y, width, title, subtitle, rows, unit, decimals):
    out.append(f'<text x="{x}" y="{y + 16}" {FONT} font-size="15" font-weight="600" fill="{TEXT}">{title}</text>')
    out.append(f'<text x="{x}" y="{y + 34}" {FONT} font-size="12" fill="{MUTED}">{subtitle}</text>')
    label_w = 210
    bar_x = x + label_w
    bar_max = width - label_w - 80
    top = max(v for _, v, _ in rows) or 1
    row_y = y + 50
    for label, value, mine in rows:
        w = max(2.0, bar_max * value / top)
        colour = ACCENT if mine else OTHER
        weight = "700" if mine else "400"
        out.append(f'<text x="{x}" y="{row_y + 13}" {FONT} font-size="13" font-weight="{weight}" fill="{TEXT}">{label}</text>')
        out.append(f'<rect x="{bar_x}" y="{row_y + 1}" width="{w:.1f}" height="16" rx="3" fill="{colour}"/>')
        out.append(f'<text x="{bar_x + w + 6:.1f}" y="{row_y + 13}" {FONT} font-size="12" font-weight="{weight}" fill="{TEXT}">{value:.{decimals}f} {unit}</text>')
        row_y += 24
    return row_y - y


def main():
    data = load(sys.argv[1:-1])
    body, heights = [], []
    for i, (scenario, metric, title, subtitle, unit, decimals) in enumerate(PANELS):
        rows = []
        for n, ((server, approach), label) in enumerate(SUBJECTS):
            key = (server, approach, scenario, metric)
            if key in data:
                rows.append((label, data[key], n == 0))
        if not rows:
            continue
        x = (i % 2) * 500
        y = (i // 2) * 150
        heights.append(y + panel(body, x, y, 460, title, subtitle, rows, unit, decimals))
    height = max(heights) + 10
    with open(sys.argv[-1], "w") as f:
        f.write(f'<svg xmlns="http://www.w3.org/2000/svg" width="960" height="{height}" viewBox="0 0 960 {height}" role="img" '
                f'aria-label="Islands and arenas: Shulk Worlds on Shulker against pasting and world folders on Paper">\n'
                + "\n".join(body) + "\n</svg>\n")
    for key in sorted(data):
        print(",".join(key), f"{data[key]:.2f}")


if __name__ == "__main__":
    main()
