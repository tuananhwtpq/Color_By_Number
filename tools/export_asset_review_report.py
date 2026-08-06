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


def format_number(value, digits=1):
    if value in ("", "-", None):
        return ""
    try:
        return f"{float(value):.{digits}f}"
    except (TypeError, ValueError):
        return str(value)


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


def classify_compact_issue(row):
    largest = number(row, "largest_region_pct")
    top2 = number(row, "top_2_region_pct")
    hidden = number(row, "estimated_hidden_label_pct") or number(row, "hidden_label_pct")
    tiny100 = number(row, "tiny_region_pct_lt_100")
    region_drop = number(row, "region_count_drop_pct")
    final_regions = number(row, "final_region_count") or number(row, "regions")
    similarity = number(row, "preview_similarity_score")
    overmerge_risk = text(row, "overmerge_risk")
    legitimacy = text(row, "giant_region_legitimacy")

    tags = []
    if largest >= 25 or top2 >= 45:
        tags.append("large_regions")
    elif largest >= 18:
        tags.append("large_region_review")

    if region_drop > 80 or overmerge_risk == "high":
        tags.append("overmerge")
    elif region_drop > 65 or overmerge_risk == "medium":
        tags.append("overmerge_review")

    if hidden > 80 or tiny100 > 45:
        tags.append("tiny_regions")
    elif hidden > 50:
        tags.append("many_hidden_labels")

    if final_regions < 80:
        tags.append("too_few_regions")
    elif final_regions < 120:
        tags.append("low_region_count")

    if similarity and similarity < 93:
        tags.append("color_fidelity")

    if legitimacy == "intentional_flat_background" and "large_regions" in tags:
        tags.append("flat_background_possible")

    if "large_regions" in tags and "overmerge" in tags:
        decision = "replace_or_redraw"
    elif "tiny_regions" in tags or "too_few_regions" in tags:
        decision = "replace_or_redraw"
    elif any(tag in tags for tag in ("large_regions", "overmerge", "many_hidden_labels")):
        decision = "designer_review"
    elif any(tag.endswith("_review") for tag in tags) or "low_region_count" in tags:
        decision = "quick_review"
    else:
        decision = "keep"

    return {
        "decision": decision,
        "issue_tags": ";".join(dict.fromkeys(tags)) or "ok",
    }


def top_region_stats(level_dir):
    config_path = os.path.join(level_dir, "config.json")
    if not os.path.exists(config_path):
        return {"top_2_region_pct": "", "final_region_count_from_config": ""}
    try:
        config = load_json(config_path)
    except (OSError, json.JSONDecodeError):
        return {"top_2_region_pct": "", "final_region_count_from_config": ""}

    width = int(config.get("width") or 0)
    height = int(config.get("height") or 0)
    total_pixels = max(1, width * height)
    areas = sorted(
        (int(region.get("area") or 0) for region in config.get("regions", [])),
        reverse=True,
    )
    top_2_pct = round(sum(areas[:2]) * 100.0 / total_pixels, 2) if areas else ""
    return {
        "top_2_region_pct": top_2_pct,
        "final_region_count_from_config": len(areas),
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
        flat.update(top_region_stats(level_dir))
        if not flat.get("final_region_count") and flat.get("final_region_count_from_config"):
            flat["final_region_count"] = flat["final_region_count_from_config"]
        flat.update(classify_for_designer(flat))
        flat.update(classify_compact_issue(flat))
        rows.append(flat)
    return rows


COMPACT_FIELDS = [
    "level_key",
    "decision",
    "issue_tags",
    "grade",
    "score",
    "final_region_count",
    "largest_region_pct",
    "top_2_region_pct",
    "region_count_drop_pct",
    "hidden_label_pct",
]


def compact_row(row):
    return {
        "level_key": row.get("level_key", ""),
        "decision": row.get("decision", ""),
        "issue_tags": row.get("issue_tags", ""),
        "grade": row.get("grade", ""),
        "score": format_number(row.get("score"), digits=0),
        "final_region_count": format_number(row.get("final_region_count"), digits=0),
        "largest_region_pct": format_number(row.get("largest_region_pct"), digits=1),
        "top_2_region_pct": format_number(row.get("top_2_region_pct"), digits=1),
        "region_count_drop_pct": format_number(row.get("region_count_drop_pct"), digits=1),
        "hidden_label_pct": format_number(row.get("hidden_label_pct"), digits=1),
    }


def write_csv(rows, output_path, compact=True):
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    if compact:
        with open(output_path, "w", newline="", encoding="utf-8") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=COMPACT_FIELDS)
            writer.writeheader()
            writer.writerows(compact_row(row) for row in rows)
        return

    preferred_fields = [
        "decision",
        "issue_tags",
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
        "top_2_region_pct",
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
    parser.add_argument(
        "--wide",
        action="store_true",
        help="Xuất CSV đầy đủ tất cả metric cũ. Mặc định là bản gọn 10 cột để lọc designer.",
    )
    args = parser.parse_args()

    rows = collect_rows(args.assets_path, args.data_root, require_reference=args.require_reference)
    if not rows:
        raise SystemExit("Không tìm thấy level asset nào để export.")
    write_csv(rows, args.output, compact=not args.wide)

    counts = {}
    for row in rows:
        counts[row["review_bucket"]] = counts.get(row["review_bucket"], 0) + 1
    print(f"Đã ghi designer review CSV: {args.output}")
    print(f"Tổng level: {len(rows)}")
    for bucket, count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
        print(f"  - {bucket}: {count}")


if __name__ == "__main__":
    main()
