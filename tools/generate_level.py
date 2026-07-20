import argparse
import json
import math
import os
from collections import Counter, deque

from PIL import Image, ImageFilter


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ASSETS_ROOT = os.path.abspath(
    os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets")
)


def rgb_to_hex(rgb):
    return "#{:02x}{:02x}{:02x}".format(rgb[0], rgb[1], rgb[2])


def color_distance(c1, c2):
    return ((c1[0] - c2[0]) ** 2 + (c1[1] - c2[1]) ** 2 + (c1[2] - c2[2]) ** 2) ** 0.5


def quantize_rgb(rgb, step=16):
    return tuple(min(255, int(round(channel / step) * step)) for channel in rgb)


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
    colors = [quantize_rgb(ref_pixels[x, y][:3], quantize_step) for x, y in region]
    return Counter(colors).most_common(1)[0][0]


def cluster_colors(colors, merge_threshold):
    clusters = []
    for color in colors:
        best_idx = None
        best_distance = None
        for idx, cluster in enumerate(clusters):
            distance = color_distance(color, cluster["center"])
            if best_distance is None or distance < best_distance:
                best_idx = idx
                best_distance = distance
        if best_idx is not None and best_distance is not None and best_distance < merge_threshold:
            cluster = clusters[best_idx]
            cluster["colors"].append(color)
            total = len(cluster["colors"])
            cluster["center"] = tuple(
                int(round(sum(component[i] for component in cluster["colors"]) / total))
                for i in range(3)
            )
        else:
            clusters.append({"center": color, "colors": [color]})

    return [tuple(cluster["center"]) for cluster in clusters]


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


def is_tiny_display_region(info, tiny_area_threshold, tiny_side_threshold):
    return info["area"] < tiny_area_threshold or bbox_min_side(info["bbox"]) < tiny_side_threshold


def merge_small_attached_regions(
    region_infos,
    min_region_area,
    attach_distance,
    color_threshold,
    tiny_area_threshold,
    tiny_side_threshold,
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

        grouped_infos.append(
            {
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
):
    print(f"Đang tải ảnh nét vẽ: {line_art_path}")
    line_img, binary_img = load_binary_fill_map(
        line_art_path,
        brightness_threshold=brightness_threshold,
        line_close_radius=line_close_radius,
    )

    print(f"Đang tải ảnh tham chiếu màu: {reference_path}")
    ref_img = Image.open(reference_path).convert("RGB")

    if line_img.size != ref_img.size:
        raise ValueError("Kích thước của ảnh nét vẽ và ảnh tham chiếu phải trùng khớp.")

    width, height = line_img.size
    ref_pixels = ref_img.load()

    mask_img = Image.new("RGB", (width, height), (0, 0, 0))
    mask_pixels = mask_img.load()

    print("Đang phân tích các vùng trống trên ảnh nét vẽ...")
    regions = extract_regions(binary_img)
    raw_region_count = len(regions)
    print(f"Tìm thấy {raw_region_count} vùng mảng màu riêng biệt.")

    print("Đang lấy màu tham chiếu và metadata cho từng vùng...")
    region_infos = []
    target_colors = []
    skipped_noise_regions = 0

    for region in regions:
        area = len(region)
        if area <= 1:
            skipped_noise_regions += 1
            continue

        dominant_color = get_dominant_color(ref_pixels, region)
        bbox = get_region_bbox(region)
        centroid = get_region_centroid(region)
        label_anchor = find_label_anchor(region, bbox, centroid)
        hide_number = area < hide_small_label_threshold or bbox_min_side(bbox) < tiny_region_side_threshold

        region_infos.append(
            {
                "region": region,
                "area": area,
                "bbox": bbox,
                "centroid": centroid,
                "label_anchor": label_anchor,
                "target_color": dominant_color,
                "hide_number": hide_number,
                "is_small_region": area < min_region_area,
                "is_tiny_display_region": area < hide_small_label_threshold or bbox_min_side(bbox) < tiny_region_side_threshold,
            }
        )
        target_colors.append(dominant_color)

    region_infos, merged_small_region_count = merge_small_attached_regions(
        region_infos=region_infos,
        min_region_area=min_region_area,
        attach_distance=small_region_attach_distance,
        color_threshold=max(6.0, color_merge_threshold * 0.65),
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
    )
    target_colors = [info["target_color"] for info in region_infos]

    palette_colors = cluster_colors(target_colors, color_merge_threshold)
    palette_colors.sort(key=lambda color: 0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2])
    color_to_number = {color: index + 1 for index, color in enumerate(palette_colors)}

    os.makedirs(output_dir, exist_ok=True)

    palette_config = []
    region_configs = []

    print("Đang vẽ ảnh Mask và tạo cấu hình Palette...")
    for region_id, info in enumerate(region_infos, start=1):
        region = info["region"]
        target_color = info["target_color"]
        best_palette_color = choose_palette_color(target_color, palette_colors)
        palette_number = color_to_number[best_palette_color]

        mask_r = (region_id >> 16) & 0xFF
        mask_g = (region_id >> 8) & 0xFF
        mask_b = region_id & 0xFF
        mask_rgb = (mask_r, mask_g, mask_b)
        mask_hex = rgb_to_hex(mask_rgb)

        for x, y in region:
            mask_pixels[x, y] = mask_rgb

        palette_config.append(
            {
                "number": palette_number,
                "mask_color": mask_hex,
                "target_color": rgb_to_hex(best_palette_color),
            }
        )

        region_configs.append(
            {
                "mask_color": mask_hex,
                "number": palette_number,
                "target_color": rgb_to_hex(best_palette_color),
                "area": info["area"],
                "bbox": info["bbox"],
                "centroid": info["centroid"],
                "label_anchor": info["label_anchor"],
                "hide_number": info["hide_number"],
            }
        )

    small_regions_count = sum(1 for info in region_infos if info["is_small_region"])
    difficulty = estimate_difficulty(
        total_regions=len(region_configs),
        unique_numbers=len({item["number"] for item in palette_config}),
        small_regions_count=small_regions_count,
    )

    mask_out_path = os.path.join(output_dir, "mask.png")
    mask_img.save(mask_out_path)
    print(f"Đã lưu ảnh Mask: {mask_out_path}")

    line_out_path = os.path.join(output_dir, "line.png")
    line_img.save(line_out_path)
    print(f"Đã lưu ảnh Line: {line_out_path}")

    config_data = {
        "id": generated_id if generated_id else os.path.basename(output_dir),
        "name": data_name if data_name else os.path.basename(output_dir),
        "category": category_name if category_name else "Uncategorized",
        "width": width,
        "height": height,
        "palette": palette_config,
        "regions": region_configs,
        "estimated_difficulty": difficulty,
        "total_regions": len(region_configs),
        "unique_numbers": len({item["number"] for item in palette_config}),
        "small_regions_count": small_regions_count,
        "skipped_noise_regions": skipped_noise_regions,
        "merged_small_regions_count": merged_small_region_count,
    }

    config_out_path = os.path.join(output_dir, "config.json")
    with open(config_out_path, "w", encoding="utf-8") as output_file:
        json.dump(config_data, output_file, indent=2, ensure_ascii=False)
    print(f"Đã lưu file cấu hình: {config_out_path}")
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
