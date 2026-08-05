import argparse
import csv
import json
import os

try:
    from validate_assets import (
        find_reference_image,
        flatten_row,
        infer_category_level,
        iter_level_dirs,
        selected_profile_from_report,
    )
    from asset_quality import evaluate_level_dir, load_json
except ImportError:
    from tools.validate_assets import (
        find_reference_image,
        flatten_row,
        infer_category_level,
        iter_level_dirs,
        selected_profile_from_report,
    )
    from tools.asset_quality import evaluate_level_dir, load_json


def number(row, key, default=0.0):
    try:
        value = row.get(key, "")
        if value in ("", "-", None):
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def text(row, key):
    return row.get(key, "") or ""


def has_issue(row, code):
    return code in (text(row, "fail_reasons") + "," + text(row, "warnings"))


def classify_for_designer(row):
    largest = number(row, "largest_region_pct")
    hidden = number(row, "estimated_hidden_label_pct")
    tiny100 = number(row, "tiny_region_pct_lt_100")
    tiny200 = number(row, "tiny_region_pct_lt_200")
    untouchable = number(row, "untouchable_region_count")
    regions = number(row, "regions")
    similarity = number(row, "preview_similarity_score")
    detail_dependency = number(row, "detail_dependency_score")
    overmerge_risk = text(row, "overmerge_risk")
    region_drop = number(row, "region_count_drop_pct")
    final_regions = number(row, "final_region_count") or number(row, "regions")
    legitimacy = text(row, "giant_region_legitimacy")
    giant_std = text(row, "giant_region_reference_color_std")

    reasons = []
    priority = "P3"
    bucket = "Keep / low risk"
    action = "Keep"
    background = "No giant-background concern"
    tiny_detail = "Tiny detail acceptable"

    if overmerge_risk == "high":
        bucket = "Foreground over-merge"
        action = "Review/fix source line separation; do not accept based on preview similarity"
        priority = "P1"
        reasons.append(
            f"overmerge high, final regions {final_regions:.0f}, drop {region_drop:.1f}%, detail dependency {detail_dependency:.1f}"
        )
    elif overmerge_risk == "medium":
        bucket = "Over-merge review"
        action = "Inspect debug_regions against source before accepting"
        priority = "P2"
        reasons.append(f"overmerge medium, drop {region_drop:.1f}%")

    if largest > 55:
        if legitimacy == "intentional_flat_background":
            background = (
                "Large flat background: likely valid one-tap background if designer intended it"
            )
            if overmerge_risk != "high":
                bucket = "Background exception candidate"
                action = "Designer confirm background is intended; mark exception if yes"
                priority = "P2"
            reasons.append(f"largest {largest:.1f}% but flat reference std {giant_std}")
        elif legitimacy == "needs_review":
            background = "Large non-flat region: likely open line/merged content"
            bucket = "Designer line fix"
            action = "Fix/close line separation or simplify background region"
            priority = "P1"
            reasons.append(f"largest {largest:.1f}% with non-flat std {giant_std}")
        else:
            background = "Large region: inspect intended background vs line leak"
            bucket = "Review giant region"
            action = "Inspect source; decide exception vs line fix"
            priority = "P1"
            reasons.append(f"largest {largest:.1f}%")
    elif largest > 50:
        background = "Moderate large region warning; check one-tap completion feel"
        if bucket == "Keep / low risk":
            bucket = "Review giant region"
            action = "Quick visual check for background/merged region"
            priority = "P2"
        reasons.append(f"largest warning {largest:.1f}%")

    tiny_hard = hidden > 80 or tiny100 > 45
    tiny_severe = hidden > 90 or tiny100 > 60 or tiny200 > 75 or regions > 1000
    untouchable_issue = has_issue(row, "UNTOUCHABLE_REGIONS") or has_issue(
        row, "UNTOUCHABLE_REGIONS_WARNING"
    )
    if tiny_hard or untouchable_issue:
        parts = []
        if hidden > 80:
            parts.append(f"hidden labels {hidden:.1f}%")
        if tiny100 > 45:
            parts.append(f"tiny<100 {tiny100:.1f}%")
        if tiny200 > 60:
            parts.append(f"tiny<200 {tiny200:.1f}%")
        if untouchable_issue:
            parts.append(f"untouchable count {untouchable:.0f}")
        tiny_detail = "Too many unreadable/tiny targets: " + ", ".join(parts)
        if tiny_severe:
            bucket = "Tiny detail too dense"
            action = "Simplify/remove micro details or replace source; generator should not over-merge to pass"
            priority = "P1"
        elif bucket == "Keep / low risk":
            bucket = "Playable-at-zoom review"
            action = "Check max-zoom playability; fix source if truly unreadable/tiny"
            priority = "P2"
        reasons.extend(parts)

    if similarity < 93:
        if bucket == "Keep / low risk":
            bucket = "Color fidelity review"
            action = "Compare preview against color source"
            priority = "P2"
        reasons.append(f"similarity {similarity:.1f}%")

    if text(row, "grade") == "A" and not reasons:
        priority = "P4"
    elif text(row, "grade") == "B" and bucket == "Keep / low risk":
        bucket = "Soft warning"
        action = "Optional review; likely usable"
        priority = "P3"

    return {
        "priority": priority,
        "designer_status": "Needs Review" if priority in {"P1", "P2"} else "Optional",
        "owner": "Designer",
        "review_bucket": bucket,
        "designer_action": action,
        "background_interpretation": background,
        "tiny_detail_interpretation": tiny_detail,
        "why_flagged": "; ".join(dict.fromkeys(reasons)) or "No major issue",
        "exception_candidate": "Yes" if bucket == "Background exception candidate" else "No",
    }


