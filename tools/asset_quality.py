import json
import os
from collections import Counter

from PIL import Image, ImageChops, ImageStat


BACKGROUND_MASK_COLORS = {(0, 0, 0)}


def rgb_to_int(rgb):
    return (rgb[0] << 16) + (rgb[1] << 8) + rgb[2]


def rgb_to_hex(rgb):
    return "#{:02x}{:02x}{:02x}".format(rgb[0], rgb[1], rgb[2])


def parse_hex_color(value):
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    if normalized.startswith("#"):
        normalized = normalized[1:]
    if len(normalized) != 6:
        return None
    try:
        return (
            int(normalized[0:2], 16),
            int(normalized[2:4], 16),
            int(normalized[4:6], 16),
        )
    except ValueError:
        return None


def load_json(path):
    with open(path, "r", encoding="utf-8") as input_file:
        return json.load(input_file)


def resolve_asset_path(level_dir, config, asset_key, fallback_name):
    asset_name = config.get("assets", {}).get(asset_key, fallback_name)
    return os.path.join(level_dir, asset_name)


def collect_config_mask_color_entries(config):
    entries = []
    for region in config.get("regions", []):
        rgb = parse_hex_color(region.get("mask_color"))
        if rgb is not None:
            entries.append(
                {
                    "source": "regions",
                    "id": region.get("id"),
                    "mask_color": rgb,
                    "area": int(region.get("area", 0) or 0),
                }
            )
    return entries


def collect_region_palette_mask_colors(config):
    colors = []
    for item in config.get("region_palette", []):
        rgb = parse_hex_color(item.get("mask_color"))
        if rgb is not None:
            colors.append(rgb)
    return colors


def collect_config_mask_colors(config):
    colors = {entry["mask_color"] for entry in collect_config_mask_color_entries(config)}
    colors.update(collect_region_palette_mask_colors(config))
    return colors


def area_mismatch_tolerance(expected_area):
    if expected_area < 50:
        return max(1, int(round(max(1, expected_area) * 0.03)))
    return max(4, int(round(max(1, expected_area) * 0.03)))


def measure_preview_mae(reference_path, preview_path):
    if not reference_path or not os.path.exists(reference_path) or not os.path.exists(preview_path):
        return None

    with Image.open(reference_path).convert("RGB") as reference_img:
        with Image.open(preview_path).convert("RGB") as preview_img:
            if reference_img.size != preview_img.size:
                preview_img = preview_img.resize(reference_img.size, Image.Resampling.BILINEAR)
            diff = ImageChops.difference(reference_img, preview_img)
            stat = ImageStat.Stat(diff)
            return round(sum(stat.mean) / len(stat.mean), 2)


def measure_line_ink(line_path):
    if not os.path.exists(line_path):
        return {
            "line_dark_pct": None,
            "line_ink_pct": None,
        }

    with Image.open(line_path).convert("L") as line_img:
        histogram = line_img.histogram()
        total_pixels = max(1, line_img.width * line_img.height)
        dark_pixels = sum(histogram[:80])
        ink_pixels = sum(histogram[:180])
        return {
            "line_dark_pct": round(dark_pixels * 100.0 / total_pixels, 2),
            "line_ink_pct": round(ink_pixels * 100.0 / total_pixels, 2),
        }


