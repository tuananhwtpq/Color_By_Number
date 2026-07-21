import argparse
import json
import math
import os
from collections import Counter, deque

from PIL import Image, ImageFilter
from PIL import ImageDraw

try:
    from asset_quality import evaluate_level_dir, merge_quality_report, score_quality
except ImportError:
    from tools.asset_quality import evaluate_level_dir, merge_quality_report, score_quality


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ASSETS_ROOT = os.path.abspath(
    os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets")
)
DEFAULT_PROFILE_TARGETS = {
    "mandala": 80,
    "standard": 64,
    "easy": 48,
}
TINY_MERGE_POLICY_DEFAULTS = {
    "relaxed": {
        "attach_distance_multiplier": 2.0,
        "color_threshold_multiplier": 2.2,
        "allow_fallback": True,
    },
    "strict": {
        "attach_distance_multiplier": 1.35,
        "color_threshold_multiplier": 1.3,
        "allow_fallback": False,
    },
    "mandala": {
        "attach_distance_multiplier": 2.6,
        "color_threshold_multiplier": 2.6,
        "allow_fallback": True,
    },
}
QUANTIZE_METHODS = {
    "mediancut": Image.Quantize.MEDIANCUT,
    "maxcoverage": Image.Quantize.MAXCOVERAGE,
    "fastoctree": Image.Quantize.FASTOCTREE,
}


def rgb_to_hex(rgb):
    return "#{:02x}{:02x}{:02x}".format(rgb[0], rgb[1], rgb[2])


def color_distance(c1, c2):
    dr = c1[0] - c2[0]
    dg = c1[1] - c2[1]
    db = c1[2] - c2[2]
    # Weighted RGB distance. Green contributes more strongly to perceived difference.
    return math.sqrt(2.0 * dr * dr + 4.0 * dg * dg + 3.0 * db * db)


def quantize_rgb(rgb, step=16):
    return tuple(min(255, int(round(channel / step) * step)) for channel in rgb)


def infer_category_profile(category_name, requested_profile=None):
    if requested_profile:
        return requested_profile
    if category_name and "mandala" in category_name.lower():
        return "mandala"
    return "standard"


def resolve_target_unique_colors(category_name, requested_profile=None, explicit_target=None):
    profile = infer_category_profile(category_name, requested_profile)
    if explicit_target is not None:
        return profile, max(2, explicit_target)
    return profile, DEFAULT_PROFILE_TARGETS[profile]


def get_quantize_method(method_name):
    if method_name not in QUANTIZE_METHODS:
        raise ValueError(
            f"Quantize method '{method_name}' không hợp lệ. "
            f"Hãy dùng một trong: {', '.join(sorted(QUANTIZE_METHODS))}."
        )
    return QUANTIZE_METHODS[method_name]


def unique_color_count(image):
    max_colors = image.width * image.height
    colors = image.getcolors(maxcolors=max_colors)
    if colors is None:
        return max_colors
    return len(colors)


def build_quantized_reference_image(ref_img, target_unique_colors, method_name):
    palette_budget = max(
        target_unique_colors + 16,
        int(round(target_unique_colors * 2.5)),
    )
    palette_budget = max(8, min(256, palette_budget))
    quantized = ref_img.quantize(
        colors=palette_budget,
        method=get_quantize_method(method_name),
        dither=Image.Dither.NONE,
    )
    return quantized.convert("RGB"), palette_budget, unique_color_count(quantized)


def find_next_available_id(output_root, start_id=100001):
    used_ids = set()
    if os.path.exists(output_root):
        for category_name in os.listdir(output_root):
            category_path = os.path.join(output_root, category_name)
            if not os.path.isdir(category_path):
                continue
            for item in os.listdir(category_path):
                if item.isdigit():
                    used_ids.add(int(item))

    next_id = start_id
    while next_id in used_ids:
        next_id += 1
    return str(next_id)


def close_small_line_gaps(binary_img, close_radius):
    if close_radius <= 0:
        return binary_img
    size = close_radius * 2 + 1
    return binary_img.filter(ImageFilter.MinFilter(size=size)).filter(
        ImageFilter.MaxFilter(size=size)
    )


def load_binary_fill_map(line_art_path, brightness_threshold, line_close_radius):
    line_img = Image.open(line_art_path).convert("RGB")
    gray = line_img.convert("L")
    binary = gray.point(lambda value: 255 if value > brightness_threshold else 0, mode="L")
    binary = close_small_line_gaps(binary, line_close_radius)
    return line_img, binary


def build_preprocessing_candidates(
    preprocess_profile,
    brightness_threshold,
    line_close_radius,
):
    base_candidate = {
        "profile": "standard",
        "brightness_threshold": brightness_threshold,
        "line_close_radius": line_close_radius,
    }
    profiles = {
        "standard": [base_candidate],
        "thin-line": [
            {"profile": "thin-line", "brightness_threshold": 130, "line_close_radius": 1},
            {"profile": "thin-line", "brightness_threshold": 150, "line_close_radius": 1},
            {"profile": "thin-line", "brightness_threshold": 150, "line_close_radius": 2},
        ],
        "manga": [
            {"profile": "manga", "brightness_threshold": 130, "line_close_radius": 1},
            {"profile": "manga", "brightness_threshold": 150, "line_close_radius": 1},
            {"profile": "manga", "brightness_threshold": 170, "line_close_radius": 2},
        ],
        "mandala": [
            {"profile": "mandala", "brightness_threshold": 100, "line_close_radius": 0},
            {"profile": "mandala", "brightness_threshold": 110, "line_close_radius": 1},
            {"profile": "mandala", "brightness_threshold": 130, "line_close_radius": 1},
        ],
        "poster": [
            {"profile": "poster", "brightness_threshold": 90, "line_close_radius": 1},
            {"profile": "poster", "brightness_threshold": 110, "line_close_radius": 1},
            {"profile": "poster", "brightness_threshold": 130, "line_close_radius": 2},
        ],
    }
    if preprocess_profile == "auto":
        candidates = [base_candidate]
        for name in ["thin-line", "manga", "mandala", "poster"]:
            candidates.extend(profiles[name])
        return candidates
    return profiles.get(preprocess_profile, [base_candidate])


def score_preprocessing_candidate(binary_img):
    regions = extract_regions(binary_img)
    total_pixels = max(1, binary_img.width * binary_img.height)
    region_areas = [len(region) for region in regions if len(region) > 1]
    total_regions = len(region_areas)
    largest_pct = max(region_areas, default=0) * 100.0 / total_pixels
    tiny_regions = sum(1 for area in region_areas if area < 50)

    metrics = {
        "total_regions": total_regions,
        "unique_numbers": 0,
        "largest_region_pct": round(largest_pct, 2),
        "tiny_region_count_lt_50": tiny_regions,
        "mask_config_mismatch_count": 0,
    }
    quality = score_quality(metrics)
    return {
        "score": quality["quality_score"],
        "evaluation_mode": "pre_generation_proxy",
        "candidate_proxy_score": quality["quality_score"],
        "candidate_proxy_grade": quality["quality_grade"],
        "quality_score": quality["quality_score"],
        "quality_grade": quality["quality_grade"],
        "fail_reasons": quality["fail_reasons"],
        "warnings": quality["warnings"],
        "total_regions": total_regions,
        "largest_region_pct": round(largest_pct, 2),
        "tiny_region_count_lt_50": tiny_regions,
        "preview_mae": None,
        "preview_mae_reason": "not_available_before_full_generation",
    }


def select_binary_fill_map(
    line_art_path,
    brightness_threshold,
    line_close_radius,
    preprocess_profile,
):
    line_img = Image.open(line_art_path).convert("RGB")
    gray = line_img.convert("L")
    candidates = []
    for candidate in build_preprocessing_candidates(
        preprocess_profile=preprocess_profile,
        brightness_threshold=brightness_threshold,
        line_close_radius=line_close_radius,
    ):
        binary = gray.point(
            lambda value, threshold=candidate["brightness_threshold"]: (
                255 if value > threshold else 0
            ),
            mode="L",
        )
        binary = close_small_line_gaps(binary, candidate["line_close_radius"])
        candidate_report = {
            **candidate,
            **score_preprocessing_candidate(binary),
            "binary": binary,
        }
        candidates.append(candidate_report)

    selected = max(
        candidates,
        key=lambda item: (
            item["score"],
            -abs(item["largest_region_pct"] - 35.0),
            item["total_regions"],
        ),
    )
    selected_report = {key: value for key, value in selected.items() if key != "binary"}
    selected_report["candidate_count"] = len(candidates)
    selected_report["candidate_top"] = [
        {key: value for key, value in candidate.items() if key != "binary"}
        for candidate in sorted(
            candidates,
            key=lambda item: (
                item["quality_score"],
                -abs(item["largest_region_pct"] - 35.0),
                item["total_regions"],
            ),
            reverse=True,
        )[:3]
    ]
    return line_img, selected["binary"], selected_report


def make_line_output_image(binary_img):
    return binary_img.convert("RGB")