def collect_rows(assets_path, data_root, require_reference=False):
    root_path = os.path.abspath(assets_path)
    rows = []
    for level_dir in iter_level_dirs(root_path):
        category, level = infer_category_level(root_path, level_dir)
        reference_path = find_reference_image(data_root, category, level)
        report = evaluate_level_dir(
            level_dir,
            reference_path=reference_path,
            require_reference=require_reference,
        )
        debug_report = {}
        debug_path = os.path.join(level_dir, "debug_report.json")
        if os.path.exists(debug_path):
            debug_report = load_json(debug_path)
        row = {
            "name": f"{category}/{level}",
            "category": category,
            "level": level,
            "path": level_dir,
            "selected_profile": selected_profile_from_report(debug_report),
            **report,
        }
        flat = flatten_row(row)
        metrics = report.get("metrics", {})
        flat["giant_region_legitimacy"] = metrics.get("giant_region_legitimacy", "")
        flat["giant_region_reference_color_std"] = (
            json.dumps(metrics.get("giant_region_reference_color_std"), ensure_ascii=False)
            if metrics.get("giant_region_reference_color_std") is not None
            else ""
        )
        flat["background_region_candidate"] = metrics.get("background_region_candidate", "")
        flat["similarity"] = metrics.get("preview_similarity_score", "")
        flat["flat_similarity"] = metrics.get("flat_similarity_score", "")
        flat["detail_dependency_score"] = metrics.get("detail_dependency_score", "")
        flat["raw_region_count"] = metrics.get("raw_region_count", "")
        flat["final_region_count"] = metrics.get("final_region_count", metrics.get("total_regions", ""))
        flat["region_count_drop_pct"] = metrics.get("region_count_drop_pct", "")
        flat["largest_region_pct"] = metrics.get("largest_region_pct", flat.get("largest_region_pct", ""))
        flat["hidden_label_pct"] = metrics.get("hidden_label_pct", "")
        flat["tiny_region_pct_lt_100"] = metrics.get("tiny_region_pct_lt_100", "")
        flat["overmerge_risk"] = metrics.get("overmerge_risk", "")
        flat["level_key"] = f"{category}/{level}"
        flat.update(classify_for_designer(flat))
        rows.append(flat)
    return rows


def write_csv(rows, output_path):
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    preferred_fields = [
        "priority",
        "designer_status",
        "owner",
        "review_bucket",
        "designer_action",
        "level_key",
        "category",
        "level",
        "grade",
        "score",
        "similarity",
        "preview_similarity_score",
        "flat_similarity",
        "flat_similarity_score",
        "detail_dependency_score",
        "raw_region_count",
        "final_region_count",
        "region_count_drop_pct",
        "largest_region_pct",
        "giant_region_legitimacy",
        "giant_region_reference_color_std",
        "hidden_label_pct",
        "tiny_region_pct_lt_100",
        "tiny_region_pct_lt_200",
        "estimated_hidden_label_pct",
        "overmerge_risk",
        "untouchable_region_count",
        "regions",
        "colors",
        "playable_score",
        "fail_reasons",
        "warnings",
        "background_interpretation",
        "tiny_detail_interpretation",
        "why_flagged",
        "exception_candidate",
        "path",
    ]
    fields = preferred_fields + [key for key in rows[0].keys() if key not in preferred_fields]
    with open(output_path, "w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(
        description="Export Color By Number asset quality report for designer review."
    )
    parser.add_argument(
        "assets_path",
        nargs="?",
        default=os.path.join("app", "src", "main", "assets"),
        help="Assets root hoặc một folder level có config.json.",
    )
    parser.add_argument(
        "--data-root",
        default="Data",
        help="Data root dùng để tìm color/ref image gốc.",
    )
    parser.add_argument(
        "--output",
        default=os.path.join("outputs", "color_by_number_asset_review", "color_by_number_designer_review.csv"),
        help="CSV output path.",
    )
    parser.add_argument(
        "--require-reference",
        action="store_true",
        help="Fail report nếu không tìm thấy ảnh màu gốc.",
    )
    args = parser.parse_args()

    rows = collect_rows(args.assets_path, args.data_root, require_reference=args.require_reference)
    if not rows:
        raise SystemExit("Không tìm thấy level asset nào để export.")
    write_csv(rows, args.output)

    counts = {}
    for row in rows:
        counts[row["review_bucket"]] = counts.get(row["review_bucket"], 0) + 1
    print(f"Đã ghi designer review CSV: {args.output}")
    print(f"Tổng level: {len(rows)}")
    for bucket, count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
        print(f"  - {bucket}: {count}")


if __name__ == "__main__":
    main()