def measure_mask_config(level_dir, config, mask_path):
    regions = config.get("regions", [])
    config_entries = collect_config_mask_color_entries(config)
    config_colors = collect_config_mask_colors(config)
    entry_counts = Counter(entry["mask_color"] for entry in config_entries)
    duplicate_colors = sorted(
        rgb_to_hex(color)
        for color, count in entry_counts.items()
        if count > 1
    )
    region_palette_counts = Counter(collect_region_palette_mask_colors(config))
    duplicate_region_palette_colors = sorted(
        rgb_to_hex(color)
        for color, count in region_palette_counts.items()
        if count > 1
    )
    total_pixels = max(1, int(config.get("width", 0)) * int(config.get("height", 0)))

    if not os.path.exists(mask_path):
        return {
            "total_regions": len(regions),
            "unique_numbers": len({region.get("number") for region in regions}),
            "largest_region_pct": 0.0,
            "tiny_region_count_lt_50": 0,
            "tiny_region_count_lt_200": 0,
            "mask_config_mismatch_count": max(1, len(config_colors)),
            "mask_missing_config_colors": sorted(rgb_to_hex(color) for color in config_colors),
            "config_missing_mask_colors": [],
            "unexpected_mask_colors": [],
            "duplicate_mask_colors": duplicate_colors,
            "duplicate_region_palette_mask_colors": duplicate_region_palette_colors,
            "region_area_mismatch_count": 0,
            "region_area_mismatches": [],
        }

    with Image.open(mask_path).convert("RGB") as mask_img:
        mask_pixels = Counter(mask_img.getdata())
        if not config.get("width") or not config.get("height"):
            total_pixels = max(1, mask_img.width * mask_img.height)

    mask_region_counts = {
        color: count
        for color, count in mask_pixels.items()
        if color not in BACKGROUND_MASK_COLORS
    }
    mask_colors = set(mask_region_counts.keys())
    missing_in_mask = config_colors - mask_colors
    missing_in_config = mask_colors - config_colors

    if mask_region_counts:
        area_values = list(mask_region_counts.values())
    elif regions:
        area_values = [int(region.get("area", 0)) for region in regions]
    else:
        area_values = []

    area_mismatches = []
    for entry in config_entries:
        expected_area = entry["area"]
        actual_area = mask_region_counts.get(entry["mask_color"], 0)
        tolerance = area_mismatch_tolerance(expected_area)
        delta = actual_area - expected_area
        if abs(delta) <= tolerance:
            continue
        area_mismatches.append(
            {
                "region_id": entry["id"],
                "mask_color": rgb_to_hex(entry["mask_color"]),
                "config_area": expected_area,
                "mask_pixel_count": actual_area,
                "delta": delta,
                "tolerance": tolerance,
            }
        )

    largest_area = max(area_values, default=0)
    tiny_lt_50 = sum(1 for area in area_values if 0 < area < 50)
    tiny_lt_200 = sum(1 for area in area_values if 0 < area < 200)

    mismatch_count = len(missing_in_mask) + len(missing_in_config)
    if abs(len(mask_colors) - len(config_colors)) > max(3, len(config_colors) * 0.05):
        mismatch_count += abs(len(mask_colors) - len(config_colors))

    return {
        "total_regions": int(config.get("stats", {}).get("total_regions") or len(regions)),
        "unique_numbers": int(
            config.get("stats", {}).get("unique_numbers")
            or len({region.get("number") for region in regions})
        ),
        "largest_region_pct": round(largest_area * 100.0 / total_pixels, 2),
        "tiny_region_count_lt_50": tiny_lt_50,
        "tiny_region_count_lt_200": tiny_lt_200,
        "mask_config_mismatch_count": mismatch_count,
        "mask_missing_config_colors": sorted(rgb_to_hex(color) for color in missing_in_mask),
        "config_missing_mask_colors": sorted(rgb_to_hex(color) for color in missing_in_config),
        "unexpected_mask_colors": sorted(rgb_to_hex(color) for color in missing_in_config),
        "duplicate_mask_colors": duplicate_colors,
        "duplicate_region_palette_mask_colors": duplicate_region_palette_colors,
        "region_area_mismatch_count": len(area_mismatches),
        "region_area_mismatches": area_mismatches[:50],
    }


def make_issue(code, message, value=None, threshold=None):
    issue = {
        "code": code,
        "message": message,
    }
    if value is not None:
        issue["value"] = value
    if threshold is not None:
        issue["threshold"] = threshold
    return issue


