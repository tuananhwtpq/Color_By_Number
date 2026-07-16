#!/bin/zsh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DATA_ROOT="$PROJECT_ROOT/Data"
GENERATOR="$PROJECT_ROOT/tools/generate_level.py"

if [[ ! -d "$DATA_ROOT" ]]; then
  echo "Không tìm thấy thư mục Data: $DATA_ROOT"
  exit 1
fi

cd "$PROJECT_ROOT"
python3 "$GENERATOR" "$@" batch "$DATA_ROOT"
