import json
import math
import os
import statistics
from collections import Counter

from PIL import Image, ImageChops, ImageStat


BACKGROUND_MASK_COLORS = {(0, 0, 0)}
PLAYABLE_REGION_MIN_AREA = 200
DEFAULT_VIEW_SIZE = (1080, 1080)
ANDROID_LABEL_MIN_SCREEN_RADIUS_PX = 25


def normalize_profile(profile):
    return (profile or "casual").lower()


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


def measure_preview_similarity_score(reference_path, preview_path):
    preview_mae = measure_preview_mae(reference_path, preview_path)
    if preview_mae is None:
        return None
    return round(max(0.0, 100.0 - (preview_mae / 255.0) * 100.0), 2)


def rgb_luminance(rgb):
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]


def measure_luminance_preservation_score(reference_path, preview_path):
    if not reference_path or not os.path.exists(reference_path) or not os.path.exists(preview_path):
        return None

    with Image.open(reference_path).convert("RGB") as reference_img:
        with Image.open(preview_path).convert("RGB") as preview_img:
            if reference_img.size != preview_img.size:
                preview_img = preview_img.resize(reference_img.size, Image.Resampling.BILINEAR)
            reference_pixels = list(reference_img.getdata())
            preview_pixels = list(preview_img.getdata())
            if not reference_pixels:
                return None
            mean_delta = sum(
                abs(rgb_luminance(reference) - rgb_luminance(preview))
                for reference, preview in zip(reference_pixels, preview_pixels)
            ) / len(reference_pixels)
            return round(max(0.0, 100.0 - (mean_delta / 255.0) * 100.0), 2)


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


def calculate_single_tap_completion_risk(
    largest_region_pct,
    total_regions,
    max_color_pct=None,
    top_3_color_pct=None,
):
    max_color_pct = 0 if max_color_pct is None else max_color_pct
    top_3_color_pct = 0 if top_3_color_pct is None else top_3_color_pct
    if largest_region_pct > 90 or total_regions < 5:
        return "critical"
    if largest_region_pct > 80 or total_regions < 10 or max_color_pct > 85:
        return "high"
    if largest_region_pct > 70 or max_color_pct > 70:
        return "medium"
    if largest_region_pct > 50 or max_color_pct > 60 or (
        top_3_color_pct > 85 and total_regions < 80
    ):
        return "low"
    return "none"


def score_playability(metrics):
    score = 100
    largest_region_pct = metrics.get("largest_region_pct") or 0
    total_regions = metrics.get("total_regions") or 0
    top_3_region_pct = metrics.get("top_3_region_pct") or 0
    max_color_pct = metrics.get("max_color_pct")
    top_3_color_pct = metrics.get("top_3_color_pct")
    playable_region_count = metrics.get("playable_region_count") or 0

    if largest_region_pct > 90:
        score -= 60
    elif largest_region_pct > 80:
        score -= 48
    elif largest_region_pct > 70:
        score -= 35
    elif largest_region_pct > 50:
        score -= 18

    if total_regions < 5:
        score -= 35
    elif total_regions < 10:
        score -= 28
    elif total_regions < 30:
        score -= 10

    if top_3_region_pct > 90:
        score -= 25
    elif top_3_region_pct > 80:
        score -= 15

    if max_color_pct is not None:
        if max_color_pct > 85:
            score -= 30
        elif max_color_pct > 70:
            score -= 22
        elif max_color_pct > 60:
            score -= 12

    if top_3_color_pct is not None:
        if top_3_color_pct > 90:
            score -= 18
        elif top_3_color_pct > 85 and total_regions < 80:
            score -= 12

    if playable_region_count < 5:
        score -= 20
    elif playable_region_count < 10:
        score -= 12

    return max(0, min(100, int(round(score))))


def nearest_rank_area(sorted_areas, percentile):
    if not sorted_areas:
        return 0
    index = int(math.ceil((percentile / 100.0) * len(sorted_areas))) - 1
    index = max(0, min(len(sorted_areas) - 1, index))
    return sorted_areas[index]