def score_quality(metrics):
    warnings = []
    fail_reasons = []
    score = 100

    largest_region_pct = metrics.get("largest_region_pct") or 0
    if largest_region_pct > 70:
        fail_reasons.append(
            make_issue(
                "GIANT_REGION",
                "Vùng lớn nhất chiếm hơn 70% ảnh; line có thể bị hở nên nhiều mảng bị dính vào nhau.",
                largest_region_pct,
                70,
            )
        )
        score -= 42
    elif largest_region_pct > 50:
        warnings.append(
            make_issue(
                "GIANT_REGION_WARNING",
                "Vùng lớn nhất chiếm hơn 50% ảnh; nên kiểm tra line bị hở hoặc nền bị tính là vùng tô.",
                largest_region_pct,
                50,
            )
        )
        score -= 18

    total_regions = metrics.get("total_regions") or 0
    if total_regions < 8:
        fail_reasons.append(
            make_issue(
                "LOW_REGION_COUNT",
                "Số vùng tô quá thấp cho một level Color by Number; nhiều vùng có thể đang bị dính.",
                total_regions,
                8,
            )
        )
        score -= 28
    elif total_regions > 900:
        warnings.append(
            make_issue(
                "TOO_MANY_REGIONS",
                "Số vùng tô rất cao; level có thể nặng và khó thao tác trên mobile.",
                total_regions,
                900,
            )
        )
        score -= 12

    preview_mae = metrics.get("preview_mae")
    if preview_mae is not None:
        if preview_mae > 70:
            fail_reasons.append(
                make_issue(
                    "PREVIEW_MAE_TOO_HIGH",
                    "Preview sinh ra lệch ảnh màu gốc quá nhiều.",
                    preview_mae,
                    70,
                )
            )
            score -= 32
        elif preview_mae > 50:
            warnings.append(
                make_issue(
                    "PREVIEW_MAE_HIGH",
                    "Preview sinh ra lệch ảnh màu gốc khá nhiều.",
                    preview_mae,
                    50,
                )
            )
            score -= 15
    elif metrics.get("reference_missing"):
        issue = make_issue(
            "REFERENCE_MISSING",
            "Không tìm thấy ảnh màu gốc/reference nên không tính được preview_mae.",
        )
        if metrics.get("reference_required"):
            fail_reasons.append(issue)
            score -= 24
        else:
            warnings.append(issue)
            score -= 6

    line_dark_pct = metrics.get("line_dark_pct")
    if line_dark_pct is not None and line_dark_pct < 2:
        warnings.append(
            make_issue(
                "LINE_TOO_LIGHT",
                "Tỷ lệ pixel line đủ tối dưới 2%; line có thể quá nhẹ để tách vùng ổn định.",
                line_dark_pct,
                2,
            )
        )
        score -= 10

    mismatch_count = metrics.get("mask_config_mismatch_count") or 0
    if mismatch_count > 0:
        fail_reasons.append(
            make_issue(
                "MASK_CONFIG_MISMATCH",
                "mask.png và config.json không khớp mapping màu vùng.",
                mismatch_count,
                0,
            )
        )
        score -= min(45, 16 + mismatch_count * 4)

    duplicate_mask_colors = metrics.get("duplicate_mask_colors") or []
    if duplicate_mask_colors:
        fail_reasons.append(
            make_issue(
                "DUPLICATE_MASK_COLOR",
                "Hai hoặc nhiều region trong config.json dùng cùng mask_color; app không thể hit-test/fill tách biệt.",
                len(duplicate_mask_colors),
                0,
            )
        )
        score -= min(35, 18 + len(duplicate_mask_colors) * 3)

    duplicate_region_palette_mask_colors = (
        metrics.get("duplicate_region_palette_mask_colors") or []
    )
    if duplicate_region_palette_mask_colors:
        fail_reasons.append(
            make_issue(
                "DUPLICATE_MASK_COLOR_IN_REGION_PALETTE",
                "region_palette trong config.json có mask_color bị trùng; dữ liệu mapping palette dễ gây nhầm khi debug/migration.",
                len(duplicate_region_palette_mask_colors),
                0,
            )
        )
        score -= min(25, 12 + len(duplicate_region_palette_mask_colors) * 3)

    area_mismatch_count = metrics.get("region_area_mismatch_count") or 0
    if area_mismatch_count > 0:
        fail_reasons.append(
            make_issue(
                "REGION_AREA_MISMATCH",
                "Diện tích region trong config.json lệch pixel thật trong mask.png.",
                area_mismatch_count,
                0,
            )
        )
        score -= min(35, 12 + area_mismatch_count * 2)

    tiny_count = metrics.get("tiny_region_count_lt_50") or 0
    if total_regions and tiny_count > max(20, total_regions * 0.25):
        warnings.append(
            make_issue(
                "MANY_TINY_REGIONS",
                "Có nhiều vùng rất nhỏ dưới 50px; user có thể khó tap hoặc khó đọc số.",
                tiny_count,
                max(20, int(total_regions * 0.25)),
            )
        )
        score -= 8

    score = max(0, min(100, int(round(score))))
    if fail_reasons:
        grade = "D"
    elif score >= 82:
        grade = "B" if warnings else "A"
    elif score >= 65:
        grade = "B"
    elif score >= 45:
        grade = "C"
    else:
        grade = "D"

    return {
        "quality_grade": grade,
        "quality_score": score,
        "warnings": warnings,
        "fail_reasons": fail_reasons,
    }


