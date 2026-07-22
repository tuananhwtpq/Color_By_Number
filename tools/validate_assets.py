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


def format_terminal_cell(value):
    if value is None or value == "":
        return "-"
    if isinstance(value, bool):
        return "Y" if value else "N"
    return str(value)


def json_dumps_string_keys(value):
    if not isinstance(value, dict):
        return json.dumps(value, ensure_ascii=False, sort_keys=True)
    normalized = {str(key): item for key, item in value.items()}
    return json.dumps(normalized, ensure_ascii=False, sort_keys=True)


def format_table(rows, columns):
    formatted_rows = []
    for row in rows:
        formatted_row = []
        for column in columns:
            value = row.get(column["key"])
            formatter = column.get("formatter", format_terminal_cell)
            formatted_row.append(formatter(value))
        formatted_rows.append(formatted_row)

    widths = []
    for index, column in enumerate(columns):
        cell_widths = [len(row[index]) for row in formatted_rows]
        widths.append(max([len(column["title"])] + cell_widths))

    def format_line(values, fill=None):
        cells = []
        for index, value in enumerate(values):
            align = columns[index].get("align", "left")
            if fill is not None:
                cells.append(fill * widths[index])
            elif align == "right":
                cells.append(value.rjust(widths[index]))
            else:
                cells.append(value.ljust(widths[index]))
        return "  ".join(cells)

    lines = [
        format_line([column["title"] for column in columns]),
        format_line([column["title"] for column in columns], fill="-"),
    ]
    lines.extend(format_line(row) for row in formatted_rows)
    return "\n".join(lines)


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
        "top_3_region_pct": metrics.get("top_3_region_pct", ""),
        "max_color_pct": metrics.get("max_color_pct", ""),
        "top_3_color_pct": metrics.get("top_3_color_pct", ""),
        "tiny_region_count_lt_50": metrics.get("tiny_region_count_lt_50", ""),
        "tiny_region_count_lt_100": metrics.get("tiny_region_count_lt_100", ""),
        "tiny_region_count_lt_200": metrics.get("tiny_region_count_lt_200", ""),
        "tiny_region_pct_lt_50": metrics.get("tiny_region_pct_lt_50", ""),
        "tiny_region_pct_lt_100": metrics.get("tiny_region_pct_lt_100", ""),
        "tiny_region_pct_lt_200": metrics.get("tiny_region_pct_lt_200", ""),
        "config_hidden_label_count": metrics.get("config_hidden_label_count", ""),
        "config_hidden_label_pct": metrics.get("config_hidden_label_pct", ""),
        "estimated_hidden_label_count": metrics.get("estimated_hidden_label_count", ""),
        "estimated_hidden_label_pct": metrics.get("estimated_hidden_label_pct", ""),
        "hidden_label_count": metrics.get("hidden_label_count", ""),
        "hidden_label_pct": metrics.get("hidden_label_pct", ""),
        "label_min_screen_radius_px": metrics.get("label_min_screen_radius_px", ""),
        "median_region_area": metrics.get("median_region_area", ""),
        "p10_region_area": metrics.get("p10_region_area", ""),
        "p25_region_area": metrics.get("p25_region_area", ""),
        "region_density_by_canvas_size": metrics.get("region_density_by_canvas_size", ""),
        "untouchable_region_count": metrics.get("untouchable_region_count", ""),
        "min_touch_target_at_default_zoom": metrics.get("min_touch_target_at_default_zoom", ""),
        "tiny_region_by_number_lt_100": json_dumps_string_keys(
            metrics.get("tiny_region_by_number_lt_100", {})
        ),
        "playable_region_count": metrics.get("playable_region_count", ""),
        "playable_score": metrics.get("playable_score", ""),
        "single_tap_completion_risk": metrics.get("single_tap_completion_risk", ""),
        "preview_mae": metrics.get("preview_mae", ""),
        "preview_similarity_score": metrics.get("preview_similarity_score", ""),
        "shading_preservation_score": metrics.get("shading_preservation_score", ""),
        "has_detail": metrics.get("has_detail", ""),
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
    "top_3_region_pct",
    "max_color_pct",
    "top_3_color_pct",
    "tiny_region_count_lt_50",
    "tiny_region_count_lt_100",
    "tiny_region_count_lt_200",
    "tiny_region_pct_lt_50",
    "tiny_region_pct_lt_100",
    "tiny_region_pct_lt_200",
    "config_hidden_label_count",
    "config_hidden_label_pct",
    "estimated_hidden_label_count",
    "estimated_hidden_label_pct",
    "hidden_label_count",
    "hidden_label_pct",
    "label_min_screen_radius_px",
    "median_region_area",
    "p10_region_area",
    "p25_region_area",
    "region_density_by_canvas_size",
    "untouchable_region_count",
    "min_touch_target_at_default_zoom",
    "tiny_region_by_number_lt_100",
    "playable_region_count",
    "playable_score",
    "single_tap_completion_risk",
    "preview_mae",
    "preview_similarity_score",
    "shading_preservation_score",
    "has_detail",
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
        "Top3 region%",
        "Max color%",
        "Top3 color%",
        "Tiny <50",
        "Tiny <100",
        "Tiny <200",
        "Tiny <50%",
        "Tiny <100%",
        "Tiny <200%",
        "Config hidden labels",
        "Config hidden%",
        "Estimated hidden labels",
        "Estimated hidden%",
        "Hidden alias labels",
        "Hidden alias%",
        "Label min radius",
        "Median area",
        "P10 area",
        "P25 area",
        "Density/MP",
        "Untouchable",
        "Min touch",
        "Tiny by number <100",
        "Playable regions",
        "Playable score",
        "Tap risk",
        "MAE",
        "Similarity",
        "Shading",
        "Detail",
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
                flat["top_3_region_pct"],
                flat["max_color_pct"],
                flat["top_3_color_pct"],
                flat["tiny_region_count_lt_50"],
                flat["tiny_region_count_lt_100"],
                flat["tiny_region_count_lt_200"],
                flat["tiny_region_pct_lt_50"],
                flat["tiny_region_pct_lt_100"],
                flat["tiny_region_pct_lt_200"],
                flat["config_hidden_label_count"],
                flat["config_hidden_label_pct"],
                flat["estimated_hidden_label_count"],
                flat["estimated_hidden_label_pct"],
                flat["hidden_label_count"],
                flat["hidden_label_pct"],
                flat["label_min_screen_radius_px"],
                flat["median_region_area"],
                flat["p10_region_area"],
                flat["p25_region_area"],
                flat["region_density_by_canvas_size"],
                flat["untouchable_region_count"],
                flat["min_touch_target_at_default_zoom"],
                flat["tiny_region_by_number_lt_100"],
                flat["playable_region_count"],
                flat["playable_score"],
                flat["single_tap_completion_risk"],
                flat["preview_mae"],
                flat["preview_similarity_score"],
                flat["shading_preservation_score"],
                flat["has_detail"],
                flat["line_dark_pct"],
                flat["fail_reasons"],
                flat["warnings"],
                flat["recommendation"],
                flat["recommendation_reasons"],
                flat["selected_profile"],
            ]
            output_file.write("| " + " | ".join(report_cell(value) for value in values) + " |\n")


