#!/bin/zsh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DATA_ROOT="$PROJECT_ROOT/Data"
GENERATOR="$PROJECT_ROOT/tools/generate_level.py"
CODEX_PYTHON="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"
if [[ -x "$CODEX_PYTHON" ]]; then
  PYTHON_BIN="$CODEX_PYTHON"
else
  PYTHON_BIN="python3"
fi

if [[ $# -lt 2 ]]; then
  echo "COMMAND LIST:"
  echo "  1) Import 1 item tu Data:"
  echo "     bash tools/import_from_data.sh Cartoons Sonic"
  echo "  2) Import 1 item va dat ten hien thi:"
  echo "     bash tools/import_from_data.sh Cartoons Sonic \"Sonic Vietnam\""
  echo "  3) Copy 1 item da co sang category khac:"
  echo "     bash tools/import_from_data.sh from-existing Cartoons/Sonic Animals"
  echo "  4) Import tat ca item trong 1 category Data vao assets:"
  echo "     bash tools/import_category_from_data.sh Data/Animals"
  echo "  5) Import tat ca item trong Data vao assets, giu nguyen category:"
  echo "     bash tools/import_all_data.sh --output-root app/src/main/assets"
  echo
  echo "Cách dùng:"
  echo "  bash tools/import_from_data.sh <Category> <ArtworkFolder> [Display Name] [generator options...]"
  echo "  bash tools/import_from_data.sh from-existing <SourceCategory/ArtworkFolder> <TargetCategory> [Display Name] [generator options...]"
  echo
  echo "Ví dụ:"
  echo "  bash tools/import_from_data.sh Cartoons Sonic"
  echo "  bash tools/import_from_data.sh Cartoons Sonic \"Sonic Vietnam\" --line-close-radius 1 --small-region-attach-distance 12"
  echo "  bash tools/import_from_data.sh Manga 01 --source-line-format svg --allow-low-quality"
  echo "  bash tools/import_category_from_data.sh Data/Animals"
  echo "  bash tools/import_category_from_data.sh Data/Animals --target-category AnimalsNew"
  echo "  bash tools/import_all_data.sh --output-root app/src/main/assets"
  echo "  bash tools/import_from_data.sh from-existing Cartoons/Sonic Animals"
  echo "  bash tools/import_from_data.sh from-existing Cartoons/Sonic Animals \"Sonic Blue\" --line-close-radius 1"
  echo "command dùng để đánh giá chất lượng Assets hiện tại"
  echo "python3 tools/validate_assets.py app/src/main/assets --data-root Data --write-debug-report"
  echo "Command dùng để chạy tiếp lúc duyệt nếu assets lỗi"
  echo "bash tools/import_category_from_data.sh "Data/Fairy Tail" --overwrite --continue-on-error"
  echo "Command dùng để thêm data nếu như assets vẫn lỗi: "
  echo "bash tools/import_category_from_data.sh "Data/Fairy Tail" --overwrite --allow-low-quality --continue-on-error"
  # sinh bảng review sau khi import all data xong
  echo "python3 tools/export_asset_review_report.py app/src/main/assets --data-root Data --output outputs/color_by_number_asset_review/color_by_number_designer_review.csv"
  # run data preview csv
  echo "python3 tools/export_asset_review_report.py --per-category"
  # audit line degradation CSV: so line gốc trong Data với source/display/segmentation line trong assets
  # để tìm level bị mờ nét hiển thị hoặc xử lý line quá tay làm ảnh hưởng mask.
  echo "python3 tools/audit_line_degradation.py app/src/main/assets --data-root Data --output outputs/line_degradation_audit.csv"
  # audit line degradation HTML: sinh trang xem trực quan Data line, source line, display line,
  # segmentation line, debug_regions và preview cạnh nhau cho từng level.
  echo "python3 tools/audit_line_degradation.py app/src/main/assets --data-root Data --output outputs/line_degradation_audit.csv --html-output outputs/line_degradation_audit.html"
  # export runtime package cho backend import
  echo "python3 tools/export_backend_content.py app/src/main/assets --output-dir outputs/backend_content --zip"
  exit 1

fi

if [[ "$1" == "from-existing" ]]; then
  if [[ $# -lt 3 ]]; then
    echo "Cách dùng:"
    echo "  bash tools/import_from_data.sh from-existing <SourceCategory/ArtworkFolder> <TargetCategory> [Display Name] [generator options...]"
    exit 1
  fi

  SOURCE_PATH="$2"
  TARGET_CATEGORY="$3"
  shift 3

  if [[ "$SOURCE_PATH" != */* ]]; then
    echo "Source path phải có dạng <SourceCategory/ArtworkFolder>, ví dụ: Cartoons/Sonic"
    exit 1
  fi

  CATEGORY="$TARGET_CATEGORY"
  ARTWORK_FOLDER="${SOURCE_PATH##*/}"
  LEVEL_DIR="$DATA_ROOT/$SOURCE_PATH"
else
  CATEGORY="$1"
  ARTWORK_FOLDER="$2"
  shift 2
  LEVEL_DIR="$DATA_ROOT/$CATEGORY/$ARTWORK_FOLDER"
fi

DISPLAY_NAME="$ARTWORK_FOLDER"
if [[ $# -gt 0 && "$1" != --* ]]; then
  DISPLAY_NAME="$1"
  shift 1
fi

if [[ ! -d "$LEVEL_DIR" ]]; then
  echo "Không tìm thấy thư mục: $LEVEL_DIR"
  exit 1
fi

SOURCE_LINE_FORMAT="raster"
for arg in "$@"; do
  if [[ "$arg" == "--source-line-format" ]]; then
    SOURCE_LINE_FORMAT="pending"
  elif [[ "$SOURCE_LINE_FORMAT" == "pending" ]]; then
    SOURCE_LINE_FORMAT="$arg"
  fi
done

LINE_FILE=""
COLOR_FILE=""

for candidate in "$LEVEL_DIR"/*; do
  file_name="$(basename "$candidate")"
  lower_name="$(echo "$file_name" | tr '[:upper:]' '[:lower:]')"
  if [[ -f "$candidate" ]]; then
    case "$lower_name" in
      *line*.svg)
        if [[ "$SOURCE_LINE_FORMAT" == "svg" ]]; then
          LINE_FILE="$candidate"
        fi
        ;;
      *line*.png|*line*.jpg|*line*.jpeg|*line*.webp)
        if [[ "$SOURCE_LINE_FORMAT" != "svg" ]]; then
          LINE_FILE="$candidate"
        fi
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
"$PYTHON_BIN" "$GENERATOR" "$@" single "$LINE_FILE" "$COLOR_FILE" --category "$CATEGORY" --name "$DISPLAY_NAME"