def extract_regions(binary_img):
    width, height = binary_img.size
    pixels = binary_img.load()
    visited = bytearray(width * height)
    regions = []

    for y in range(height):
        for x in range(width):
            idx = y * width + x
            if visited[idx]:
                continue
            visited[idx] = 1
            if pixels[x, y] == 0:
                continue

            queue = deque([(x, y)])
            region = []

            while queue:
                cx, cy = queue.popleft()
                region.append((cx, cy))
                for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                    nx = cx + dx
                    ny = cy + dy
                    if not (0 <= nx < width and 0 <= ny < height):
                        continue
                    n_idx = ny * width + nx
                    if visited[n_idx]:
                        continue
                    visited[n_idx] = 1
                    if pixels[nx, ny] != 0:
                        queue.append((nx, ny))

            if region:
                regions.append(region)

    return regions


def get_region_bbox(region):
    xs = [point[0] for point in region]
    ys = [point[1] for point in region]
    return {
        "left": min(xs),
        "top": min(ys),
        "right": max(xs),
        "bottom": max(ys),
    }


def bbox_gap(box_a, box_b):
    if box_a["right"] < box_b["left"]:
        gap_x = box_b["left"] - box_a["right"] - 1
    elif box_b["right"] < box_a["left"]:
        gap_x = box_a["left"] - box_b["right"] - 1
    else:
        gap_x = 0

    if box_a["bottom"] < box_b["top"]:
        gap_y = box_b["top"] - box_a["bottom"] - 1
    elif box_b["bottom"] < box_a["top"]:
        gap_y = box_a["top"] - box_b["bottom"] - 1
    else:
        gap_y = 0

    return max(0, gap_x), max(0, gap_y)


def get_region_centroid(region):
    area = len(region)
    sum_x = sum(point[0] for point in region)
    sum_y = sum(point[1] for point in region)
    return {"x": sum_x / area, "y": sum_y / area}


def merge_bboxes(boxes):
    return {
        "left": min(box["left"] for box in boxes),
        "top": min(box["top"] for box in boxes),
        "right": max(box["right"] for box in boxes),
        "bottom": max(box["bottom"] for box in boxes),
    }


def bbox_width(box):
    return box["right"] - box["left"] + 1


def bbox_height(box):
    return box["bottom"] - box["top"] + 1


def bbox_min_side(box):
    return min(bbox_width(box), bbox_height(box))


def get_dominant_color(ref_pixels, region, quantize_step=16):
    if quantize_step and quantize_step > 0:
        colors = [quantize_rgb(ref_pixels[x, y][:3], quantize_step) for x, y in region]
    else:
        colors = [tuple(ref_pixels[x, y][:3]) for x, y in region]
    return Counter(colors).most_common(1)[0][0]


def median_channel(values):
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 1:
        return ordered[middle]
    return int(round((ordered[middle - 1] + ordered[middle]) / 2.0))


def trimmed_mean_channel(values, trim_fraction=0.1):
    ordered = sorted(values)
    trim_count = int(len(ordered) * trim_fraction)
    if trim_count > 0 and trim_count * 2 < len(ordered):
        ordered = ordered[trim_count:-trim_count]
    return int(round(sum(ordered) / max(1, len(ordered))))


def get_representative_color(ref_pixels, region, method="median", quantize_step=0):
    colors = [tuple(ref_pixels[x, y][:3]) for x, y in region]
    if not colors:
        return (255, 255, 255)

    if method == "dominant":
        return get_dominant_color(ref_pixels, region, quantize_step=quantize_step)

    channels = list(zip(*colors))
    if method == "trimmed-mean":
        rgb = tuple(trimmed_mean_channel(channel) for channel in channels)
    elif method == "median":
        rgb = tuple(median_channel(channel) for channel in channels)
    else:
        raise ValueError(
            f"Region color method '{method}' không hợp lệ. "
            "Hãy dùng dominant, median hoặc trimmed-mean."
        )

    if quantize_step and quantize_step > 0:
        return quantize_rgb(rgb, quantize_step)
    return rgb


def sort_palette_colors(colors):
    return sorted(
        colors,
        key=lambda color: (
            0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2],
            color[0],
            color[1],
            color[2],
        ),
    )


def make_cluster_from_info(info):
    return {
        "center": info["target_color"],
        "weight": info["area"],
        "members": {info["target_color"]},
        "region_count": 1,
        "small_weight": info["area"] if info["is_small_region"] else 0,
        "tiny_weight": info["area"] if info["is_tiny_display_region"] else 0,
    }


def merge_cluster_pair(cluster_a, cluster_b):
    total_weight = cluster_a["weight"] + cluster_b["weight"]
    merged_center = tuple(
        int(
            round(
                (
                    cluster_a["center"][index] * cluster_a["weight"]
                    + cluster_b["center"][index] * cluster_b["weight"]
                )
                / max(1, total_weight)
            )
        )
        for index in range(3)
    )
    return {
        "center": merged_center,
        "weight": total_weight,
        "members": cluster_a["members"] | cluster_b["members"],
        "region_count": cluster_a["region_count"] + cluster_b["region_count"],
        "small_weight": cluster_a["small_weight"] + cluster_b["small_weight"],
        "tiny_weight": cluster_a["tiny_weight"] + cluster_b["tiny_weight"],
    }


def cluster_merge_score(cluster_a, cluster_b):
    smaller_weight = min(cluster_a["weight"], cluster_b["weight"])
    larger_weight = max(cluster_a["weight"], cluster_b["weight"])
    weight_ratio = smaller_weight / max(1.0, larger_weight)
    tiny_bonus = min(cluster_a["tiny_weight"], cluster_b["tiny_weight"]) / max(
        1.0, smaller_weight
    )
    return color_distance(cluster_a["center"], cluster_b["center"]) + weight_ratio * 8.0 + tiny_bonus * 3.0


def merge_clusters_under_threshold(clusters, threshold):
    merged_any = False
    changed = True
    while changed and len(clusters) > 1:
        changed = False
        best_pair = None
        best_score = None
        for left_idx in range(len(clusters)):
            for right_idx in range(left_idx + 1, len(clusters)):
                score = cluster_merge_score(clusters[left_idx], clusters[right_idx])
                if score > threshold:
                    continue
                if best_score is None or score < best_score:
                    best_score = score
                    best_pair = (left_idx, right_idx)
        if best_pair is None:
            break
        left_idx, right_idx = best_pair
        merged_cluster = merge_cluster_pair(clusters[left_idx], clusters[right_idx])
        for idx in sorted(best_pair, reverse=True):
            clusters.pop(idx)
        clusters.append(merged_cluster)
        changed = True
        merged_any = True
    return clusters, merged_any


def force_reduce_clusters(clusters, target_unique_colors):
    passes = 0
    while len(clusters) > target_unique_colors and len(clusters) > 1:
        best_pair = None
        best_score = None
        for left_idx in range(len(clusters)):
            for right_idx in range(left_idx + 1, len(clusters)):
                score = cluster_merge_score(clusters[left_idx], clusters[right_idx])
                if best_score is None or score < best_score:
                    best_score = score
                    best_pair = (left_idx, right_idx)
        if best_pair is None:
            break
        left_idx, right_idx = best_pair
        merged_cluster = merge_cluster_pair(clusters[left_idx], clusters[right_idx])
        for idx in sorted(best_pair, reverse=True):
            clusters.pop(idx)
        clusters.append(merged_cluster)
        passes += 1
    return clusters, passes


def build_palette_with_adaptive_merge(
    region_infos,
    merge_threshold,
    target_unique_colors,
    category_profile,
    adaptive_enabled,
):
    color_clusters = {}
    for info in region_infos:
        color_key = tuple(info["target_color"])
        if color_key not in color_clusters:
            color_clusters[color_key] = make_cluster_from_info(info)
            continue

        cluster = color_clusters[color_key]
        cluster["weight"] += info["area"]
        cluster["region_count"] += 1
        if info["is_small_region"]:
            cluster["small_weight"] += info["area"]
        if info["is_tiny_display_region"]:
            cluster["tiny_weight"] += info["area"]

    clusters = list(color_clusters.values())
    initial_unique_numbers = len(clusters)
    if not adaptive_enabled or initial_unique_numbers <= target_unique_colors:
        palette_colors = sort_palette_colors([tuple(cluster["center"]) for cluster in clusters])
        return {
            "palette_colors": palette_colors,
            "color_mapping": {color: color for color in color_clusters},
            "initial_unique_numbers": initial_unique_numbers,
            "final_unique_numbers": len(palette_colors),
            "final_merge_threshold": merge_threshold,
            "palette_reduction_passes": 0,
            "palette_reduction_reason": "within_limit",
        }

    profile_multiplier = {
        "easy": 1.15,
        "standard": 1.0,
        "mandala": 0.92,
    }[category_profile]
    threshold_steps = [
        merge_threshold,
        merge_threshold * 1.35,
        merge_threshold * 1.75,
        merge_threshold * 2.1,
        merge_threshold * 2.6,
        merge_threshold * 3.2,
        merge_threshold * 4.0,
        merge_threshold * 5.0,
    ]
    threshold_steps = [max(4.0, step * profile_multiplier) for step in threshold_steps]

    applied_threshold = merge_threshold
    adaptive_passes = 0
    working_clusters = clusters
    for threshold in threshold_steps:
        working_clusters, merged_any = merge_clusters_under_threshold(working_clusters, threshold)
        applied_threshold = threshold
        if merged_any:
            adaptive_passes += 1
        if len(working_clusters) <= target_unique_colors:
            break

    forced_passes = 0
    reduction_reason = "within_limit"
    if len(working_clusters) > target_unique_colors:
        working_clusters, forced_passes = force_reduce_clusters(
            working_clusters, target_unique_colors
        )
        reduction_reason = "clamped_to_target"

    palette_colors = sort_palette_colors([tuple(cluster["center"]) for cluster in working_clusters])
    color_mapping = {}
    for cluster in working_clusters:
        for member_color in cluster["members"]:
            color_mapping[member_color] = tuple(cluster["center"])

    return {
        "palette_colors": palette_colors,
        "color_mapping": color_mapping,
        "initial_unique_numbers": initial_unique_numbers,
        "final_unique_numbers": len(palette_colors),
        "final_merge_threshold": applied_threshold,
        "palette_reduction_passes": adaptive_passes + forced_passes,
        "palette_reduction_reason": reduction_reason,
    }


