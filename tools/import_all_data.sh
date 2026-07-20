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

if [[ $# -lt 1 ]]; then
  echo "COMMAND LIST:"
  echo "  1) Import tat ca category/item trong Data:"
  echo "     bash tools/import_all_data.sh"
  echo "  2) Import tat ca category/item trong Data kem tuy chinh generator:"
  echo "     bash tools/import_all_data.sh --line-close-radius 1 --small-region-attach-distance 12"
  echo "  3) Neu chi muon import 1 category:"
  echo "     bash tools/import_category_from_data.sh Data/Animals"
  echo "  4) Neu chi muon import 1 item:"
  echo "     bash tools/import_from_data.sh Animals 01"
  echo
fi

cd "$PROJECT_ROOT"
python3 "$GENERATOR" "$@" batch "$DATA_ROOT"
