#!/usr/bin/env bash
# Builds WorldBench.jar against the libraries of a server that has been started once.
#
#   tools/world-bench/build.sh <server folder> [output jar]
#
# The server folder must hold libraries/ and versions/ (any Paper-based 26.2 server writes them on
# its first start). Compile against a Shulker server so the Shulk World approach is included; the
# plugin still runs on other servers and skips it there.
set -euo pipefail

server=${1:?usage: build.sh <server folder> [output jar]}
here=$(cd "$(dirname "$0")" && pwd)
out=${2:-$here/WorldBench.jar}
sep=":"
native() { cat; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; native() { while read -r p; do cygpath -w "$p"; done; } ;; esac

classpath=$(find "$server/libraries" "$server/versions" -name '*.jar' | native | tr '\n' "$sep")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

javac --release 25 -nowarn -d "$work" -cp "$classpath" $(find "$here/src" -name '*.java')
cp "$here/src/plugin.yml" "$work/"
jar --create --file "$out" -C "$work" .
echo "Built $out"