COMPACT_TABLE_COLUMNS = [
    {"key": "grade", "title": "grade", "align": "left"},
    {"key": "score", "title": "score", "align": "right"},
    {"key": "level", "title": "level", "align": "left"},
    {"key": "regions", "title": "regions", "align": "right"},
    {"key": "colors", "title": "colors", "align": "right"},
    {"key": "largest_region_pct", "title": "largest%", "align": "right"},
    {"key": "playable_score", "title": "play", "align": "right"},
    {"key": "tiny_region_pct_lt_100", "title": "tiny100%", "align": "right"},
    {"key": "estimated_hidden_label_pct", "title": "est_hidden%", "align": "right"},
    {"key": "preview_similarity_score", "title": "sim", "align": "right"},
    {"key": "has_detail", "title": "detail", "align": "left"},
    {"key": "fail_reasons", "title": "fails", "align": "left"},
    {"key": "warnings", "title": "warnings", "align": "left"},
    {"key": "recommendation", "title": "recommendation", "align": "left"},
]


FULL_TABLE_COLUMNS = [
    {"key": "grade", "title": "grade", "align": "left"},
    {"key": "score", "title": "score", "align": "right"},
    {"key": "level", "title": "level", "align": "left"},
    {"key": "regions", "title": "regions", "align": "right"},
    {"key": "colors", "title": "colors", "align": "right"},
    {"key": "largest_region_pct", "title": "largest%", "align": "right"},
    {"key": "playable_score", "title": "play", "align": "right"},
    {"key": "tiny_region_pct_lt_100", "title": "tiny100%", "align": "right"},
    {"key": "tiny_region_pct_lt_200", "title": "tiny200%", "align": "right"},
    {"key": "config_hidden_label_pct", "title": "config_hidden%", "align": "right"},
    {"key": "estimated_hidden_label_pct", "title": "est_hidden%", "align": "right"},
    {"key": "median_region_area", "title": "median", "align": "right"},
    {"key": "region_density_by_canvas_size", "title": "density", "align": "right"},
    {"key": "single_tap_completion_risk", "title": "tap_risk", "align": "left"},
    {"key": "preview_mae", "title": "mae", "align": "right"},
    {"key": "preview_similarity_score", "title": "sim", "align": "right"},
    {"key": "shading_preservation_score", "title": "shading", "align": "right"},
    {"key": "has_detail", "title": "detail", "align": "left"},
    {"key": "line_dark_pct", "title": "line_dark%", "align": "right"},
    {"key": "fail_reasons", "title": "fails", "align": "left"},
    {"key": "warnings", "title": "warnings", "align": "left"},
    {"key": "recommendation", "title": "recommendation", "align": "left"},
]


