#!/bin/zsh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GENERATOR="$PROJECT_ROOT/tools/generate_level.py"
CODEX_PYTHON="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"
if [[ -x "$CODEX_PYTHON" ]]; then
  PYTHON_BIN="$CODEX_PYTHON"
else
  PYTHON_BIN="python3"
fi

if [[ $# -lt 1 ]]; then
  echo "COMMAND LIST:"
  echo "  1) Import 1 category y nguyen ten folder con:"
  echo "     bash tools/import_category_from_data.sh Data/Animals"
  echo "  2) Import 1 category vao category khac trong assets:"
  echo "     bash tools/import_category_from_data.sh Data/Animals --target-category AnimalsNew"
  echo "  3) Regenerate category, bo qua level loi va chay tiep:"
  echo "     bash tools/import_category_from_data.sh Data/Animals --overwrite --continue-on-error"
  echo "  4) Ghi ca level khong dat quality gate de debug:"
  echo "     bash tools/import_category_from_data.sh Data/Animals --overwrite --allow-low-quality"
  echo "  5) Import category dung color.png + line.svg:"
  echo "     bash tools/import_category_from_data.sh Data/Manga --source-line-format svg --overwrite --continue-on-error"
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
  echo "  bash tools/import_category_from_data.sh Data/Animals --overwrite --continue-on-error"
  echo "  bash tools/import_category_from_data.sh Data/Animals --overwrite --allow-low-quality"
  exit 1
fi

SOURCE_CATEGORY="$1"
shift

GLOBAL_ARGS=()
SUBCOMMAND_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target-category|--only-ids)
      OPTION_NAME="$1"
      SUBCOMMAND_ARGS+=("$OPTION_NAME")
      shift
      if [[ $# -eq 0 ]]; then
        echo "Thiếu giá trị cho option $OPTION_NAME" >&2
        exit 2
      fi
      SUBCOMMAND_ARGS+=("$1")
      ;;
    --overwrite|--continue-on-error)
      SUBCOMMAND_ARGS+=("$1")
      ;;
    *)
      GLOBAL_ARGS+=("$1")
      if [[ $# -gt 1 && "$2" != --* ]]; then
        GLOBAL_ARGS+=("$2")
        shift
      fi
      ;;
  esac
  shift
done

cd "$PROJECT_ROOT"
COMMAND=("$PYTHON_BIN" "$GENERATOR")
if [[ ${#GLOBAL_ARGS[@]} -gt 0 ]]; then
  COMMAND+=("${GLOBAL_ARGS[@]}")
fi
COMMAND+=(batch-source-category "$SOURCE_CATEGORY")
if [[ ${#SUBCOMMAND_ARGS[@]} -gt 0 ]]; then
  COMMAND+=("${SUBCOMMAND_ARGS[@]}")
fi
"${COMMAND[@]}"