def issue_codes(report, key):
    return {issue.get("code") for issue in report.get(key, [])}


def build_recommendation(report):
    grade = report.get("quality_grade")
    warning_codes = issue_codes(report, "warnings")
    fail_codes = issue_codes(report, "fail_reasons")
    all_codes = warning_codes | fail_codes

    reasons = []
    design_focus = []

    if "LINE_TOO_LIGHT" in all_codes and "GIANT_REGION" in all_codes:
        reasons.append("DESIGN_FIX_LINE")
        design_focus.append("line")
    elif "LINE_TOO_LIGHT" in all_codes:
        reasons.append("REVIEW_LINE_CONTRAST")
        design_focus.append("line")

    if "GIANT_REGION_WARNING" in all_codes:
        reasons.append("REGENERATE_AUTO_OR_FIX_LINE")
        design_focus.append("line")
    if "GIANT_REGION" in all_codes and "DESIGN_FIX_LINE" not in reasons:
        reasons.append("DESIGN_FIX_LINE")
        design_focus.append("line")

    if "PREVIEW_MAE_HIGH" in all_codes or "PREVIEW_MAE_TOO_HIGH" in all_codes:
        reasons.append("DESIGN_FIX_COLOR")
        design_focus.append("color_alignment")

    if "TOO_MANY_REGIONS" in all_codes:
        reasons.append("REVIEW_DETAIL_DENSITY")
        design_focus.append("detail_density")

    if any(
        code in all_codes
        for code in [
            "MASK_CONFIG_MISMATCH",
            "DUPLICATE_MASK_COLOR",
            "DUPLICATE_MASK_COLOR_IN_REGION_PALETTE",
            "REGION_AREA_MISMATCH",
        ]
    ):
        reasons.append("REGENERATE_AUTO")

    if grade == "A":
        action = "KEEP"
    elif grade == "B":
        action = "REVIEW_VISUAL"
    elif grade == "C":
        action = "REGENERATE_AUTO"
        if "DESIGN_FIX_LINE" in reasons or "DESIGN_FIX_COLOR" in reasons:
            action = "REVIEW_VISUAL"
    elif grade == "D":
        action = "EXCLUDE_DEMO"
    else:
        action = "REVIEW_VISUAL"

    if not reasons:
        reasons.append(action)

    return {
        "action": action,
        "reasons": list(dict.fromkeys(reasons)),
        "design_focus": list(dict.fromkeys(design_focus)),
    }


def evaluate_level_dir(level_dir, reference_path=None, require_reference=False):
    config_path = os.path.join(level_dir, "config.json")
    config = load_json(config_path)
    mask_path = resolve_asset_path(level_dir, config, "mask", "mask.png")
    line_path = resolve_asset_path(level_dir, config, "line", "line.png")
    preview_path = resolve_asset_path(level_dir, config, "preview", "preview_colored.png")

    metrics = {}
    metrics.update(measure_mask_config(level_dir, config, mask_path))
    metrics.update(measure_line_ink(line_path))
    metrics["reference_missing"] = not reference_path or not os.path.exists(reference_path)
    metrics["reference_required"] = require_reference
    metrics["preview_mae"] = measure_preview_mae(reference_path, preview_path)

    quality = score_quality(metrics)
    report = {
        "schema_version": 1,
        **quality,
        "metrics": metrics,
    }
    report["recommendation"] = build_recommendation(report)
    return report


def merge_quality_report(base_report, quality_report):
    merged_warnings = list(base_report.get("warnings", []))
    existing_codes = {warning.get("code") for warning in merged_warnings}
    for warning in quality_report.get("warnings", []):
        if warning.get("code") not in existing_codes:
            merged_warnings.append(warning)
            existing_codes.add(warning.get("code"))

    return {
        **base_report,
        "quality_grade": quality_report["quality_grade"],
        "quality_score": quality_report["quality_score"],
        "warnings": merged_warnings,
        "fail_reasons": quality_report["fail_reasons"],
        "metrics": quality_report["metrics"],
        "recommendation": quality_report.get("recommendation")
        or build_recommendation(quality_report),
    }