def print_table(rows, wide=False):
    columns = FULL_TABLE_COLUMNS if wide else COMPACT_TABLE_COLUMNS

    table_rows = []
    for row in rows:
        metrics = row["metrics"]
        table_rows.append(
            {
                "grade": row["quality_grade"],
                "score": row["quality_score"],
                "level": row["name"],
                "regions": metrics.get("total_regions"),
                "colors": metrics.get("unique_numbers"),
                "largest_region_pct": format_pct(metrics.get("largest_region_pct")),
                "playable_score": metrics.get("playable_score"),
                "tiny_region_pct_lt_100": format_pct(metrics.get("tiny_region_pct_lt_100")),
                "tiny_region_pct_lt_200": format_pct(metrics.get("tiny_region_pct_lt_200")),
                "config_hidden_label_pct": format_pct(metrics.get("config_hidden_label_pct")),
                "estimated_hidden_label_pct": format_pct(metrics.get("estimated_hidden_label_pct")),
                "hidden_label_pct": format_pct(metrics.get("hidden_label_pct")),
                "median_region_area": metrics.get("median_region_area"),
                "region_density_by_canvas_size": format_pct(metrics.get("region_density_by_canvas_size")),
                "single_tap_completion_risk": metrics.get("single_tap_completion_risk"),
                "preview_mae": format_pct(metrics.get("preview_mae")),
                "preview_similarity_score": format_pct(metrics.get("preview_similarity_score")),
                "shading_preservation_score": format_pct(metrics.get("shading_preservation_score")),
                "has_detail": metrics.get("has_detail"),
                "line_dark_pct": format_pct(metrics.get("line_dark_pct")),
                "fail_reasons": summarize_issue_codes(row["fail_reasons"]),
                "warnings": summarize_issue_codes(row["warnings"]),
                "recommendation": row.get("recommendation", {}).get("action", "-"),
            }
        )
    print(format_table(table_rows, columns))

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
    parser.add_argument(
        "--wide",
        "--full-table",
        action="store_true",
        help="In bảng terminal đầy đủ thay vì bảng compact mặc định.",
    )
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
        print_table(rows, wide=args.wide)

    if args.report_md:
        write_markdown_report(rows, args.report_md)
        print(f"Đã ghi Markdown report: {args.report_md}")
    if args.report_csv:
        write_csv_report(rows, args.report_csv)
        print(f"Đã ghi CSV report: {args.report_csv}")

    return 1 if any(row["fail_reasons"] for row in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
