import argparse
import csv
import json
import os
import sys

try:
    from asset_quality import build_recommendation, evaluate_level_dir, merge_quality_report, load_json
except ImportError:
    from tools.asset_quality import build_recommendation, evaluate_level_dir, merge_quality_report, load_json


IMAGE_EXTENSIONS = (".png", ".webp", ".jpg", ".jpeg")


def find_reference_image(data_root, category, level):
    if not data_root:
        return None
    level_dir = os.path.join(data_root, category, level)
    if not os.path.isdir(level_dir):
        return None
    candidates = []
    for file_name in os.listdir(level_dir):
        lower_name = file_name.lower()
        if not lower_name.endswith(IMAGE_EXTENSIONS):
            continue
        if "color" in lower_name or "ref" in lower_name or "paint" in lower_name:
            candidates.append(os.path.join(level_dir, file_name))
    return sorted(candidates)[0] if candidates else None


def iter_level_dirs(path):
    if os.path.isfile(os.path.join(path, "config.json")):
        yield path
        return

    for category in sorted(os.listdir(path)):
        category_dir = os.path.join(path, category)
        if not os.path.isdir(category_dir) or category.startswith("."):
            continue
        for level in sorted(os.listdir(category_dir)):
            level_dir = os.path.join(category_dir, level)
            if os.path.isfile(os.path.join(level_dir, "config.json")):
                yield level_dir


def infer_category_level(root_path, level_dir):
    rel_path = os.path.relpath(level_dir, root_path)
    parts = rel_path.split(os.sep)
    if len(parts) >= 2:
        return parts[-2], parts[-1]
    return os.path.basename(os.path.dirname(level_dir)), os.path.basename(level_dir)


def summarize_issue_codes(issues):
    return ",".join(issue.get("code", "?") for issue in issues) or "-"


def format_pct(value):
    if value is None:
        return "-"
    return f"{value:.1f}"


def selected_profile_from_report(report):
    generation_params = report.get("generation_params") or {}
    selected = generation_params.get("selected_preprocessing") or {}
    if not selected:
        return "-"
    profile = selected.get("profile", "-")
    threshold = selected.get("brightness_threshold", "-")
    close_radius = selected.get("line_close_radius", "-")
    return f"{profile}:{threshold}:{close_radius}"


def report_cell(value):
    if value is None:
        return ""
    return str(value)


def flatten_row(row):
    metrics = row.get("metrics", {})
    recommendation = row.get("recommendation") or build_recommendation(row)
    return {
        "category": row.get("category", ""),
        "level": row.get("level", ""),
        "grade": row.get("quality_grade", ""),
        "score": row.get("quality_score", ""),
        "regions": metrics.get("total_regions", ""),
        "colors": metrics.get("unique_numbers", ""),
        "largest_region_pct": metrics.get("largest_region_pct", ""),
        "preview_mae": metrics.get("preview_mae", ""),
        "line_dark_pct": metrics.get("line_dark_pct", ""),
        "fail_reasons": summarize_issue_codes(row.get("fail_reasons", [])),
        "warnings": summarize_issue_codes(row.get("warnings", [])),
        "recommendation": recommendation.get("action", ""),
        "recommendation_reasons": ",".join(recommendation.get("reasons", [])),
        "design_focus": ",".join(recommendation.get("design_focus", [])),
        "selected_profile": row.get("selected_profile") or selected_profile_from_report(row),
        "path": row.get("path", ""),
    }


REPORT_COLUMNS = [
    "category",
    "level",
    "grade",
    "score",
    "regions",
    "colors",
    "largest_region_pct",
    "preview_mae",
    "line_dark_pct",
    "fail_reasons",
    "warnings",
    "recommendation",
    "recommendation_reasons",
    "design_focus",
    "selected_profile",
    "path",
]


def ensure_parent_dir(path):
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)