def choose_palette_color(target_color, palette_colors):
    return min(palette_colors, key=lambda color: color_distance(color, target_color))


def centroid_fallback(region, centroid):
    best_point = region[0]
    best_distance = None
    for point in region:
        distance = (point[0] - centroid["x"]) ** 2 + (point[1] - centroid["y"]) ** 2
        if best_distance is None or distance < best_distance:
            best_distance = distance
            best_point = point
    return {"x": float(best_point[0]), "y": float(best_point[1])}


def find_label_anchor(region, bbox, centroid):
    region_set = set(region)
    width = bbox["right"] - bbox["left"] + 1
    height = bbox["bottom"] - bbox["top"] + 1
    sample_stride = max(1, int(math.sqrt(max(1, len(region) // 250))))

    best_point = None
    best_score = -1
    directions = [(-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, -1), (-1, 1), (1, 1)]

    for index, point in enumerate(region):
        if index % sample_stride != 0:
            continue
        x, y = point
        min_distance = min(x - bbox["left"], bbox["right"] - x, y - bbox["top"], bbox["bottom"] - y)
        if min_distance < 1:
            continue

        distance_score = 0
        for dx, dy in directions:
            step = 0
            while True:
                nx = x + dx * (step + 1)
                ny = y + dy * (step + 1)
                if (nx, ny) not in region_set:
                    break
                step += 1
            distance_score += step

        center_penalty = abs(x - centroid["x"]) + abs(y - centroid["y"])
        score = distance_score * 10 - center_penalty
        if score > best_score:
            best_score = score
            best_point = point

    if best_point is None:
        fallback = centroid_fallback(region, centroid)
        return {"x": fallback["x"], "y": fallback["y"]}

    return {"x": float(best_point[0]), "y": float(best_point[1])}


def estimate_difficulty(total_regions, unique_numbers, small_regions_count):
    score = total_regions * 2 + unique_numbers * 4 + small_regions_count * 6
    return max(1, min(10, int(round(score / 35.0))))


def make_flat_colored_image(width, height, mask_img, mask_to_target_rgb):
    mask_pixels = mask_img.load()
    preview = Image.new("RGB", (width, height), (255, 255, 255))
    preview_pixels = preview.load()

    for y in range(height):
        for x in range(width):
            target = mask_to_target_rgb.get(mask_pixels[x, y])
            if target is not None:
                preview_pixels[x, y] = target

    return preview


def apply_line_overlay(preview, line_img):
    line_gray = line_img.convert("L")
    line_pixels = line_gray.load()
    preview = preview.copy()
    preview_pixels = preview.load()
    width, height = preview.size
    for y in range(height):
        for x in range(width):
            line_value = line_pixels[x, y]
            if line_value < 245:
                current = preview_pixels[x, y]
                factor = line_value / 255.0
                preview_pixels[x, y] = tuple(int(channel * factor) for channel in current)

    return preview


def make_detail_overlay_image(
    reference_img,
    flat_preview_img,
    line_img,
    mask_img,
    mask_to_target_rgb,
    detail_alpha,
):
    width, height = reference_img.size
    reference_pixels = reference_img.load()
    flat_pixels = flat_preview_img.load()
    mask_pixels = mask_img.load()
    line_pixels = line_img.convert("L").load()
    detail = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    detail_pixels = detail.load()
    max_alpha = int(round(max(0.0, min(1.0, detail_alpha)) * 255))

    for y in range(height):
        for x in range(width):
            if mask_pixels[x, y] not in mask_to_target_rgb:
                continue
            if line_pixels[x, y] < 245:
                continue
            reference = reference_pixels[x, y][:3]
            flat = flat_pixels[x, y][:3]
            diff = color_distance(reference, flat)
            if diff < 2.0:
                continue
            alpha = int(round(max_alpha * min(1.0, diff / 96.0)))
            if alpha <= 0:
                continue
            detail_pixels[x, y] = (reference[0], reference[1], reference[2], alpha)

    return detail


def composite_detail_over_flat(flat_preview_img, detail_img):
    if detail_img is None:
        return flat_preview_img.copy()
    return Image.alpha_composite(flat_preview_img.convert("RGBA"), detail_img).convert("RGB")


def make_preview_colored_image(width, height, mask_img, line_img, mask_to_target_rgb, detail_img=None):
    flat_preview = make_flat_colored_image(width, height, mask_img, mask_to_target_rgb)
    detailed_preview = composite_detail_over_flat(flat_preview, detail_img)
    return apply_line_overlay(detailed_preview, line_img)


def deterministic_debug_color(region_id):
    return (
        (region_id * 73) % 206 + 30,
        (region_id * 151) % 206 + 30,
        (region_id * 199) % 206 + 30,
    )


def make_debug_regions_image(width, height, region_configs, region_pixel_sets):
    debug = Image.new("RGB", (width, height), (250, 250, 250))
    draw = ImageDraw.Draw(debug)
    pixels = debug.load()

    for region_config, region in zip(region_configs, region_pixel_sets):
        color = deterministic_debug_color(region_config["id"])
        for x, y in region:
            pixels[x, y] = color

    for region_config in region_configs:
        anchor = region_config["label_anchor"]
        x = int(round(anchor["x"]))
        y = int(round(anchor["y"]))
        draw.text((x, y), str(region_config["number"]), fill=(0, 0, 0), anchor="mm")

    return debug


def build_quality_report(width, height, region_configs, stats, generation_params):
    warnings = []
    total_area = width * height
    giant_regions = [
        region
        for region in region_configs
        if region["area"] / max(1, total_area) > 0.6
    ]
    tiny_regions = [
        region
        for region in region_configs
        if region["quality"]["is_tiny"]
    ]
    edge_label_regions = []
    for region in region_configs:
        anchor = region["label_anchor"]
        if anchor["x"] < 4 or anchor["y"] < 4 or anchor["x"] > width - 5 or anchor["y"] > height - 5:
            edge_label_regions.append(region)

    if giant_regions:
        warnings.append(
            {
                "code": "GIANT_REGION",
                "message": "Một hoặc nhiều vùng chiếm hơn 60% canvas; line art có thể bị hở hoặc nền đang bị coi là vùng tô.",
                "region_ids": [region["id"] for region in giant_regions],
            }
        )
    if len(tiny_regions) > max(8, len(region_configs) * 0.25):
        warnings.append(
            {
                "code": "MANY_TINY_REGIONS",
                "message": "Số vùng quá nhỏ cao; level có thể khó chạm hoặc khó đọc số.",
                "region_ids": [region["id"] for region in tiny_regions[:50]],
            }
        )
    if edge_label_regions:
        warnings.append(
            {
                "code": "EDGE_LABEL_ANCHOR",
                "message": "Một số label anchor nằm sát mép ảnh.",
                "region_ids": [region["id"] for region in edge_label_regions[:50]],
            }
        )

    return {
        "schema_version": 1,
        "stats": stats,
        "generation_params": generation_params,
        "warnings": warnings,
    }


def is_tiny_display_region(info, tiny_area_threshold, tiny_side_threshold):
    return info["area"] < tiny_area_threshold or bbox_min_side(info["bbox"]) < tiny_side_threshold


def is_micro_region(info, tiny_merge_min_area, tiny_merge_min_side):
    return info["area"] < tiny_merge_min_area or bbox_min_side(info["bbox"]) < tiny_merge_min_side


def update_region_classification(
    info,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
):
    min_side = bbox_min_side(info["bbox"])
    info["min_side"] = min_side
    info["is_small_region"] = info["area"] < min_region_area
    info["is_tiny_display_region"] = (
        info["area"] < tiny_area_threshold or min_side < tiny_side_threshold
    )
    info["is_micro_region"] = info["area"] < tiny_merge_min_area or min_side < tiny_merge_min_side
    return info


def classify_region_infos(
    region_infos,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
):
    for info in region_infos:
        update_region_classification(
            info=info,
            min_region_area=min_region_area,
            tiny_area_threshold=tiny_area_threshold,
            tiny_side_threshold=tiny_side_threshold,
            tiny_merge_min_area=tiny_merge_min_area,
            tiny_merge_min_side=tiny_merge_min_side,
        )
    return region_infos


def region_neighbor_score(small_info, candidate_info, color_gap):
    gap_x, gap_y = bbox_gap(small_info["bbox"], candidate_info["bbox"])
    centroid_dx = small_info["centroid"]["x"] - candidate_info["centroid"]["x"]
    centroid_dy = small_info["centroid"]["y"] - candidate_info["centroid"]["y"]
    centroid_distance = math.sqrt(centroid_dx * centroid_dx + centroid_dy * centroid_dy)
    normalized_area_penalty = small_info["area"] / max(1.0, candidate_info["area"])
    return (
        gap_x * 1.5
        + gap_y * 2.0
        + centroid_distance * 0.05
        + color_gap
        + normalized_area_penalty * 8.0
    )


def merge_region_info_into_parent(parent_info, child_info):
    merged_regions = list(parent_info["region"])
    merged_regions.extend(child_info["region"])
    merged_bbox = merge_bboxes([parent_info["bbox"], child_info["bbox"]])
    merged_centroid = get_region_centroid(merged_regions)
    merged_label_anchor = find_label_anchor(merged_regions, merged_bbox, merged_centroid)
    parent_info["region"] = merged_regions
    parent_info["area"] = len(merged_regions)
    parent_info["bbox"] = merged_bbox
    parent_info["centroid"] = merged_centroid
    parent_info["label_anchor"] = merged_label_anchor
    parent_info["hide_number"] = parent_info["hide_number"] and child_info["hide_number"]
    parent_info["merged_region_count"] = parent_info.get("merged_region_count", 1) + child_info.get(
        "merged_region_count", 1
    )
    return parent_info


def merge_tiny_regions_into_neighbors(
    region_infos,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
    attach_distance,
    color_threshold,
    tiny_merge_policy,
):
    if not region_infos:
        return region_infos, 0, 0, 0

    policy = TINY_MERGE_POLICY_DEFAULTS[tiny_merge_policy]
    effective_attach_distance = max(
        tiny_merge_min_side,
        int(round(attach_distance * policy["attach_distance_multiplier"])),
    )
    effective_color_threshold = max(6.0, color_threshold * policy["color_threshold_multiplier"])

    region_infos = classify_region_infos(
        region_infos=region_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=tiny_area_threshold,
        tiny_side_threshold=tiny_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
    )

    merged_tiny_regions_count = 0
    forced_tiny_region_merges_count = 0

    while True:
        micro_indices = sorted(
            [idx for idx, info in enumerate(region_infos) if info.get("is_micro_region")],
            key=lambda idx: region_infos[idx]["area"],
        )
        if not micro_indices:
            break

        merged_any = False
        for small_idx in micro_indices:
            if small_idx >= len(region_infos):
                continue
            small_info = region_infos[small_idx]
            if not small_info.get("is_micro_region"):
                continue

            best_candidate_idx = None
            best_candidate_score = None
            fallback_candidate_idx = None
            fallback_candidate_score = None

            for candidate_idx, candidate_info in enumerate(region_infos):
                if candidate_idx == small_idx or candidate_info["area"] <= small_info["area"]:
                    continue

                gap_x, gap_y = bbox_gap(small_info["bbox"], candidate_info["bbox"])
                if max(gap_x, gap_y) > effective_attach_distance:
                    continue

                color_gap = color_distance(small_info["target_color"], candidate_info["target_color"])
                score = region_neighbor_score(small_info, candidate_info, color_gap)

                if color_gap <= effective_color_threshold:
                    if best_candidate_score is None or score < best_candidate_score:
                        best_candidate_score = score
                        best_candidate_idx = candidate_idx

                if fallback_candidate_score is None or score < fallback_candidate_score:
                    fallback_candidate_score = score
                    fallback_candidate_idx = candidate_idx

            target_idx = best_candidate_idx
            forced_merge = False
            if target_idx is None and policy["allow_fallback"]:
                target_idx = fallback_candidate_idx
                forced_merge = target_idx is not None

            if target_idx is None:
                continue

            merge_region_info_into_parent(region_infos[target_idx], small_info)
            region_infos.pop(small_idx)
            classify_region_infos(
                region_infos=region_infos,
                min_region_area=min_region_area,
                tiny_area_threshold=tiny_area_threshold,
                tiny_side_threshold=tiny_side_threshold,
                tiny_merge_min_area=tiny_merge_min_area,
                tiny_merge_min_side=tiny_merge_min_side,
            )
            merged_tiny_regions_count += 1
            if forced_merge and best_candidate_idx is None:
                forced_tiny_region_merges_count += 1
            merged_any = True
            break

        if not merged_any:
            break

    remaining_tiny_regions_count = sum(1 for info in region_infos if info.get("is_micro_region"))
    return (
        region_infos,
        merged_tiny_regions_count,
        forced_tiny_region_merges_count,
        remaining_tiny_regions_count,
    )


def absorb_small_region_colors(
    region_infos,
    attach_distance,
    color_threshold,
    tiny_area_threshold,
    tiny_side_threshold,
):
    absorbed_count = 0
    sorted_indices = sorted(range(len(region_infos)), key=lambda idx: region_infos[idx]["area"])
    for small_idx in sorted_indices:
        info = region_infos[small_idx]
        if not is_tiny_display_region(info, tiny_area_threshold, tiny_side_threshold):
            continue

        best_candidate = None
        best_score = None
        candidate_attach_distance = max(attach_distance * 2, tiny_side_threshold * 2)
        for large_idx, candidate in enumerate(region_infos):
            if large_idx == small_idx or candidate["area"] <= info["area"]:
                continue
            gap_x, gap_y = bbox_gap(info["bbox"], candidate["bbox"])
            if max(gap_x, gap_y) > candidate_attach_distance:
                continue
            color_gap = color_distance(info["target_color"], candidate["target_color"])
            if color_gap > color_threshold:
                continue
            score = gap_x + gap_y * 2 + color_gap + (
                info["area"] / max(1.0, candidate["area"])
            ) * 10.0
            if best_score is None or score < best_score:
                best_score = score
                best_candidate = candidate

        if best_candidate is not None and info["target_color"] != best_candidate["target_color"]:
            info["target_color"] = best_candidate["target_color"]
            absorbed_count += 1

    return absorbed_count


def merge_small_attached_regions(
    region_infos,
    min_region_area,
    attach_distance,
    color_threshold,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
):
    if not region_infos:
        return region_infos, 0

    for info in region_infos:
        info["merge_children"] = []
        info["merged_into"] = None

    sorted_indices = sorted(range(len(region_infos)), key=lambda idx: region_infos[idx]["area"])
    merged_count = 0

    for small_idx in sorted_indices:
        small_info = region_infos[small_idx]
        small_or_tiny = (
            small_info["area"] < min_region_area
            or is_tiny_display_region(small_info, tiny_area_threshold, tiny_side_threshold)
        )
        if not small_or_tiny or small_info["merged_into"] is not None:
            continue

        best_candidate_idx = None
        best_candidate_score = None
        candidate_attach_distance = attach_distance
        if is_tiny_display_region(small_info, tiny_area_threshold, tiny_side_threshold):
            candidate_attach_distance = max(attach_distance, tiny_side_threshold * 2)

        for large_idx, large_info in enumerate(region_infos):
            if large_idx == small_idx or large_info["merged_into"] is not None:
                continue
            if large_info["area"] <= small_info["area"]:
                continue

            color_gap = color_distance(small_info["target_color"], large_info["target_color"])
            if color_gap > color_threshold:
                continue

            gap_x, gap_y = bbox_gap(small_info["bbox"], large_info["bbox"])
            if max(gap_x, gap_y) > candidate_attach_distance:
                continue

            gap_score = gap_x + gap_y * 2
            area_score = abs(large_info["area"] - small_info["area"]) / max(1, large_info["area"])
            score = gap_score + color_gap + area_score
            if best_candidate_score is None or score < best_candidate_score:
                best_candidate_score = score
                best_candidate_idx = large_idx

        if best_candidate_idx is not None:
            region_infos[best_candidate_idx]["merge_children"].append(small_idx)
            small_info["merged_into"] = best_candidate_idx
            merged_count += 1

    grouped_infos = []
    for idx, info in enumerate(region_infos):
        if info["merged_into"] is not None:
            continue

        merged_indices = [idx] + info["merge_children"]
        merged_regions = []
        merged_boxes = []
        hide_number = True
        small_group = True
        for merged_idx in merged_indices:
            child = region_infos[merged_idx]
            merged_regions.extend(child["region"])
            merged_boxes.append(child["bbox"])
            hide_number = hide_number and child["hide_number"]
            if child["area"] >= min_region_area:
                small_group = False

        merged_area = len(merged_regions)
        merged_bbox = merge_bboxes(merged_boxes)
        merged_centroid = get_region_centroid(merged_regions)
        merged_label_anchor = find_label_anchor(merged_regions, merged_bbox, merged_centroid)
        merged_is_tiny = is_tiny_display_region(
            {"area": merged_area, "bbox": merged_bbox},
            tiny_area_threshold,
            tiny_side_threshold,
        )
        merged_info = {
            "region": merged_regions,
            "area": merged_area,
            "bbox": merged_bbox,
            "centroid": merged_centroid,
            "label_anchor": merged_label_anchor,
            "target_color": info["target_color"],
            "hide_number": hide_number and merged_is_tiny,
            "is_small_region": small_group,
            "merged_region_count": len(merged_indices),
            "is_tiny_display_region": merged_is_tiny,
        }
        grouped_infos.append(
            update_region_classification(
                info=merged_info,
                min_region_area=min_region_area,
                tiny_area_threshold=tiny_area_threshold,
                tiny_side_threshold=tiny_side_threshold,
                tiny_merge_min_area=tiny_merge_min_area,
                tiny_merge_min_side=tiny_merge_min_side,
            )
        )

    return grouped_infos, merged_count


def build_single_output_dir(args):
    if args.output_directory:
        return args.output_directory

    category = args.category or "Uncategorized"
    generated_id = args.generated_id or find_next_available_id(args.output_root)
    return os.path.join(args.output_root, category, generated_id)


def generate_level_assets(
    line_art_path,
    reference_path,
    output_dir,
    category_name="",
    data_name="",
    generated_id="",
    brightness_threshold=120,
    color_merge_threshold=15.0,
    min_region_area=8,
    line_close_radius=0,
    hide_small_label_threshold=48,
    small_region_attach_distance=8,
    tiny_region_side_threshold=10,
    tiny_merge_min_area=20,
    tiny_merge_min_side=6,
    tiny_merge_attach_distance=None,
    tiny_merge_color_threshold=None,
    tiny_merge_policy="relaxed",
    target_unique_colors=None,
    category_profile=None,
    quantize_method="mediancut",
    adaptive_palette=True,
    preprocess_profile="standard",
    region_color_method="median",
    detail_alpha=0.65,
):
    print(f"Đang tải ảnh nét vẽ: {line_art_path}")
    source_line_img, binary_img, selected_preprocessing = select_binary_fill_map(
        line_art_path,
        brightness_threshold=brightness_threshold,
        line_close_radius=line_close_radius,
        preprocess_profile=preprocess_profile,
    )
    print(
        "Preprocess được chọn: "
        f"profile={selected_preprocessing['profile']}, "
        f"threshold={selected_preprocessing['brightness_threshold']}, "
        f"close_radius={selected_preprocessing['line_close_radius']}, "
        f"candidate_score={selected_preprocessing['score']}"
    )

    print(f"Đang tải ảnh tham chiếu màu: {reference_path}")
    ref_img = Image.open(reference_path).convert("RGB")

    if source_line_img.size != ref_img.size:
        raise ValueError("Kích thước của ảnh nét vẽ và ảnh tham chiếu phải trùng khớp.")

    width, height = source_line_img.size
    line_img = make_line_output_image(binary_img)
    resolved_profile, resolved_target_unique_colors = resolve_target_unique_colors(
        category_name=category_name,
        requested_profile=category_profile,
        explicit_target=target_unique_colors,
    )
    quantized_ref_img, quantized_palette_budget, quantized_palette_count = build_quantized_reference_image(
        ref_img,
        target_unique_colors=resolved_target_unique_colors,
        method_name=quantize_method,
    )
    ref_pixels = ref_img.load()

    mask_img = Image.new("RGB", (width, height), (0, 0, 0))
    mask_pixels = mask_img.load()

    print("Đang phân tích các vùng trống trên ảnh nét vẽ...")
    regions = extract_regions(binary_img)
    raw_region_count = len(regions)
    print(f"Tìm thấy {raw_region_count} vùng mảng màu riêng biệt.")

    print("Đang lấy màu tham chiếu và metadata cho từng vùng...")
    region_infos = []
    skipped_noise_regions = 0

    for region in regions:
        area = len(region)
        if area <= 1:
            skipped_noise_regions += 1
            continue

        representative_color = get_representative_color(
            ref_pixels,
            region,
            method=region_color_method,
            quantize_step=0,
        )
        bbox = get_region_bbox(region)
        centroid = get_region_centroid(region)
        label_anchor = find_label_anchor(region, bbox, centroid)
        hide_number = area < hide_small_label_threshold or bbox_min_side(bbox) < tiny_region_side_threshold

        region_infos.append(
            update_region_classification(
                info={
                    "region": region,
                    "area": area,
                    "bbox": bbox,
                    "centroid": centroid,
                    "label_anchor": label_anchor,
                    "target_color": representative_color,
                    "representative_color": representative_color,
                    "hide_number": hide_number,
                    "merged_region_count": 1,
                },
                min_region_area=min_region_area,
                tiny_area_threshold=hide_small_label_threshold,
                tiny_side_threshold=tiny_region_side_threshold,
                tiny_merge_min_area=tiny_merge_min_area,
                tiny_merge_min_side=tiny_merge_min_side,
            )
        )

    effective_tiny_merge_attach_distance = (
        tiny_merge_attach_distance
        if tiny_merge_attach_distance is not None
        else max(small_region_attach_distance * 2, tiny_merge_min_side * 2)
    )
    effective_tiny_merge_color_threshold = (
        tiny_merge_color_threshold
        if tiny_merge_color_threshold is not None
        else max(10.0, color_merge_threshold * 1.5)
    )

    (
        region_infos,
        merged_tiny_regions_count,
        forced_tiny_region_merges_count,
        remaining_tiny_regions_count,
    ) = merge_tiny_regions_into_neighbors(
        region_infos=region_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        attach_distance=effective_tiny_merge_attach_distance,
        color_threshold=effective_tiny_merge_color_threshold,
        tiny_merge_policy=tiny_merge_policy,
    )

    region_infos, merged_small_region_count = merge_small_attached_regions(
        region_infos=region_infos,
        min_region_area=min_region_area,
        attach_distance=small_region_attach_distance,
        color_threshold=max(6.0, color_merge_threshold * 0.65),
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
    )
    region_infos = classify_region_infos(
        region_infos=region_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
    )
    absorbed_small_color_count = absorb_small_region_colors(
        region_infos=region_infos,
        attach_distance=small_region_attach_distance,
        color_threshold=max(10.0, color_merge_threshold * 1.5),
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
    )

    palette_result = build_palette_with_adaptive_merge(
        region_infos=region_infos,
        merge_threshold=color_merge_threshold,
        target_unique_colors=resolved_target_unique_colors,
        category_profile=resolved_profile,
        adaptive_enabled=adaptive_palette,
    )
    palette_colors = palette_result["palette_colors"]
    color_to_number = {color: index + 1 for index, color in enumerate(palette_colors)}

    os.makedirs(output_dir, exist_ok=True)

    region_palette_config = []
    region_configs = []
    region_pixel_sets = []
    mask_to_target_rgb = {}
    palette_summary = {}

    print("Đang vẽ ảnh Mask và tạo cấu hình Palette...")
    for region_id, info in enumerate(region_infos, start=1):
        region = info["region"]
        target_color = tuple(info["target_color"])
        best_palette_color = palette_result["color_mapping"].get(target_color)
        if best_palette_color is None:
            best_palette_color = choose_palette_color(target_color, palette_colors)
        palette_number = color_to_number[best_palette_color]

        mask_r = (region_id >> 16) & 0xFF
        mask_g = (region_id >> 8) & 0xFF
        mask_b = region_id & 0xFF
        mask_rgb = (mask_r, mask_g, mask_b)
        mask_hex = rgb_to_hex(mask_rgb)

        for x, y in region:
            mask_pixels[x, y] = mask_rgb
        mask_to_target_rgb[mask_rgb] = best_palette_color

        palette_key = (palette_number, best_palette_color)
        if palette_key not in palette_summary:
            palette_summary[palette_key] = {
                "number": palette_number,
                "target_color": rgb_to_hex(best_palette_color),
                "region_count": 0,
                "total_area": 0,
            }
        palette_summary[palette_key]["region_count"] += 1
        palette_summary[palette_key]["total_area"] += info["area"]

        region_palette_config.append(
            {
                "number": palette_number,
                "mask_color": mask_hex,
                "target_color": rgb_to_hex(best_palette_color),
            }
        )

        region_configs.append(
            {
                "id": region_id,
                "mask_color": mask_hex,
                "number": palette_number,
                "target_color": rgb_to_hex(best_palette_color),
                "fill_color": rgb_to_hex(best_palette_color),
                "representative_color": rgb_to_hex(tuple(info.get("representative_color", target_color))),
                "area": info["area"],
                "bbox": info["bbox"],
                "centroid": info["centroid"],
                "label_anchor": info["label_anchor"],
                "hide_number": info["hide_number"],
                "quality": {
                    "is_tiny": info["is_tiny_display_region"],
                    "is_small": info["is_small_region"],
                    "touchable": not info["hide_number"],
                    "merged_region_count": info.get("merged_region_count", 1),
                },
            }
        )
        region_pixel_sets.append(region)

    small_regions_count = sum(1 for info in region_infos if info["is_small_region"])
    unique_numbers = len({item["number"] for item in region_palette_config})
    difficulty = estimate_difficulty(
        total_regions=len(region_configs),
        unique_numbers=unique_numbers,
        small_regions_count=small_regions_count,
    )
    giant_regions_count = sum(
        1 for item in region_configs if item["area"] / max(1, width * height) > 0.6
    )
    palette_config = sorted(
        palette_summary.values(),
        key=lambda item: item["number"],
    )
    stats = {
        "total_regions": len(region_configs),
        "unique_numbers": unique_numbers,
        "estimated_difficulty": difficulty,
        "small_regions_count": small_regions_count,
        "giant_regions_count": giant_regions_count,
    }
    generation_params = {
        "brightness_threshold": brightness_threshold,
        "merge_threshold": color_merge_threshold,
        "line_close_radius": line_close_radius,
        "min_region_area": min_region_area,
        "hide_small_label_threshold": hide_small_label_threshold,
        "small_region_attach_distance": small_region_attach_distance,
        "tiny_region_side_threshold": tiny_region_side_threshold,
        "tiny_merge_min_area": tiny_merge_min_area,
        "tiny_merge_min_side": tiny_merge_min_side,
        "tiny_merge_attach_distance": effective_tiny_merge_attach_distance,
        "tiny_merge_color_threshold": effective_tiny_merge_color_threshold,
        "tiny_merge_policy": tiny_merge_policy,
        "adaptive_palette": adaptive_palette,
        "quantize_method": quantize_method,
        "region_color_method": region_color_method,
        "category_profile": resolved_profile,
        "target_unique_colors": resolved_target_unique_colors,
        "preprocess_profile": preprocess_profile,
        "selected_preprocessing": selected_preprocessing,
        "has_detail": True,
        "detail_mode": "reference_lerp_rgba",
        "detail_alpha": detail_alpha,
    }

    print(
        "Palette stats: "
        f"profile={resolved_profile}, "
        f"target={resolved_target_unique_colors}, "
        f"quantized_ref_colors={quantized_palette_count}/{quantized_palette_budget}, "
        f"initial_unique={palette_result['initial_unique_numbers']}, "
        f"final_unique={palette_result['final_unique_numbers']}, "
        f"merged_tiny_regions={merged_tiny_regions_count}, "
        f"remaining_micro_regions={remaining_tiny_regions_count}, "
        f"final_merge_threshold={palette_result['final_merge_threshold']:.2f}"
    )

    mask_out_path = os.path.join(output_dir, "mask.png")
    mask_img.save(mask_out_path)
    print(f"Đã lưu ảnh Mask: {mask_out_path}")

    line_out_path = os.path.join(output_dir, "line.png")
    line_img.save(line_out_path)
    print(f"Đã lưu ảnh Line: {line_out_path}")

    source_line_out_path = os.path.join(output_dir, "debug_source_line.png")
    source_line_img.save(source_line_out_path)
    print(f"Đã lưu ảnh Line gốc để debug: {source_line_out_path}")

    flat_preview_img = make_flat_colored_image(
        width=width,
        height=height,
        mask_img=mask_img,
        mask_to_target_rgb=mask_to_target_rgb,
    )
    flat_preview_out_path = os.path.join(output_dir, "debug_preview_flat.png")
    apply_line_overlay(flat_preview_img, line_img).save(flat_preview_out_path)
    print(f"Đã lưu ảnh Preview flat để debug: {flat_preview_out_path}")

    detail_img = make_detail_overlay_image(
        reference_img=ref_img,
        flat_preview_img=flat_preview_img,
        line_img=line_img,
        mask_img=mask_img,
        mask_to_target_rgb=mask_to_target_rgb,
        detail_alpha=detail_alpha,
    )
    detail_out_path = os.path.join(output_dir, "detail.png")
    detail_img.save(detail_out_path)
    print(f"Đã lưu ảnh Detail/Shading: {detail_out_path}")

    preview_img = make_preview_colored_image(
        width=width,
        height=height,
        mask_img=mask_img,
        line_img=line_img,
        mask_to_target_rgb=mask_to_target_rgb,
        detail_img=detail_img,
    )
    preview_out_path = os.path.join(output_dir, "preview_colored.png")
    preview_img.save(preview_out_path)
    print(f"Đã lưu ảnh Preview: {preview_out_path}")

    debug_regions_img = make_debug_regions_image(
        width=width,
        height=height,
        region_configs=region_configs,
        region_pixel_sets=region_pixel_sets,
    )
    debug_regions_out_path = os.path.join(output_dir, "debug_regions.png")
    debug_regions_img.save(debug_regions_out_path)
    print(f"Đã lưu ảnh Debug Regions: {debug_regions_out_path}")

    legacy_quality_report = build_quality_report(
        width=width,
        height=height,
        region_configs=region_configs,
        stats=stats,
        generation_params=generation_params,
    )
    config_data = {
        "schema_version": 2,
        "id": generated_id if generated_id else os.path.basename(output_dir),
        "name": data_name if data_name else os.path.basename(output_dir),
        "category": category_name if category_name else "Uncategorized",
        "width": width,
        "height": height,
        "assets": {
            "line": "line.png",
            "mask": "mask.png",
            "preview": "preview_colored.png",
            "detail": "detail.png",
            "debug_regions": "debug_regions.png",
            "debug_report": "debug_report.json",
            "debug_source_line": "debug_source_line.png",
            "debug_preview_flat": "debug_preview_flat.png",
        },
        "palette": palette_config,
        "region_palette": region_palette_config,
        "regions": region_configs,
        "stats": stats,
        "estimated_difficulty": difficulty,
        "total_regions": len(region_configs),
        "unique_numbers": unique_numbers,
        "small_regions_count": small_regions_count,
        "skipped_noise_regions": skipped_noise_regions,
        "merged_small_regions_count": merged_small_region_count,
        "merged_tiny_regions_count": merged_tiny_regions_count,
        "forced_tiny_region_merges_count": forced_tiny_region_merges_count,
        "remaining_tiny_regions_count": remaining_tiny_regions_count,
        "generation": {
            "source_mode": "line_plus_reference",
            **generation_params,
        },
        "generation_params": generation_params,
        "category_profile": resolved_profile,
        "target_unique_colors": resolved_target_unique_colors,
        "initial_unique_numbers": palette_result["initial_unique_numbers"],
        "final_unique_numbers": palette_result["final_unique_numbers"],
        "quantization_method": quantize_method,
        "quantized_reference_color_count": quantized_palette_count,
        "final_merge_threshold": palette_result["final_merge_threshold"],
        "palette_reduction_passes": palette_result["palette_reduction_passes"],
        "palette_reduction_reason": palette_result["palette_reduction_reason"],
        "absorbed_small_color_count": absorbed_small_color_count,
        "compat": {
            "region_palette_entries": True,
            "android_source_of_truth": "regions",
        },
    }

    config_out_path = os.path.join(output_dir, "config.json")
    with open(config_out_path, "w", encoding="utf-8") as output_file:
        json.dump(config_data, output_file, indent=2, ensure_ascii=False)
    print(f"Đã lưu file cấu hình: {config_out_path}")

    quality_report = merge_quality_report(
        legacy_quality_report,
        evaluate_level_dir(output_dir, reference_path=reference_path),
    )
    debug_report_out_path = os.path.join(output_dir, "debug_report.json")
    with open(debug_report_out_path, "w", encoding="utf-8") as output_file:
        json.dump(quality_report, output_file, indent=2, ensure_ascii=False)
    print(
        f"Đã lưu báo cáo Debug: {debug_report_out_path} "
        f"(grade={quality_report['quality_grade']}, score={quality_report['quality_score']})"
    )
    print("=== Hoàn tất sinh Asset! ===")


def create_parser():
    parser = argparse.ArgumentParser(
        description="Tạo asset level Color By Number từ line art và ảnh màu tham chiếu."
    )
    parser.add_argument(
        "--output-root",
        default=DEFAULT_ASSETS_ROOT,
        help=f"Thư mục gốc assets đầu ra. Mặc định: {DEFAULT_ASSETS_ROOT}",
    )
    parser.add_argument(
        "--brightness-threshold",
        type=int,
        default=120,
        help="Ngưỡng sáng để nhận diện vùng tô.",
    )
    parser.add_argument(
        "--merge-threshold",
        type=float,
        default=15.0,
        help="Ngưỡng gộp các màu gần giống nhau.",
    )
    parser.add_argument(
        "--min-region-area",
        type=int,
        default=8,
        help="Ngưỡng vùng nhỏ để đánh dấu small region.",
    )
    parser.add_argument(
        "--line-close-radius",
        type=int,
        default=0,
        help="Bán kính vá khe hở line art nhỏ trước khi flood fill.",
    )
    parser.add_argument(
        "--hide-small-label-threshold",
        type=int,
        default=48,
        help="Ẩn số ở các vùng quá nhỏ hơn ngưỡng này.",
    )
    parser.add_argument(
        "--small-region-attach-distance",
        type=int,
        default=8,
        help="Khoảng cách tối đa để gộp vùng rất nhỏ vào vùng lớn cùng màu ở gần.",
    )
    parser.add_argument(
        "--tiny-region-side-threshold",
        type=int,
        default=10,
        help="Ngưỡng cạnh nhỏ nhất của bbox để xem vùng là quá nhỏ cho việc hiển thị số.",
    )
    parser.add_argument(
        "--tiny-merge-min-area",
        type=int,
        default=20,
        help="Ngưỡng area tối thiểu; nhỏ hơn ngưỡng này sẽ ưu tiên gộp hình học vào vùng lân cận.",
    )
    parser.add_argument(
        "--tiny-merge-min-side",
        type=int,
        default=6,
        help="Ngưỡng cạnh nhỏ nhất; nhỏ hơn ngưỡng này sẽ ưu tiên gộp hình học vào vùng lân cận.",
    )
    parser.add_argument(
        "--tiny-merge-attach-distance",
        type=int,
        help="Khoảng cách tối đa để gộp chấm siêu nhỏ vào vùng lân cận.",
    )
    parser.add_argument(
        "--tiny-merge-color-threshold",
        type=float,
        help="Ngưỡng lệch màu tối đa ưu tiên khi gộp chấm siêu nhỏ.",
    )
    parser.add_argument(
        "--tiny-merge-policy",
        choices=sorted(TINY_MERGE_POLICY_DEFAULTS.keys()),
        default="relaxed",
        help="Chính sách gộp chấm siêu nhỏ: relaxed, strict hoặc mandala.",
    )
    parser.add_argument(
        "--target-unique-colors",
        type=int,
        help="Số lượng màu mục tiêu tối đa cho palette cuối.",
    )
    parser.add_argument(
        "--category-profile",
        choices=sorted(DEFAULT_PROFILE_TARGETS.keys()),
        help="Profile palette theo loại tranh: mandala, standard hoặc easy.",
    )
    parser.add_argument(
        "--quantize-method",
        choices=sorted(QUANTIZE_METHODS.keys()),
        default="mediancut",
        help="Thuật toán quantize ảnh tham chiếu trước khi gom màu.",
    )
    parser.add_argument(
        "--disable-adaptive-palette",
        action="store_true",
        help="Tắt adaptive palette để debug/fallback theo pipeline cũ.",
    )
    parser.add_argument(
        "--preprocess-profile",
        choices=["standard", "thin-line", "manga", "mandala", "poster", "auto"],
        default="standard",
        help=(
            "Profile xử lý line trước flood-fill. Mặc định standard giữ hành vi cũ; "
            "auto thử nhiều threshold/close radius và chọn candidate tốt nhất theo metric vùng."
        ),
    )
    parser.add_argument(
        "--region-color-method",
        choices=["dominant", "median", "trimmed-mean"],
        default="median",
        help=(
            "Cách chọn màu đại diện cho mỗi region. median giữ màu ổn định hơn khi vùng có "
            "highlight/shadow nhỏ; dominant là hành vi gần pipeline cũ."
        ),
    )
    parser.add_argument(
        "--detail-alpha",
        type=float,
        default=0.65,
        help="Độ mạnh tối đa của detail.png khi blend lại với flat fill, trong khoảng 0..1.",
    )

    subparsers = parser.add_subparsers(dest="mode", required=True)

    single = subparsers.add_parser("single", help="Tạo 1 level từ 2 ảnh.")
    single.add_argument("line_art_path", help="Đường dẫn tới ảnh line art.")
    single.add_argument("reference_colored_path", help="Đường dẫn tới ảnh màu tham chiếu.")
    single.add_argument(
        "--output-directory",
        help="Thư mục level đầu ra cụ thể. Nếu bỏ qua sẽ tự tạo dưới output-root/category/id.",
    )
    single.add_argument("--category", help="Tên category, ví dụ Cartoons hoặc Mandala.")
    single.add_argument("--name", help="Tên level hiển thị trong app.")
    single.add_argument("--id", dest="generated_id", help="ID level. Nếu bỏ qua sẽ tự tìm ID trống.")

    batch = subparsers.add_parser("batch", help="Tạo hàng loạt level từ thư mục input.")
    batch.add_argument("input_root_directory", help="Thư mục nguồn chứa Category/LevelName.")
    batch.add_argument(
        "--start-id",
        type=int,
        default=100001,
        help="ID bắt đầu khi tạo hàng loạt.",
    )

    batch_single_category = subparsers.add_parser(
        "batch-single-category",
        help="Gom toàn bộ level trong Data vào một category đích duy nhất.",
    )
    batch_single_category.add_argument(
        "input_root_directory",
        help="Thư mục nguồn chứa Category/LevelName.",
    )
    batch_single_category.add_argument(
        "target_category",
        help="Category đích duy nhất trong assets.",
    )
    batch_single_category.add_argument(
        "--start-id",
        type=int,
        default=100001,
        help="ID bắt đầu khi tạo hàng loạt.",
    )

    batch_source_category = subparsers.add_parser(
        "batch-source-category",
        help="Import toàn bộ item trong một folder category nguồn vào một category trong assets.",
    )
    batch_source_category.add_argument(
        "input_category_directory",
        help="Thư mục nguồn dạng Data/Animals hoặc Data/Manga.",
    )
    batch_source_category.add_argument(
        "--target-category",
        help="Tên category đích trong assets. Mặc định dùng tên folder nguồn.",
    )

    return parser


def collect_levels_from_data(input_root):
    if not os.path.exists(input_root):
        raise FileNotFoundError(f"Thư mục đầu vào '{input_root}' không tồn tại.")

    levels_to_process = []

    for category_name in sorted(os.listdir(input_root)):
        category_path = os.path.join(input_root, category_name)
        if not os.path.isdir(category_path) or category_name.startswith("."):
            continue

        for data_name in sorted(os.listdir(category_path)):
            data_path = os.path.join(category_path, data_name)
            if not os.path.isdir(data_path) or data_name.startswith("."):
                continue

            line_file = None
            ref_file = None
            for file_name in os.listdir(data_path):
                lower_name = file_name.lower()
                if "line" in lower_name and lower_name.endswith((".png", ".webp", ".jpg", ".jpeg")):
                    line_file = file_name
                elif (
                    ("ref" in lower_name or "color" in lower_name or "paint" in lower_name)
                    and lower_name.endswith((".png", ".webp", ".jpg", ".jpeg"))
                ):
                    ref_file = file_name

            if line_file and ref_file:
                levels_to_process.append(
                    {
                        "source_category": category_name,
                        "name": data_name,
                        "line_path": os.path.join(data_path, line_file),
                        "ref_path": os.path.join(data_path, ref_file),
                    }
                )
            else:
                print(f"Bỏ qua '{category_name}/{data_name}': thiếu file line/color hợp lệ.")

    return levels_to_process


def collect_levels_from_single_category(input_category_directory):
    if not os.path.exists(input_category_directory):
        raise FileNotFoundError(
            f"Thư mục category đầu vào '{input_category_directory}' không tồn tại."
        )
    if not os.path.isdir(input_category_directory):
        raise NotADirectoryError(
            f"Đường dẫn '{input_category_directory}' không phải là thư mục category hợp lệ."
        )

    source_category = os.path.basename(os.path.normpath(input_category_directory))
    levels_to_process = []

    for data_name in sorted(os.listdir(input_category_directory)):
        data_path = os.path.join(input_category_directory, data_name)
        if not os.path.isdir(data_path) or data_name.startswith("."):
            continue

        line_file = None
        ref_file = None
        for file_name in os.listdir(data_path):
            lower_name = file_name.lower()
            if "line" in lower_name and lower_name.endswith((".png", ".webp", ".jpg", ".jpeg")):
                line_file = file_name
            elif (
                ("ref" in lower_name or "color" in lower_name or "paint" in lower_name)
                and lower_name.endswith((".png", ".webp", ".jpg", ".jpeg"))
            ):
                ref_file = file_name

        if line_file and ref_file:
            levels_to_process.append(
                {
                    "source_category": source_category,
                    "name": data_name,
                    "line_path": os.path.join(data_path, line_file),
                    "ref_path": os.path.join(data_path, ref_file),
                }
            )
        else:
            print(f"Bỏ qua '{source_category}/{data_name}': thiếu file line/color hợp lệ.")

    return levels_to_process


def run_batch(args):
    input_root = args.input_root_directory
    output_root = args.output_root

    print(f"=== BẮT ĐẦU XỬ LÝ HÀNG LOẠT TỪ THƯ MỤC: {input_root} ===")
    levels_to_process = collect_levels_from_data(input_root)
    print(f"Tìm thấy tổng cộng {len(levels_to_process)} tác phẩm hợp lệ.")
    processed_count = 0
    next_id = args.start_id

    for level in levels_to_process:
        while os.path.exists(os.path.join(output_root, level["source_category"], str(next_id))):
            next_id += 1
        generated_id = str(next_id)
        next_id += 1
        level_out_dir = os.path.join(output_root, level["source_category"], generated_id)

        print(
            f"\n[XỬ LÝ - ID: {generated_id}] "
            f"Category: {level['source_category']} | Name: {level['name']}"
        )
        generate_level_assets(
            level["line_path"],
            level["ref_path"],
            level_out_dir,
            category_name=level["source_category"],
            data_name=level["name"],
            generated_id=generated_id,
            brightness_threshold=args.brightness_threshold,
            color_merge_threshold=args.merge_threshold,
            min_region_area=args.min_region_area,
            line_close_radius=args.line_close_radius,
            hide_small_label_threshold=args.hide_small_label_threshold,
            small_region_attach_distance=args.small_region_attach_distance,
            tiny_region_side_threshold=args.tiny_region_side_threshold,
            tiny_merge_min_area=args.tiny_merge_min_area,
            tiny_merge_min_side=args.tiny_merge_min_side,
            tiny_merge_attach_distance=args.tiny_merge_attach_distance,
            tiny_merge_color_threshold=args.tiny_merge_color_threshold,
            tiny_merge_policy=args.tiny_merge_policy,
            target_unique_colors=args.target_unique_colors,
            category_profile=args.category_profile,
            quantize_method=args.quantize_method,
            adaptive_palette=not args.disable_adaptive_palette,
            preprocess_profile=args.preprocess_profile,
            region_color_method=args.region_color_method,
            detail_alpha=args.detail_alpha,
        )
        processed_count += 1

    print(f"\n=== HOÀN TẤT XỬ LÝ HÀNG LOẠT! Đã tạo thành công {processed_count} tác phẩm. ===")


def run_batch_single_category(args):
    input_root = args.input_root_directory
    output_root = args.output_root
    target_category = args.target_category

    print(
        f"=== BẮT ĐẦU GOM TOÀN BỘ LEVEL TỪ THƯ MỤC: {input_root} "
        f"VÀO CATEGORY: {target_category} ==="
    )
    levels_to_process = collect_levels_from_data(input_root)
    print(f"Tìm thấy tổng cộng {len(levels_to_process)} tác phẩm hợp lệ.")
    processed_count = 0
    next_id = args.start_id

    for level in levels_to_process:
        while os.path.exists(os.path.join(output_root, target_category, str(next_id))):
            next_id += 1
        generated_id = str(next_id)
        next_id += 1
        level_out_dir = os.path.join(output_root, target_category, generated_id)

        print(
            f"\n[XỬ LÝ - ID: {generated_id}] "
            f"Source: {level['source_category']}/{level['name']} -> Category: {target_category}"
        )
        generate_level_assets(
            level["line_path"],
            level["ref_path"],
            level_out_dir,
            category_name=target_category,
            data_name=generated_id,
            generated_id=generated_id,
            brightness_threshold=args.brightness_threshold,
            color_merge_threshold=args.merge_threshold,
            min_region_area=args.min_region_area,
            line_close_radius=args.line_close_radius,
            hide_small_label_threshold=args.hide_small_label_threshold,
            small_region_attach_distance=args.small_region_attach_distance,
            tiny_region_side_threshold=args.tiny_region_side_threshold,
            tiny_merge_min_area=args.tiny_merge_min_area,
            tiny_merge_min_side=args.tiny_merge_min_side,
            tiny_merge_attach_distance=args.tiny_merge_attach_distance,
            tiny_merge_color_threshold=args.tiny_merge_color_threshold,
            tiny_merge_policy=args.tiny_merge_policy,
            target_unique_colors=args.target_unique_colors,
            category_profile=args.category_profile,
            quantize_method=args.quantize_method,
            adaptive_palette=not args.disable_adaptive_palette,
            preprocess_profile=args.preprocess_profile,
            region_color_method=args.region_color_method,
            detail_alpha=args.detail_alpha,
        )
        processed_count += 1

    print(
        f"\n=== HOÀN TẤT GOM CATEGORY! Đã tạo thành công {processed_count} tác phẩm vào "
        f"'{target_category}'. ==="
    )


def run_batch_source_category(args):
    input_category_directory = args.input_category_directory
    output_root = args.output_root
    levels_to_process = collect_levels_from_single_category(input_category_directory)
    source_category = os.path.basename(os.path.normpath(input_category_directory))
    target_category = args.target_category or source_category

    print(
        f"=== BẮT ĐẦU IMPORT CATEGORY NGUỒN: {source_category} "
        f"-> CATEGORY ĐÍCH: {target_category} ==="
    )
    print(f"Tìm thấy tổng cộng {len(levels_to_process)} tác phẩm hợp lệ.")

    processed_count = 0

    for level in levels_to_process:
        generated_id = level["name"]
        level_out_dir = os.path.join(output_root, target_category, generated_id)

        if os.path.exists(level_out_dir):
            raise FileExistsError(
                f"Thư mục đích đã tồn tại: {level_out_dir}. "
                "Hãy xóa/sửa folder cũ hoặc dùng --target-category khác."
            )

        print(
            f"\n[XỬ LÝ - ID: {generated_id}] "
            f"Source: {source_category}/{level['name']} -> Category: {target_category}"
        )
        generate_level_assets(
            level["line_path"],
            level["ref_path"],
            level_out_dir,
            category_name=target_category,
            data_name=generated_id,
            generated_id=generated_id,
            brightness_threshold=args.brightness_threshold,
            color_merge_threshold=args.merge_threshold,
            min_region_area=args.min_region_area,
            line_close_radius=args.line_close_radius,
            hide_small_label_threshold=args.hide_small_label_threshold,
            small_region_attach_distance=args.small_region_attach_distance,
            tiny_region_side_threshold=args.tiny_region_side_threshold,
            tiny_merge_min_area=args.tiny_merge_min_area,
            tiny_merge_min_side=args.tiny_merge_min_side,
            tiny_merge_attach_distance=args.tiny_merge_attach_distance,
            tiny_merge_color_threshold=args.tiny_merge_color_threshold,
            tiny_merge_policy=args.tiny_merge_policy,
            target_unique_colors=args.target_unique_colors,
            category_profile=args.category_profile,
            quantize_method=args.quantize_method,
            adaptive_palette=not args.disable_adaptive_palette,
            preprocess_profile=args.preprocess_profile,
            region_color_method=args.region_color_method,
            detail_alpha=args.detail_alpha,
        )
        processed_count += 1

    print(
        f"\n=== HOÀN TẤT IMPORT CATEGORY! Đã tạo thành công {processed_count} tác phẩm vào "
        f"'{target_category}'. ==="
    )


def run_single(args):
    out_dir = build_single_output_dir(args)
    generated_id = args.generated_id or os.path.basename(out_dir)
    category_name = args.category or os.path.basename(os.path.dirname(out_dir))
    data_name = args.name or generated_id

    generate_level_assets(
        args.line_art_path,
        args.reference_colored_path,
        out_dir,
        category_name=category_name,
        data_name=data_name,
        generated_id=generated_id,
        brightness_threshold=args.brightness_threshold,
        color_merge_threshold=args.merge_threshold,
        min_region_area=args.min_region_area,
        line_close_radius=args.line_close_radius,
        hide_small_label_threshold=args.hide_small_label_threshold,
        small_region_attach_distance=args.small_region_attach_distance,
        tiny_region_side_threshold=args.tiny_region_side_threshold,
        tiny_merge_min_area=args.tiny_merge_min_area,
        tiny_merge_min_side=args.tiny_merge_min_side,
        tiny_merge_attach_distance=args.tiny_merge_attach_distance,
        tiny_merge_color_threshold=args.tiny_merge_color_threshold,
        tiny_merge_policy=args.tiny_merge_policy,
        target_unique_colors=args.target_unique_colors,
        category_profile=args.category_profile,
        quantize_method=args.quantize_method,
        adaptive_palette=not args.disable_adaptive_palette,
        preprocess_profile=args.preprocess_profile,
        region_color_method=args.region_color_method,
        detail_alpha=args.detail_alpha,
    )


if __name__ == "__main__":
    parsed_args = create_parser().parse_args()
    if parsed_args.mode == "batch":
        run_batch(parsed_args)
    elif parsed_args.mode == "batch-single-category":
        run_batch_single_category(parsed_args)
    elif parsed_args.mode == "batch-source-category":
        run_batch_source_category(parsed_args)
    else:
        run_single(parsed_args)
