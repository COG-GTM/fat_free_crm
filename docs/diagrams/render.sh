#!/usr/bin/env bash
# Render the architecture diagram. Needs Graphviz (`apt-get install graphviz fonts-dejavu` / `brew install graphviz`).
set -euo pipefail
cd "$(dirname "$0")"
dot -Tpng -Gdpi=110 architecture.dot -o architecture.png
dot -Tsvg architecture.dot -o architecture.svg
