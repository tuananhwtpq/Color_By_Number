import os
import json
import sys
import argparse
from collections import Counter
from PIL import Image

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ASSETS_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets"))

def get_dominant_color(ref_pixels, region):
    """
    Tìm màu xuất hiện nhiều nhất trong vùng trên ảnh tham chiếu.
    """
    colors = []
    for x, y in region:
        r, g, b = ref_pixels[x, y][:3]
        colors.append((r, g, b))
    
    # Trả về màu phổ biến nhất (mode)
    most_common = Counter(colors).most_common(1)
    return most_common[0][0]

def rgb_to_hex(rgb):
    return '#{:02x}{:02x}{:02x}'.format(rgb[0], rgb[1], rgb[2])

def color_distance(c1, c2):
    """
    Tính khoảng cách Euclid giữa 2 màu RGB để gộp các màu gần giống nhau.
    """
    return ((c1[0]-c2[0])**2 + (c1[1]-c2[1])**2 + (c1[2]-c2[2])**2)**0.5

def find_next_available_id(output_root, start_id=100001):
    """
    Tìm ID số tiếp theo chưa dùng trong thư mục assets đích.
    """
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

def generate_level_assets(line_art_path, reference_path, output_dir, category_name="", data_name="", generated_id="", color_merge_threshold=15.0):
    print(f"Đang tải ảnh nét vẽ: {line_art_path}")
    line_img = Image.open(line_art_path).convert('RGB')
    
    print(f"Đang tải ảnh tham chiếu màu: {reference_path}")
    ref_img = Image.open(reference_path).convert('RGB')
    
    if line_img.size != ref_img.size:
        print("Lỗi: Kích thước của ảnh nét vẽ và ảnh tham chiếu phải trùng khớp!")
        sys.exit(1)
        
    width, height = line_img.size
    line_pixels = line_img.load()
    ref_pixels = ref_img.load()
    
    # Tạo ảnh mask mới (nền đen ban đầu)
    mask_img = Image.new('RGB', (width, height), (0, 0, 0))
    mask_pixels = mask_img.load()
    
    visited = set()
    regions = []
    
    print("Đang phân tích các vùng trống trên ảnh nét vẽ...")
    
    # Sử dụng thuật toán loang (Flood Fill / BFS) để tìm các mảng màu
    # Ngưỡng độ sáng để nhận diện nét đen (border)
    brightness_threshold = 120 
    
    for y in range(height):
        for x in range(width):
            if (x, y) not in visited:
                r, g, b = line_pixels[x, y]
                brightness = (r + g + b) // 3
                
                # Nếu là pixel trắng (mảng trống cần tô)
                if brightness > brightness_threshold:
                    # Chạy loang tìm toàn bộ vùng
                    queue = [(x, y)]
                    visited.add((x, y))
                    region = []
                    
                    while queue:
                        cx, cy = queue.pop(0)
                        region.append((cx, cy))
                        
                        # Xét 4 hướng lân cận
                        for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                            nx, ny = cx + dx, cy + dy
                            if 0 <= nx < width and 0 <= ny < height:
                                if (nx, ny) not in visited:
                                    nr, ng, nb = line_pixels[nx, ny]
                                    nbright = (nr + ng + nb) // 3
                                    if nbright > brightness_threshold:
                                        visited.add((nx, ny))
                                        queue.append((nx, ny))
                                        
                    if len(region) > 5: # Bỏ qua các mảng nhiễu quá nhỏ (< 5 pixels)
                        regions.append(region)
                        
    print(f"Tìm thấy {len(regions)} vùng mảng màu riêng biệt.")
    
    # Thu thập màu thực tế từ ảnh tham chiếu cho từng vùng
    region_data = []
    unique_target_colors = []
    
    print("Đang lấy màu tham chiếu cho từng vùng...")
    for i, region in enumerate(regions):
        dom_color = get_dominant_color(ref_pixels, region)
        region_data.append({
            "id": i + 1,
            "region": region,
            "target_color": dom_color
        })
        
        # Thêm vào danh sách màu độc bản nếu chưa tồn tại màu tương tự
        is_new_color = True
        for uc in unique_target_colors:
            if color_distance(dom_color, uc) < color_merge_threshold:
                is_new_color = False
                break
        if is_new_color:
            unique_target_colors.append(dom_color)
            
    print(f"Tổng hợp được {len(unique_target_colors)} màu trong Palette sau khi gộp các màu tương đồng.")
    
    # Sắp xếp các màu đích theo tông màu để palette hiển thị đẹp mắt
    # Sắp xếp đơn giản theo độ sáng (brightness) hoặc Hue
    unique_target_colors.sort(key=lambda c: 0.2126*c[0] + 0.7152*c[1] + 0.0722*c[2])
    
    # Tạo mapping từ màu thực tế sang số thứ tự màu trong Palette (1-indexed)
    color_to_number = {}
    for num, color in enumerate(unique_target_colors):
        color_to_number[color] = num + 1
        
    # Tạo thư mục đầu ra nếu chưa có
    os.makedirs(output_dir, exist_ok=True)
    
    # Tạo file mask.png và config.json
    palette_config = []
    
    print("Đang vẽ ảnh Mask và tạo cấu hình Palette...")
    for idx, data in enumerate(region_data):
        region_id = data["id"]
        region = data["region"]
        target_color = data["target_color"]
        
        # Tìm màu palette tương thích nhất
        best_palette_color = min(unique_target_colors, key=lambda c: color_distance(target_color, c))
        palette_number = color_to_number[best_palette_color]
        
        # Mã hóa region_id thành mã màu RGB của ảnh Mask
        # ID 1 -> R=0, G=0, B=1; ID 256 -> R=0, G=1, B=0, v.v.
        mask_r = (region_id >> 16) & 0xFF
        mask_g = (region_id >> 8) & 0xFF
        mask_b = region_id & 0xFF
        mask_rgb = (mask_r, mask_g, mask_b)
        mask_hex = '#{:02x}{:02x}{:02x}'.format(mask_r, mask_g, mask_b)
        
        # Vẽ màu ID này lên các pixel của ảnh Mask
        for x, y in region:
            mask_pixels[x, y] = mask_rgb
            
        palette_config.append({
            "number": palette_number,
            "mask_color": mask_hex,
            "target_color": rgb_to_hex(best_palette_color)
        })
        
    # Lưu ảnh Mask
    mask_out_path = os.path.join(output_dir, "mask.png")
    mask_img.save(mask_out_path)
    print(f"Đã lưu ảnh Mask: {mask_out_path}")
    
    # Lưu ảnh nét vẽ sang thư mục đầu ra
    line_out_path = os.path.join(output_dir, "line.png")
    line_img.save(line_out_path)
    
    # Lưu config.json
    config_data = {
      "id": generated_id if generated_id else os.path.basename(output_dir),
      "name": data_name if data_name else os.path.basename(output_dir),
      "category": category_name if category_name else "Uncategorized",
      "width": width,
      "height": height,
      "palette": palette_config
    }
    
    config_out_path = os.path.join(output_dir, "config.json")
    with open(config_out_path, 'w', encoding='utf-8') as f:
        json.dump(config_data, f, indent=2, ensure_ascii=False)
    print(f"Đã lưu file cấu hình: {config_out_path}")
    print("=== Hoàn tất sinh Asset! ===")

