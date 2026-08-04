import argparse
import glob
import json
import os
import sys

try:
    from generate_level import DEFAULT_ASSETS_ROOT, evaluate_quality_gate
except ImportError:
    from tools.generate_level import DEFAULT_ASSETS_ROOT, evaluate_quality_gate


def find_debug_reports(assets_root):
    return sorted(glob.glob(os.path.join(assets_root, "*", "*", "debug_report.json")))


def level_name_from_report_path(assets_root, report_path):
    rel_path = os.path.relpath(os.path.dirname(report_path), assets_root)
    parts = rel_path.split(os.sep)
    if len(parts) >= 2:
        return f"{parts[-2]}/{parts[-1]}"
    return rel_path


def scan_asset_quality(assets_root):
    """Quét toàn bộ debug_report.json đã build sẵn trong assets_root và trả về danh sách
    level không đạt hard gate (quality_grade D/F, largest_region_pct > 55%, hoặc
    giant_region_count > 0) — dùng field đã ghi sẵn, không re-generate/re-process ảnh,
    để chạy nhanh như 1 regression guard trong CI.
    """
    violations = []
    report_paths = find_debug_reports(assets_root)
    for report_path in report_paths:
        with open(report_path, "r", encoding="utf-8") as input_file:
            report = json.load(input_file)
        reasons = evaluate_quality_gate(report)
        if reasons:
            violations.append(
                {
                    "level": level_name_from_report_path(assets_root, report_path),
                    "path": report_path,
                    "quality_grade": report.get("quality_grade"),
                    "reasons": reasons,
                }
            )
    return report_paths, violations


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Quét toàn bộ app/src/main/assets/*/*/debug_report.json và fail nếu có level "
            "nào không đạt hard gate chất lượng (quality_grade D/F, largest_region_pct > 55%, "
            "hoặc giant_region_count > 0)."
        )
    )
    parser.add_argument(
        "assets_root",
        nargs="?",
        default=DEFAULT_ASSETS_ROOT,
        help=f"Thư mục assets root. Mặc định: {DEFAULT_ASSETS_ROOT}",
    )
    args = parser.parse_args()

    report_paths, violations = scan_asset_quality(args.assets_root)
    print(f"Đã quét {len(report_paths)} debug_report.json trong '{args.assets_root}'.")

    if violations:
        print(f"\nPHÁT HIỆN {len(violations)} LEVEL KHÔNG ĐẠT HARD GATE:")
        for violation in violations:
            print(
                f"  - {violation['level']} (grade={violation['quality_grade']}): "
                f"{', '.join(violation['reasons'])}"
            )
        return 1

    print("Tất cả level đều đạt hard gate chất lượng.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