def write_csv_report(rows, output_path):
    ensure_parent_dir(output_path)
    with open(output_path, "w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=REPORT_COLUMNS)
        writer.writeheader()
        for row in rows:
            writer.writerow(flatten_row(row))


def write_markdown_report(rows, output_path):
    ensure_parent_dir(output_path)
    headers = [
        "Category",
        "Level",
        "Grade",
        "Score",
        "Regions",
        "Colors",
        "Largest%",
        "MAE",
        "Line dark%",
        "Fails",
        "Warnings",
        "Recommendation",
        "Reasons",
        "Selected profile",
    ]
    with open(output_path, "w", encoding="utf-8") as output_file:
        output_file.write("# Asset Quality Report\n\n")
        output_file.write("| " + " | ".join(headers) + " |\n")
        output_file.write("| " + " | ".join("---" for _ in headers) + " |\n")
        for row in rows:
            flat = flatten_row(row)
            values = [
                flat["category"],
                flat["level"],
                flat["grade"],
                flat["score"],
                flat["regions"],
                flat["colors"],
                flat["largest_region_pct"],
                flat["preview_mae"],
                flat["line_dark_pct"],
                flat["fail_reasons"],
                flat["warnings"],
                flat["recommendation"],
                flat["recommendation_reasons"],
                flat["selected_profile"],
            ]
            output_file.write("| " + " | ".join(report_cell(value) for value in values) + " |\n")


def print_table(rows):
    headers = [
        "grade",
        "score",
        "level",
        "regions",
        "colors",
        "largest%",
        "mae",
        "line_dark%",
        "fails",
        "warnings",
        "recommendation",
    ]
    print(" | ".join(headers))
    print(" | ".join("-" * len(header) for header in headers))
    for row in rows:
        metrics = row["metrics"]
        print(
            " | ".join(
                [
                    row["quality_grade"],
                    str(row["quality_score"]),
                    row["name"],
                    str(metrics.get("total_regions", "-")),
                    str(metrics.get("unique_numbers", "-")),
                    format_pct(metrics.get("largest_region_pct")),
                    format_pct(metrics.get("preview_mae")),
                    format_pct(metrics.get("line_dark_pct")),
                    summarize_issue_codes(row["fail_reasons"]),
                    summarize_issue_codes(row["warnings"]),
                    row.get("recommendation", {}).get("action", "-"),
                ]
            )
        )

    totals = {}
    for row in rows:
        totals[row["quality_grade"]] = totals.get(row["quality_grade"], 0) + 1
    print("\nSummary:", " ".join(f"{grade}={totals.get(grade, 0)}" for grade in ["A", "B", "C", "D"]))


def write_debug_report(level_dir, report):
    debug_path = os.path.join(level_dir, "debug_report.json")
    if os.path.exists(debug_path):
        base_report = load_json(debug_path)
    else:
        base_report = {"schema_version": 1, "warnings": []}
    merged_report = merge_quality_report(base_report, report)
    with open(debug_path, "w", encoding="utf-8") as output_file:
        json.dump(merged_report, output_file, indent=2, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(
        description="Validate generated Color by Number assets and print A/B/C/D quality summary."
    )
    parser.add_argument(
        "assets_path",
        nargs="?",
        default=os.path.join("app", "src", "main", "assets"),
        help="Thư mục assets root hoặc một thư mục level có config.json.",
    )
    parser.add_argument(
        "--data-root",
        default="Data",
        help="Thư mục Data dùng để tìm color.png gốc khi tính preview_mae.",
    )
    parser.add_argument("--json", action="store_true", help="In kết quả dạng JSON.")
    parser.add_argument(
        "--write-debug-report",
        action="store_true",
        help="Ghi grade/metrics/fail_reasons vào debug_report.json của từng level.",
    )
    parser.add_argument(
        "--require-reference",
        action="store_true",
        help="Fail level nếu không tìm thấy ảnh màu gốc để tính preview_mae.",
    )
    parser.add_argument("--report-md", help="Ghi report tổng hợp dạng Markdown vào path này.")
    parser.add_argument("--report-csv", help="Ghi report tổng hợp dạng CSV vào path này.")
    args = parser.parse_args()

    root_path = os.path.abspath(args.assets_path)
    rows = []
    for level_dir in iter_level_dirs(root_path):
        category, level = infer_category_level(root_path, level_dir)
        reference_path = find_reference_image(args.data_root, category, level)
        report = evaluate_level_dir(
            level_dir,
            reference_path=reference_path,
            require_reference=args.require_reference,
        )
        existing_debug_report = {}
        if os.path.exists(os.path.join(level_dir, "debug_report.json")):
            existing_debug_report = load_json(os.path.join(level_dir, "debug_report.json"))
        selected_profile = selected_profile_from_report(existing_debug_report)
        if args.write_debug_report:
            write_debug_report(level_dir, report)
        rows.append(
            {
                "name": f"{category}/{level}",
                "category": category,
                "level": level,
                "path": level_dir,
                "selected_profile": selected_profile,
                **report,
            }
        )

    if args.json:
        print(json.dumps(rows, indent=2, ensure_ascii=False))
    else:
        print_table(rows)

    if args.report_md:
        write_markdown_report(rows, args.report_md)
        print(f"Đã ghi Markdown report: {args.report_md}")
    if args.report_csv:
        write_csv_report(rows, args.report_csv)
        print(f"Đã ghi CSV report: {args.report_csv}")

    return 1 if any(row["fail_reasons"] for row in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
