#!/bin/zsh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GENERATOR="$PROJECT_ROOT/tools/generate_level.py"

if [[ $# -lt 1 ]]; then
  echo "COMMAND LIST:"
  echo "  1) Import 1 category y nguyen ten folder con:"
  echo "     bash tools/import_category_from_data.sh Data/Animals"
  echo "  2) Import 1 category vao category khac trong assets:"
  echo "     bash tools/import_category_from_data.sh Data/Animals --target-category AnimalsNew"
  echo "  3) Import 1 category kem tuy chinh generator:"
  echo "     bash tools/import_category_from_data.sh Data/Animals --line-close-radius 1 --small-region-attach-distance 12"
  echo
  echo "Cách dùng:"
  echo "  bash tools/import_category_from_data.sh <SourceCategoryFolder> [--target-category <TargetCategory>] [generator options...]"
  echo
  echo "Mô tả:"
  echo "  Import toàn bộ item trong một folder category của Data vào assets."
  echo "  Giữ nguyên tên folder con nguồn làm ID/folder đích."
  echo
  echo "Ví dụ copy vào terminal Android Studio:"
  echo "  bash tools/import_category_from_data.sh Data/Animals"
  echo "  bash tools/import_category_from_data.sh Data/Animals --target-category AnimalsNew"
  echo "  bash tools/import_category_from_data.sh Data/Animals --line-close-radius 1 --small-region-attach-distance 12"
  exit 1
fi

cd "$PROJECT_ROOT"
python3 "$GENERATOR" batch-source-category "$@"
