#!/bin/zsh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DATA_ROOT="$PROJECT_ROOT/Data"
GENERATOR="$PROJECT_ROOT/tools/generate_level.py"

if [[ $# -lt 2 ]]; then
  echo "Cách dùng:"
  echo "  bash tools/import_from_data.sh <Category> <ArtworkFolder> [Display Name] [generator options...]"
  echo
  echo "Ví dụ:"
  echo "  bash tools/import_from_data.sh Cartoons Sonic"
  echo "  bash tools/import_from_data.sh Cartoons Sonic \"Sonic Vietnam\" --line-close-radius 1 --small-region-attach-distance 12"
  exit 1
fi

CATEGORY="$1"
ARTWORK_FOLDER="$2"
shift 2

DISPLAY_NAME="$ARTWORK_FOLDER"
if [[ $# -gt 0 && "$1" != --* ]]; then
  DISPLAY_NAME="$1"
  shift 1
fi

LEVEL_DIR="$DATA_ROOT/$CATEGORY/$ARTWORK_FOLDER"
if [[ ! -d "$LEVEL_DIR" ]]; then
  echo "Không tìm thấy thư mục: $LEVEL_DIR"
  exit 1
fi

LINE_FILE=""
COLOR_FILE=""

for candidate in "$LEVEL_DIR"/*; do
  file_name="$(basename "$candidate")"
  lower_name="$(echo "$file_name" | tr '[:upper:]' '[:lower:]')"
  if [[ -f "$candidate" ]]; then
    case "$lower_name" in
      *line*.png|*line*.jpg|*line*.jpeg|*line*.webp)
        LINE_FILE="$candidate"
        ;;
      *color*.png|*color*.jpg|*color*.jpeg|*color*.webp|*ref*.png|*ref*.jpg|*ref*.jpeg|*ref*.webp|*paint*.png|*paint*.jpg|*paint*.jpeg|*paint*.webp)
        COLOR_FILE="$candidate"
        ;;
    esac
  fi
done

if [[ -z "$LINE_FILE" || -z "$COLOR_FILE" ]]; then
  echo "Thiếu file line/color hợp lệ trong: $LEVEL_DIR"
  echo "Cần có file tên chứa 'line' và file tên chứa 'color' hoặc 'ref' hoặc 'paint'."
  exit 1
fi

cd "$PROJECT_ROOT"
python3 "$GENERATOR" "$@" single "$LINE_FILE" "$COLOR_FILE" --category "$CATEGORY" --name "$DISPLAY_NAME"
