# Format experiments

Scripts behind the measurements in docs/SHULK-FORMAT.md.

- `compress_experiment.py <region dir> <work dir>`: extracts chunks from .mca files and compares storage strategies (needs the `zstd` CLI). Leaves raw chunks in `<work dir>/raw`.
- `ZBench.java <work dir>/raw`: size and speed of zlib vs zstd levels, with and without a trained dictionary. Run with the zstd-jni jar on the classpath.