def estimate_region_screen_touch_size(region, fit_scale):
    bbox = region.get("bbox") or {}
    if bbox:
        width = max(1, int(bbox.get("right", 0)) - int(bbox.get("left", 0)) + 1)
        height = max(1, int(bbox.get("bottom", 0)) - int(bbox.get("top", 0)) + 1)
        return min(width, height) * fit_scale
    area = max(0, int(region.get("area", 0) or 0))
    if area <= 0:
        return 0.0
    return math.sqrt(area) * fit_scale


def estimate_region_screen_radius(region, fit_scale):
    if region.get("radius") is not None:
        return max(0.0, float(region.get("radius") or 0)) * fit_scale

    bbox = region.get("bbox") or {}
    if bbox:
        width = max(1, int(bbox.get("right", 0)) - int(bbox.get("left", 0)) + 1)
        height = max(1, int(bbox.get("bottom", 0)) - int(bbox.get("top", 0)) + 1)
        return max(6.0, min(width, height) / 2.0) * fit_scale

    return 12.0 * fit_scale


def analyze_region_playability(
    total_pixels,
    regions,
    color_areas=None,
    playable_region_min_area=PLAYABLE_REGION_MIN_AREA,
    background_region_candidate=False,
    canvas_width=None,
    canvas_height=None,
    default_view_width=DEFAULT_VIEW_SIZE[0],
    default_view_height=DEFAULT_VIEW_SIZE[1],
    profile="casual",
    label_min_screen_radius_px=ANDROID_LABEL_MIN_SCREEN_RADIUS_PX,
):
    total_pixels = max(1, int(total_pixels or 0))
    normalized_regions = []
    for region in regions or []:
        area = int(region.get("area", 0) or 0)
        if area <= 0:
            continue
        normalized_regions.append({**region, "area": area})

    region_areas = [region["area"] for region in normalized_regions]
    color_areas = (
        None
        if color_areas is None
        else [int(area) for area in color_areas if int(area) > 0]
    )
    sorted_regions = sorted(region_areas, reverse=True)
    sorted_areas = sorted(region_areas)
    sorted_colors = sorted(color_areas, reverse=True) if color_areas is not None else []
    total_regions = len(region_areas)

    def pct(count):
        return round(count * 100.0 / max(1, total_regions), 1)

    tiny_lt_50 = sum(1 for area in region_areas if area < 50)
    tiny_lt_100 = sum(1 for area in region_areas if area < 100)
    tiny_lt_200 = sum(1 for area in region_areas if area < 200)
    config_hidden_label_count = sum(
        1
        for region in normalized_regions
        if region.get("hide_number") is True or region.get("hide_label") is True
    )

    canvas_width = int(canvas_width or 0)
    canvas_height = int(canvas_height or 0)
    if canvas_width <= 0 or canvas_height <= 0:
        side = math.sqrt(total_pixels)
        canvas_width = max(1, int(round(side)))
        canvas_height = max(1, int(round(total_pixels / canvas_width)))
    fit_scale = min(
        default_view_width / max(1, canvas_width),
        default_view_height / max(1, canvas_height),
    )
    touch_sizes = [
        estimate_region_screen_touch_size(region, fit_scale)
        for region in normalized_regions
    ]
    min_touch_target = round(min(touch_sizes), 2) if touch_sizes else 0.0
    untouchable_count = sum(1 for size in touch_sizes if size < 24.0)
    screen_radii = [
        estimate_region_screen_radius(region, fit_scale)
        for region in normalized_regions
    ]
    estimated_hidden_label_count = sum(
        1
        for region, screen_radius in zip(normalized_regions, screen_radii)
        if region.get("hide_number") is True
        or region.get("hide_label") is True
        or screen_radius < label_min_screen_radius_px
    )

    tiny_by_number = Counter()
    for region in normalized_regions:
        if region["area"] < 100:
            tiny_by_number[region.get("number", "?")] += 1

    largest_region_pct = round((sorted_regions[0] if sorted_regions else 0) * 100.0 / total_pixels, 2)
    top_3_region_pct = round(sum(sorted_regions[:3]) * 100.0 / total_pixels, 2)
    max_color_pct = (
        round(sorted_colors[0] * 100.0 / total_pixels, 2)
        if sorted_colors
        else None
    )
    top_3_color_pct = (
        round(sum(sorted_colors[:3]) * 100.0 / total_pixels, 2)
        if sorted_colors
        else None
    )

    metrics = {
        "largest_region_pct": largest_region_pct,
        "max_region_pct": largest_region_pct,
        "top_3_region_pct": top_3_region_pct,
        "max_color_pct": max_color_pct,
        "top_3_color_pct": top_3_color_pct,
        "tiny_region_count_lt_50": tiny_lt_50,
        "tiny_region_count_lt_100": tiny_lt_100,
        "tiny_region_count_lt_200": tiny_lt_200,
        "tiny_region_pct_lt_50": pct(tiny_lt_50),
        "tiny_region_pct_lt_100": pct(tiny_lt_100),
        "tiny_region_pct_lt_200": pct(tiny_lt_200),
        "config_hidden_label_count": config_hidden_label_count,
        "config_hidden_label_pct": pct(config_hidden_label_count),
        "estimated_hidden_label_count": estimated_hidden_label_count,
        "estimated_hidden_label_pct": pct(estimated_hidden_label_count),
        "hidden_label_count": estimated_hidden_label_count,
        "hidden_label_pct": pct(estimated_hidden_label_count),
        "label_min_screen_radius_px": label_min_screen_radius_px,
        "median_region_area": float(statistics.median(sorted_areas)) if sorted_areas else 0,
        "p10_region_area": nearest_rank_area(sorted_areas, 10),
        "p25_region_area": nearest_rank_area(sorted_areas, 25),
        "region_density_by_canvas_size": round(total_regions / (total_pixels / 1000000.0), 2),
        "tiny_region_by_number_lt_100": dict(sorted(tiny_by_number.items(), key=lambda item: str(item[0]))),
        "untouchable_region_count": untouchable_count,
        "min_touch_target_at_default_zoom": min_touch_target,
        "playable_region_count": sum(
            1 for area in region_areas if area >= playable_region_min_area
        ),
        "giant_region_count": sum(
            1 for area in region_areas if area * 100.0 / total_pixels > 50
        ),
        "background_region_candidate": bool(background_region_candidate),
        "playability_profile": normalize_profile(profile),
    }
    metrics["single_tap_completion_risk"] = calculate_single_tap_completion_risk(
        largest_region_pct=largest_region_pct,
        total_regions=total_regions,
        max_color_pct=max_color_pct,
        top_3_color_pct=top_3_color_pct,
    )
    metrics["playable_score"] = score_playability(
        {
            **metrics,
            "total_regions": total_regions,
        }
    )
    return metrics