def build_single_output_dir(args):
    if args.output_directory:
        return args.output_directory

    category = args.category or "Uncategorized"
    generated_id = args.generated_id or find_next_available_id(args.output_root)
    return os.path.join(args.output_root, category, generated_id)

def create_parser():
    parser = argparse.ArgumentParser(
        description="Tạo asset level Color By Number từ line art và ảnh màu tham chiếu."
    )
    parser.add_argument(
        "--output-root",
        default=DEFAULT_ASSETS_ROOT,
        help=f"Thư mục gốc assets đầu ra. Mặc định: {DEFAULT_ASSETS_ROOT}"
    )
    parser.add_argument(
        "--merge-threshold",
        type=float,
        default=15.0,
        help="Ngưỡng gộp các màu gần giống nhau."
    )

    subparsers = parser.add_subparsers(dest="mode", required=True)

    single = subparsers.add_parser("single", help="Tạo 1 level từ 2 ảnh.")
    single.add_argument("line_art_path", help="Đường dẫn tới ảnh line art.")
    single.add_argument("reference_colored_path", help="Đường dẫn tới ảnh màu tham chiếu.")
    single.add_argument(
        "--output-directory",
        help="Thư mục level đầu ra cụ thể. Nếu bỏ qua sẽ tự tạo dưới output-root/category/id."
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
        help="ID bắt đầu khi tạo hàng loạt."
    )

    return parser