def calculate_gameplay_metrics(
    total_pixels,
    region_areas,
    color_areas=None,
    playable_region_min_area=PLAYABLE_REGION_MIN_AREA,
    background_region_candidate=False,
    profile="casual",
):
    return analyze_region_playability(
        total_pixels=total_pixels,
        regions=[{"area": area} for area in region_areas],
        color_areas=color_areas,
        playable_region_min_area=playable_region_min_area,
        background_region_candidate=background_region_candidate,
        profile=profile,
    )


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
    region_numbers_by_id = {region.get("id"): region.get("number") for region in regions}
    region_by_mask_color = {}
    for region in regions:
        rgb = parse_hex_color(region.get("mask_color"))
        if rgb is not None:
            region_by_mask_color[rgb] = region
    profile = (
        config.get("category_profile")
        or (config.get("generation_params") or {}).get("category_profile")
        or (config.get("generation") or {}).get("category_profile")
        or config.get("category")
    )

    if not os.path.exists(mask_path):
        color_area_totals = Counter()
        for region in regions:
            color_area_totals[region.get("number")] += int(region.get("area", 0) or 0)
        return {
            "total_regions": len(regions),
            "unique_numbers": len({region.get("number") for region in regions}),
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
            **analyze_region_playability(
                total_pixels=total_pixels,
                regions=regions,
                color_areas=list(color_area_totals.values()),
                canvas_width=int(config.get("width", 0) or 0),
                canvas_height=int(config.get("height", 0) or 0),
                profile=profile,
            ),
        }

    with Image.open(mask_path).convert("RGB") as mask_img:
        if not config.get("width") or not config.get("height"):
            total_pixels = max(1, mask_img.width * mask_img.height)
        mask_width = mask_img.width
        mask_height = mask_img.height
        mask_pixels = Counter()
        color_bboxes = {}
        mask_reader = mask_img.load()
        for y in range(mask_img.height):
            for x in range(mask_img.width):
                color = mask_reader[x, y]
                mask_pixels[color] += 1
                if color in BACKGROUND_MASK_COLORS:
                    continue
                if color not in color_bboxes:
                    color_bboxes[color] = [x, y, x, y]
                else:
                    bbox = color_bboxes[color]
                    bbox[0] = min(bbox[0], x)
                    bbox[1] = min(bbox[1], y)
                    bbox[2] = max(bbox[2], x)
                    bbox[3] = max(bbox[3], y)

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
    color_area_totals = Counter()
    region_summaries = []
    for entry in config_entries:
        actual_area = mask_region_counts.get(
            entry["mask_color"],
            entry["area"],
        )
        color_area_totals[region_numbers_by_id.get(entry["id"])] += actual_area
        region = region_by_mask_color.get(entry["mask_color"], {})
        region_summaries.append(
            {
                "area": actual_area,
                "number": region.get("number"),
                "hide_number": region.get("hide_number"),
                "hide_label": region.get("hide_label"),
                "radius": region.get("radius"),
                "bbox": region.get("bbox"),
            }
        )
    configured_colors = {entry["mask_color"] for entry in config_entries}
    for color, area in mask_region_counts.items():
        if color in configured_colors:
            continue
        region_summaries.append({"area": area, "number": "unknown"})

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
    largest_color = max(mask_region_counts, key=mask_region_counts.get, default=None)
    largest_bbox = color_bboxes.get(largest_color) if largest_color else None
    background_region_candidate = False
    if largest_bbox and largest_area * 100.0 / total_pixels > 50:
        background_region_candidate = (
            largest_bbox[0] == 0
            or largest_bbox[1] == 0
            or largest_bbox[2] == mask_width - 1
            or largest_bbox[3] == mask_height - 1
        )
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
        **analyze_region_playability(
            total_pixels=total_pixels,
            regions=region_summaries if region_summaries else [{"area": area} for area in area_values],
            color_areas=list(color_area_totals.values()) if color_area_totals else None,
            background_region_candidate=background_region_candidate,
            canvas_width=mask_width,
            canvas_height=mask_height,
            profile=profile,
        ),
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
    total_regions = metrics.get("total_regions") or 0
    max_color_pct = metrics.get("max_color_pct")
    top_3_color_pct = metrics.get("top_3_color_pct")
    tap_risk = metrics.get("single_tap_completion_risk") or "none"
    playable_score = metrics.get("playable_score")

    if largest_region_pct > 80:
        fail_reasons.append(
            make_issue(
                "GIANT_REGION",
                "Vùng lớn nhất chiếm hơn 80% ảnh; một lần tap có thể tô gần hết ảnh.",
                largest_region_pct,
                80,
            )
        )
        score -= 48
    elif largest_region_pct > 70:
        issue = make_issue(
            "GIANT_REGION",
            "Vùng lớn nhất chiếm hơn 70% ảnh; line có thể bị hở nên nhiều mảng bị dính vào nhau.",
            largest_region_pct,
            70,
        )
        if total_regions < 80 or (max_color_pct is not None and max_color_pct > 70):
            fail_reasons.append(issue)
        else:
            warnings.append({**issue, "code": "GIANT_REGION_SEVERE_WARNING"})
        score -= 35
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

    if total_regions < 10:
        fail_reasons.append(
            make_issue(
                "LOW_REGION_COUNT",
                "Số vùng tô quá thấp cho một level Color by Number; nhiều vùng có thể đang bị dính.",
                total_regions,
                10,
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

    if max_color_pct is not None:
        if max_color_pct > 85:
            fail_reasons.append(
                make_issue(
                    "DOMINANT_COLOR_AREA",
                    "Một number/color chiếm hơn 85% ảnh; user có thể hoàn thành quá nhiều bằng một màu.",
                    max_color_pct,
                    85,
                )
            )
            score -= 30
        elif max_color_pct > 70:
            warnings.append(
                make_issue(
                    "DOMINANT_COLOR_AREA_WARNING",
                    "Một number/color chiếm hơn 70% ảnh; cần kiểm tra cân bằng gameplay.",
                    max_color_pct,
                    70,
                )
            )
            score -= 16

    if top_3_color_pct is not None and top_3_color_pct > 85 and total_regions < 80:
        warnings.append(
            make_issue(
                "TOP_COLORS_DOMINATE_WARNING",
                "Ba màu lớn nhất chiếm hơn 85% ảnh khi số vùng chưa cao; gameplay có thể quá ngắn.",
                top_3_color_pct,
                85,
            )
        )
        score -= 10

    if tap_risk in {"critical", "high"}:
        issue = make_issue(
            "SINGLE_TAP_COMPLETION_RISK",
            "Phân bố vùng tạo rủi ro một tap hoặc một màu hoàn thành quá nhiều ảnh.",
            tap_risk,
            "medium",
        )
        if tap_risk == "critical":
            fail_reasons.append(issue)
            score -= 22
        else:
            warnings.append(issue)
            score -= 12

    if playable_score is not None:
        if playable_score < 35:
            fail_reasons.append(
                make_issue(
                    "PLAYABLE_SCORE_TOO_LOW",
                    "Điểm gameplay dưới 35/100; level không đủ cân bằng để đưa vào demo.",
                    playable_score,
                    35,
                )
            )
            score -= 18
        elif playable_score < 70:
            warnings.append(
                make_issue(
                    "PLAYABLE_SCORE_LOW",
                    "Điểm gameplay dưới 70/100; nên regenerate auto hoặc kiểm tra line.",
                    playable_score,
                    70,
                )
            )
            score -= 8

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

    profile = normalize_profile(metrics.get("playability_profile"))
    tiny_pct_100 = metrics.get("tiny_region_pct_lt_100") or 0
    tiny_pct_200 = metrics.get("tiny_region_pct_lt_200") or 0
    hidden_label_pct = (
        metrics.get("config_hidden_label_pct")
        if metrics.get("config_hidden_label_pct") is not None
        else metrics.get("hidden_label_pct")
    ) or 0
    median_area = metrics.get("median_region_area") or 0
    tiny_profile_is_strict = profile not in {"hard", "mandala"}
    if tiny_profile_is_strict and (
        tiny_pct_100 > 35
        or tiny_pct_200 > 50
        or hidden_label_pct > 30
    ):
        warnings.append(
            make_issue(
                "TINY_REGION_DENSITY_WARNING",
                "Tỷ lệ vùng nhỏ/ẩn số cao so với profile casual; level có thể khó tap hoặc khó đọc.",
                {
                    "tiny_lt_100_pct": tiny_pct_100,
                    "tiny_lt_200_pct": tiny_pct_200,
                    "hidden_label_pct": hidden_label_pct,
                    "median_region_area": median_area,
                },
                {
                    "tiny_lt_100_pct": 35,
                    "tiny_lt_200_pct": 50,
                    "hidden_label_pct": 30,
                },
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

    gameplay_auto_codes = {
        "GIANT_REGION_WARNING",
        "GIANT_REGION_SEVERE_WARNING",
        "DOMINANT_COLOR_AREA_WARNING",
        "TOP_COLORS_DOMINATE_WARNING",
        "PLAYABLE_SCORE_LOW",
        "TINY_REGION_DENSITY_WARNING",
    }
    gameplay_fix_codes = {
        "GIANT_REGION",
        "LOW_REGION_COUNT",
        "SINGLE_TAP_COMPLETION_RISK",
        "PLAYABLE_SCORE_TOO_LOW",
        "DOMINANT_COLOR_AREA",
    }

    if gameplay_auto_codes & all_codes:
        reasons.append("REGENERATE_AUTO")
        design_focus.append("line")
    if gameplay_fix_codes & all_codes and "DESIGN_FIX_LINE" not in reasons:
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
        action = "REGENERATE_AUTO" if gameplay_auto_codes & all_codes else "REVIEW_VISUAL"
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
    metrics["color_mae"] = metrics["preview_mae"]
    metrics["preview_similarity_score"] = measure_preview_similarity_score(reference_path, preview_path)
    metrics["shading_preservation_score"] = measure_luminance_preservation_score(
        reference_path,
        preview_path,
    )
    detail_path = resolve_asset_path(level_dir, config, "detail", "detail.png")
    metrics["has_detail"] = os.path.exists(detail_path)

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