if __name__ == "__main__":
    parser = create_parser()
    args = parser.parse_args()

    if args.mode == "batch":
        input_root = args.input_root_directory
        output_root = args.output_root

        if not os.path.exists(input_root):
            print(f"Lỗi: Thư mục đầu vào '{input_root}' không tồn tại!")
            sys.exit(1)
            
        print(f"=== BẮT ĐẦU XỬ LÝ HÀNG LOẠT THEO CẤU TRÚC CATEGORY TỪ THƯ MỤC: {input_root} ===")
        
        # Tìm tất cả các level hợp lệ dưới dạng: input_root/Category Name/Data Name
        levels_to_process = []
        
        for category_name in sorted(os.listdir(input_root)):
            category_path = os.path.join(input_root, category_name)
            if os.path.isdir(category_path) and not category_name.startswith('.'):
                for data_name in sorted(os.listdir(category_path)):
                    data_path = os.path.join(category_path, data_name)
                    if os.path.isdir(data_path) and not data_name.startswith('.'):
                        # Tìm file nét vẽ (line) và ảnh màu (ref/color/paint)
                        line_file = None
                        ref_file = None
                        for file_name in os.listdir(data_path):
                            lower_name = file_name.lower()
                            if "line" in lower_name and lower_name.endswith(('.png', '.webp', '.jpg', '.jpeg')):
                                line_file = file_name
                            elif ("ref" in lower_name or "color" in lower_name or "paint" in lower_name) and lower_name.endswith(('.png', '.webp', '.jpg', '.jpeg')):
                                ref_file = file_name
                        
                        if line_file and ref_file:
                            levels_to_process.append({
                                "category": category_name,
                                "name": data_name,
                                "line_path": os.path.join(data_path, line_file),
                                "ref_path": os.path.join(data_path, ref_file)
                            })
                        else:
                            print(f"Bỏ qua '{category_name}/{data_name}': Thiếu file line/color hợp lệ.")
                            
        print(f"Tìm thấy tổng cộng {len(levels_to_process)} tác phẩm nghệ thuật hợp lệ.")
        processed_count = 0
        
        # Đánh số ID tự động tăng dần, bỏ qua các ID đã tồn tại.
        next_id = args.start_id
        
        for level in levels_to_process:
            while os.path.exists(os.path.join(output_root, level["category"], str(next_id))):
                next_id += 1
            generated_id = str(next_id)
            next_id += 1
            category = level["category"]
            name = level["name"]
            
            # Thư mục đầu ra sẽ có cấu trúc: output_root/Category Name/ID
            level_out_dir = os.path.join(output_root, category, generated_id)
            
            print(f"\n[XỬ LÝ - ID: {generated_id}] Category: {category} | Name: {name}")
            try:
                generate_level_assets(
                    level["line_path"],
                    level["ref_path"],
                    level_out_dir,
                    category_name=category,
                    data_name=name,
                    generated_id=generated_id,
                    color_merge_threshold=args.merge_threshold
                )
                processed_count += 1
            except Exception as e:
                print(f"Xảy ra lỗi khi xử lý {category}/{name}: {e}")
                
        print(f"\n=== HOÀN TẤT XỬ LÝ HÀNG LOẠT! Đã tạo thành công {processed_count} tác phẩm. ===")
    else:
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
            color_merge_threshold=args.merge_threshold
        )
