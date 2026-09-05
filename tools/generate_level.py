import argparse
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict, deque

from PIL import Image, ImageChops, ImageFilter, ImageStat
from PIL import ImageDraw

try:
    from asset_quality import (
        calculate_gameplay_metrics,
        evaluate_level_dir,
        merge_quality_report,
        score_quality,
        screen_size_scale_factor,
        canvas_fit_scale,
        REFERENCE_CANVAS_SIZE,
    )
    from playability_profiles import PROFILE_CHOICES, normalize_profile, profile_thresholds
except ImportError:
    from tools.asset_quality import (
        calculate_gameplay_metrics,
        evaluate_level_dir,
        merge_quality_report,
        score_quality,
        screen_size_scale_factor,
        canvas_fit_scale,
        REFERENCE_CANVAS_SIZE,
    )
    from tools.playability_profiles import PROFILE_CHOICES, normalize_profile, profile_thresholds


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ASSETS_ROOT = os.path.abspath(
    os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets")
)
SOURCE_LINE_FORMATS = ("raster", "svg")
DEFAULT_SOURCE_LINE_FORMAT = "raster"
GENERATION_PROFILE_DEFAULTS = {
    "casual": {
        "target_unique_colors": 48,
        "min_region_area": 180,
        "hide_small_label_threshold": 160,
        "tiny_region_side_threshold": 14,
        "tiny_merge_min_area": 80,
        "tiny_merge_min_side": 8,
        "tiny_merge_policy": "relaxed",
    },
    "medium": {
        "target_unique_colors": 64,
        "min_region_area": 120,
        "hide_small_label_threshold": 100,
        "tiny_region_side_threshold": 10,
        "tiny_merge_min_area": 48,
        "tiny_merge_min_side": 6,
        "tiny_merge_policy": "relaxed",
    },
    "hard": {
        "target_unique_colors": 80,
        "min_region_area": 80,
        "hide_small_label_threshold": 60,
        "tiny_region_side_threshold": 8,
        "tiny_merge_min_area": 24,
        "tiny_merge_min_side": 4,
        "tiny_merge_policy": "strict",
    },
    "mandala": {
        "target_unique_colors": 80,
        "min_region_area": 50,
        "hide_small_label_threshold": 40,
        "tiny_region_side_threshold": 7,
        "tiny_merge_min_area": 16,
        "tiny_merge_min_side": 4,
        "tiny_merge_policy": "mandala",
    },
}
DEFAULT_PROFILE_TARGETS = {
    profile: GENERATION_PROFILE_DEFAULTS[normalize_profile(profile)]["target_unique_colors"]
    for profile in PROFILE_CHOICES
}
TINY_MERGE_POLICY_DEFAULTS = {
    "relaxed": {
        "color_threshold_multiplier": 2.2,
        "allow_fallback": True,
    },
    "strict": {
        "color_threshold_multiplier": 1.3,
        "allow_fallback": False,
    },
    "mandala": {
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
        return normalize_profile(requested_profile)
    if category_name and "mandala" in category_name.lower():
        return "mandala"
    return "medium"


def resolve_generation_profile(profile):
    return normalize_profile(profile)


def resolve_target_unique_colors(category_name, requested_profile=None, explicit_target=None):
    profile = infer_category_profile(category_name, requested_profile)
    if explicit_target is not None:
        return profile, max(2, explicit_target)
    return profile, GENERATION_PROFILE_DEFAULTS[profile]["target_unique_colors"]


# Các khoá ngưỡng "vùng nhỏ" theo pixel thô, cần hiệu chỉnh theo độ phân giải ảnh nguồn
# thực tế (screen_scale) để giữ đúng kích thước hiển thị trên màn hình — side-based nhân
# 1 lần theo scale, area-based nhân bình phương (vì area ~ side^2).
# Số pixel mực tối đa được phép "nhìn xuyên qua" khi một vùng không chạm trực tiếp vùng nào
# (xem find_adjacent_region_indices). Đo phân bố bề rộng khe mực giữa 2 vùng trên data thật:
# 4px phủ 78-90% số ranh giới, trong khi nét viền cố ý dày — thứ thật sự nên tách 2 vật thể
# khác nhau — luôn dày hơn mức này.
DEFAULT_INK_GAP_ATTACH_PX = 4

SCREEN_SCALE_SIDE_KEYS = ("tiny_region_side_threshold", "tiny_merge_min_side")
SCREEN_SCALE_AREA_KEYS = ("min_region_area", "hide_small_label_threshold", "tiny_merge_min_area")


def resolve_generation_profile_settings(profile, explicit_target=None, overrides=None, screen_scale=1.0):
    settings = dict(GENERATION_PROFILE_DEFAULTS[resolve_generation_profile(profile)])

    # screen_scale > 1 nghĩa là ảnh nguồn phân giải cao hơn REFERENCE_CANVAS_SIZE (mốc mà
    # các profile default phía trên được tinh chỉnh dựa trên) — chỉ nhân lên (không bao giờ
    # thu nhỏ), và chỉ áp dụng cho giá trị lấy từ profile default, không đụng tới override
    # CLI tường minh (áp dụng bên dưới, luôn ghi đè sau cùng).
    effective_scale = max(1.0, screen_scale or 1.0)
    if effective_scale > 1.0:
        for key in SCREEN_SCALE_SIDE_KEYS:
            settings[key] = int(round(settings[key] * effective_scale))
        for key in SCREEN_SCALE_AREA_KEYS:
            settings[key] = int(round(settings[key] * effective_scale * effective_scale))

    if explicit_target is not None:
        settings["target_unique_colors"] = max(2, explicit_target)
    for key, value in (overrides or {}).items():
        if value is not None:
            settings[key] = value
    return settings


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


DEFAULT_INK_RECOVERY_RADIUS = 1
DEFAULT_INK_MIN_CHROMA_VALUE = 60
DEFAULT_INK_MIN_CHROMA_SPREAD = 18
SOURCE_STROKE_WALL_THRESHOLD = 180


def is_svg_path(path):
    return os.path.splitext(path)[1].lower() == ".svg"


SVG_OPEN_TAG_RE = re.compile(r"<svg\b[^>]*>", re.IGNORECASE | re.DOTALL)
SVG_CLOSE_TAG_RE = re.compile(r"</svg\s*>", re.IGNORECASE)
SVG_ATTR_RE_TEMPLATE = r'\s{attr}\s*=\s*(["\'])(.*?)\1'


def _svg_attr_pattern(attr):
    return re.compile(SVG_ATTR_RE_TEMPLATE.format(attr=re.escape(attr)), re.IGNORECASE | re.DOTALL)


def _read_svg_attr(tag, attr):
    match = _svg_attr_pattern(attr).search(tag)
    return match.group(2) if match else None


def _set_svg_attr(tag, attr, value):
    pattern = _svg_attr_pattern(attr)
    replacement = f' {attr}="{value}"'
    if pattern.search(tag):
        return pattern.sub(replacement, tag, count=1)
    return tag[:-1] + replacement + ">"


def _parse_svg_length(value):
    if value is None:
        return None
    match = re.match(r"\s*([-+]?\d*\.?\d+)", value)
    return float(match.group(1)) if match else None


def _parse_svg_viewbox(value):
    if not value:
        return None
    parts = re.split(r"[\s,]+", value.strip())
    if len(parts) != 4:
        return None
    try:
        min_x, min_y, width, height = (float(part) for part in parts)
    except ValueError:
        return None
    if width <= 0 or height <= 0:
        return None
    return min_x, min_y, width, height


def _format_svg_number(value):
    if abs(value - round(value)) < 0.0001:
        return str(int(round(value)))
    return f"{value:.6f}".rstrip("0").rstrip(".")


NON_VISIBLE_SVG_TAGS = {
    "clipPath",
    "defs",
    "filter",
    "linearGradient",
    "mask",
    "pattern",
    "radialGradient",
    "style",
}
VISIBLE_WHITE_FILL_TAGS = {
    "circle",
    "ellipse",
    "path",
    "polygon",
    "polyline",
    "rect",
}
WHITE_FILL_VALUES = {
    "#fff",
    "#ffffff",
    "rgb(255,255,255)",
    "rgb(255, 255, 255)",
    "white",
}


def _svg_local_name(tag):
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def _svg_style_value(style, attr):
    if not style:
        return None
    for part in style.split(";"):
        if ":" not in part:
            continue
        key, value = part.split(":", 1)
        if key.strip() == attr:
            return value.strip()
    return None


def _svg_attr_or_style(element, attr):
    return element.attrib.get(attr) or _svg_style_value(element.attrib.get("style"), attr)


def _is_white_svg_fill(value):
    return bool(value) and value.strip().lower().replace(" ", "") in {
        color.replace(" ", "") for color in WHITE_FILL_VALUES
    }


def _is_visible_white_fill_element(element):
    if _svg_local_name(element.tag) not in VISIBLE_WHITE_FILL_TAGS:
        return False
    fill = _svg_attr_or_style(element, "fill")
    if not _is_white_svg_fill(fill):
        return False
    stroke = _svg_attr_or_style(element, "stroke")
    return not stroke or stroke.strip().lower() == "none"


def strip_visible_white_fills_from_svg(svg_text):
    """Remove visible white paint from display SVG while preserving defs/clipPath geometry."""
    try:
        ET.register_namespace("", "http://www.w3.org/2000/svg")
        root = ET.fromstring(svg_text)
    except ET.ParseError:
        return svg_text, 0

    removed_count = 0

    def prune(parent, inside_non_visible):
        nonlocal removed_count
        parent_name = _svg_local_name(parent.tag)
        child_inside_non_visible = inside_non_visible or parent_name in NON_VISIBLE_SVG_TAGS
        for child in list(parent):
            if not child_inside_non_visible and _is_visible_white_fill_element(child):
                parent.remove(child)
                removed_count += 1
                continue
            prune(child, child_inside_non_visible)

    prune(root, False)
    return ET.tostring(root, encoding="unicode"), removed_count


def normalize_svg_to_canvas(svg_path, output_path, width, height):
    """Write a display SVG whose coordinate system matches the generated level canvas."""
    with open(svg_path, "r", encoding="utf-8") as input_file:
        svg_text = input_file.read()
    svg_text, removed_white_fill_count = strip_visible_white_fills_from_svg(svg_text)

    open_match = SVG_OPEN_TAG_RE.search(svg_text)
    close_matches = list(SVG_CLOSE_TAG_RE.finditer(svg_text))
    if not open_match or not close_matches:
        raise RuntimeError(f"Không thể normalize SVG không hợp lệ: {svg_path}")

    close_match = close_matches[-1]
    open_tag = open_match.group(0)
    inner_svg = svg_text[open_match.end():close_match.start()]

    old_viewbox = _parse_svg_viewbox(_read_svg_attr(open_tag, "viewBox"))
    if old_viewbox:
        source_min_x, source_min_y, source_width, source_height = old_viewbox
    else:
        source_min_x = 0.0
        source_min_y = 0.0
        source_width = _parse_svg_length(_read_svg_attr(open_tag, "width")) or float(width)
        source_height = _parse_svg_length(_read_svg_attr(open_tag, "height")) or float(height)

    scale_x = width / source_width
    scale_y = height / source_height
    translate_x = -source_min_x
    translate_y = -source_min_y

    normalized_tag = open_tag
    normalized_tag = _set_svg_attr(normalized_tag, "width", str(width))
    normalized_tag = _set_svg_attr(normalized_tag, "height", str(height))
    normalized_tag = _set_svg_attr(normalized_tag, "viewBox", f"0 0 {width} {height}")
    normalized_tag = _set_svg_attr(normalized_tag, "preserveAspectRatio", "none")

    matrix_values = (
        scale_x,
        0.0,
        0.0,
        scale_y,
        translate_x * scale_x,
        translate_y * scale_y,
    )
    transform_attr = ""
    if any(
        abs(actual - expected) > 0.0001
        for actual, expected in zip(matrix_values, (1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
    ):
        transform_attr = (
            ' transform="matrix('
            + " ".join(_format_svg_number(value) for value in matrix_values)
            + ')"'
        )

    prefix = svg_text[:open_match.start()]
    normalized_svg = (
        f"{prefix}{normalized_tag}\n"
        f"<g{transform_attr}>"
        f"{inner_svg}"
        f"</g>\n"
        f"</svg>\n"
    )
    with open(output_path, "w", encoding="utf-8") as output_file:
        output_file.write(normalized_svg)

    return {
        "source_view_box": [
            source_min_x,
            source_min_y,
            source_width,
            source_height,
        ],
        "target_view_box": [0, 0, width, height],
        "scale_x": scale_x,
        "scale_y": scale_y,
        "removed_visible_white_fill_count": removed_white_fill_count,
    }


def rasterize_svg_to_png(svg_path, output_path, width, height):
    try:
        import cairosvg

        cairosvg.svg2png(
            url=svg_path,
            write_to=output_path,
            output_width=width,
            output_height=height,
        )
        return
    except ImportError:
        pass

    rsvg_convert = shutil.which("rsvg-convert")
    if rsvg_convert:
        with open(output_path, "wb") as output_file:
            subprocess.run(
                [rsvg_convert, "-w", str(width), "-h", str(height), svg_path],
                check=True,
                stdout=output_file,
            )
        return

    inkscape = shutil.which("inkscape")
    if inkscape:
        subprocess.run(
            [
                inkscape,
                svg_path,
                "--export-type=png",
                f"--export-filename={output_path}",
                f"--export-width={width}",
                f"--export-height={height}",
            ],
            check=True,
        )
        return

    raise RuntimeError(
        "Không thể rasterize SVG line để sinh mask/regions. "
        "Hãy cài một trong các tool sau rồi chạy lại: "
        "python3 -m pip install cairosvg, hoặc brew install librsvg, hoặc cài Inkscape."
    )


def preserve_source_strokes_as_walls(binary_img, source_gray, stroke_threshold=SOURCE_STROKE_WALL_THRESHOLD):
    """Force visible source-line strokes to remain segmentation walls."""
    stroke_wall = source_gray.point(
        lambda value: 0 if value < stroke_threshold else 255,
        mode="L",
    )
    return ImageChops.darker(binary_img, stroke_wall)


def build_chromatic_mask(
    ref_img,
    min_chroma_value=DEFAULT_INK_MIN_CHROMA_VALUE,
    min_chroma_spread=DEFAULT_INK_MIN_CHROMA_SPREAD,
):
    """Mask 255 tại pixel mà ảnh màu tham chiếu có MÀU THẬT, 0 tại pixel đen/xám trung tính.

    Dùng để phân biệt hai loại pixel mực đen trong line art vốn đang bị đối xử như nhau:
    nét viền thật (bên dưới color.png cũng gần đen/trung tính) và mảng tô đặc như tóc, áo
    (bên dưới color.png là màu có sắc). Chỉ loại thứ hai mới đáng được khôi phục thành
    vùng tô được. Toàn bộ tính bằng phép ảnh của PIL để giữ tốc độ khi chạy data lớn.
    """
    red, green, blue = ref_img.convert("RGB").split()
    brightest = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    darkest = ImageChops.darker(ImageChops.darker(red, green), blue)
    chroma_spread = ImageChops.subtract(brightest, darkest)
    bright_enough = brightest.point(
        lambda value: 255 if value > min_chroma_value else 0, mode="L"
    )
    saturated_enough = chroma_spread.point(
        lambda value: 255 if value > min_chroma_spread else 0, mode="L"
    )
    # multiply hoạt động như phép AND vì hai mask chỉ có giá trị 0 hoặc 255.
    return ImageChops.multiply(bright_enough, saturated_enough)


def build_ink_mask(binary_img):
    return binary_img.point(lambda value: 255 if value == 0 else 0, mode="L")


def count_mask_pixels(mask_img):
    return mask_img.histogram()[255]


def measure_lost_color_pct(binary_img, chromatic_mask):
    """Phần trăm canvas đang bị coi là nét vẽ NHƯNG ảnh gốc có màu thật ở đó.

    Đây là phần màu bị pipeline vứt đi: pixel không thuộc vùng tô nào nên không có số,
    không có detail, và bị render thành đen. Càng thấp càng trung thực với ảnh gốc.
    """
    lost = ImageChops.multiply(build_ink_mask(binary_img), chromatic_mask)
    total_pixels = max(1, binary_img.width * binary_img.height)
    return round(count_mask_pixels(lost) * 100.0 / total_pixels, 2)


def recover_solid_ink_fills(
    binary_img,
    ref_img=None,
    chromatic_mask=None,
    ink_core_radius=DEFAULT_INK_RECOVERY_RADIUS,
    min_chroma_value=DEFAULT_INK_MIN_CHROMA_VALUE,
    min_chroma_spread=DEFAULT_INK_MIN_CHROMA_SPREAD,
):
    """Khôi phục các mảng mực đen ĐẶC trong line art thành vùng tô được.

    Nhiều line art tô hẳn mảng tối (tóc, áo) thành mực đen đặc thay vì chỉ vẽ nét viền.
    Bước nhị phân hoá coi mọi pixel đen là nét vẽ, nên các mảng đó không bao giờ thuộc
    vùng tô nào -> không có số, không có detail -> app render ra đen tuyền, mất sạch màu
    thật của ảnh gốc (ví dụ tóc tím than thành đen). Không ngưỡng sáng nào tách được hai
    loại này vì trong line art chúng có cùng giá trị 0; thông tin phân biệt chỉ nằm ở ảnh
    màu tham chiếu.

    Điều kiện khôi phục: (1) đủ dày — sống sót phép co ảnh, nên nét mảnh bị loại;
    (2) ảnh gốc bên dưới có màu thật — nên nét viền tối trung tính được giữ nguyên.

    Chỉ CO ảnh chứ không giãn lại, nên quanh mỗi mảng khôi phục luôn còn ít nhất một viền
    mực dày `ink_core_radius` — vừa giữ vai trò tường ngăn để các vùng hai bên không bị
    dính vào nhau, vừa trở thành nét viền tự nhiên cho mảng vừa khôi phục.

    Trả về (binary mới, số pixel đã khôi phục).
    """
    if ink_core_radius < 1:
        return binary_img, 0
    if chromatic_mask is None:
        if ref_img is None:
            return binary_img, 0
        chromatic_mask = build_chromatic_mask(
            ref_img,
            min_chroma_value=min_chroma_value,
            min_chroma_spread=min_chroma_spread,
        )

    recovered = build_recovered_ink_mask(binary_img, chromatic_mask, ink_core_radius)
    recovered_pixel_count = count_mask_pixels(recovered) if recovered else 0
    if recovered_pixel_count == 0:
        return binary_img, 0
    return ImageChops.lighter(binary_img, recovered), recovered_pixel_count


def build_recovered_ink_mask(binary_img, chromatic_mask, ink_core_radius):
    """Mặt nạ 255 tại các pixel mực đặc được khôi phục thành vùng tô được.

    Tách riêng khỏi recover_solid_ink_fills vì ảnh nét dùng để HIỂN THỊ cần đúng mặt nạ này:
    chỉ những pixel ở đây mới được làm trắng, còn lại giữ nguyên sắc độ ảnh nét gốc.
    """
    if ink_core_radius < 1 or chromatic_mask is None:
        return None
    ink_core = build_ink_mask(binary_img).filter(
        ImageFilter.MinFilter(ink_core_radius * 2 + 1)
    )
    return ImageChops.multiply(ink_core, chromatic_mask)


def load_binary_fill_map(line_art_path, brightness_threshold, line_close_radius):
    line_img = Image.open(line_art_path).convert("RGB")
    gray = line_img.convert("L")
    binary = gray.point(lambda value: 255 if value > brightness_threshold else 0, mode="L")
    binary = close_small_line_gaps(binary, line_close_radius)
    binary = preserve_source_strokes_as_walls(binary, gray)
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
        "source-line": [
            {"profile": "source-line", "brightness_threshold": 200, "line_close_radius": 0},
            {"profile": "source-line", "brightness_threshold": 220, "line_close_radius": 0},
            {"profile": "source-line", "brightness_threshold": 235, "line_close_radius": 0},
            {"profile": "source-line", "brightness_threshold": 220, "line_close_radius": 1},
        ],
        "poster": [
            {"profile": "poster", "brightness_threshold": 90, "line_close_radius": 1},
            {"profile": "poster", "brightness_threshold": 110, "line_close_radius": 1},
            {"profile": "poster", "brightness_threshold": 130, "line_close_radius": 2},
        ],
    }
    if preprocess_profile == "auto":
        candidates = [base_candidate]
        for name in ["thin-line", "manga", "mandala", "source-line", "poster"]:
            candidates.extend(profiles[name])
        return candidates
    return profiles.get(preprocess_profile, [base_candidate])


def estimate_post_merge_region_stats(
    binary_img,
    ref_img,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
    tiny_merge_color_threshold,
    small_merge_color_threshold,
    tiny_merge_policy,
    profile,
    ink_gap_attach_px=DEFAULT_INK_GAP_ATTACH_PX,
):
    """Ước lượng region_infos SAU khi chạy merge_tiny_regions_into_neighbors +
    merge_small_attached_regions, để candidate scoring nhìn thấy hệ quả gộp vùng
    (mảnh vụn bị dồn cục thành 1 vùng khổng lồ) thay vì chỉ nhìn kết quả flood-fill thô.

    Chạy ở chế độ nhẹ: bỏ qua find_label_anchor (chỉ ảnh hưởng hiển thị, không ảnh
    hưởng area/largest_region_pct) và dùng representative color rẻ hơn (dominant +
    quantize) để giữ tốc độ chấp nhận được khi phải lặp qua nhiều candidate.
    """
    regions = extract_regions(binary_img)
    total_pixels = max(1, binary_img.width * binary_img.height)
    ref_pixels = ref_img.load()

    region_infos = []
    for region in regions:
        area = len(region)
        if area <= 1:
            continue
        representative_color = get_dominant_color(ref_pixels, region, quantize_step=16)
        bbox = get_region_bbox(region)
        centroid = get_region_centroid(region)
        region_infos.append(
            update_region_classification(
                info={
                    "region": region,
                    "area": area,
                    "bbox": bbox,
                    "centroid": centroid,
                    "label_anchor": centroid,
                    "target_color": representative_color,
                    "representative_color": representative_color,
                    "hide_number": area < tiny_area_threshold or bbox_min_side(bbox) < tiny_side_threshold,
                    "merged_region_count": 1,
                },
                min_region_area=min_region_area,
                tiny_area_threshold=tiny_area_threshold,
                tiny_side_threshold=tiny_side_threshold,
                tiny_merge_min_area=tiny_merge_min_area,
                tiny_merge_min_side=tiny_merge_min_side,
            )
        )

    region_infos, *_ = merge_tiny_regions_into_neighbors(
        region_infos=region_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=tiny_area_threshold,
        tiny_side_threshold=tiny_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        attach_distance=ink_gap_attach_px,
        color_threshold=tiny_merge_color_threshold,
        tiny_merge_policy=tiny_merge_policy,
        profile=profile,
        total_pixels=total_pixels,
        skip_label_anchor=True,
    )
    region_infos, _ = merge_small_attached_regions(
        region_infos=region_infos,
        min_region_area=min_region_area,
        attach_distance=ink_gap_attach_px,
        color_threshold=small_merge_color_threshold,
        tiny_area_threshold=tiny_area_threshold,
        tiny_side_threshold=tiny_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        profile=profile,
        total_pixels=total_pixels,
        skip_label_anchor=True,
    )
    return region_infos, total_pixels


def score_preprocessing_candidate(
    binary_img,
    playability_profile="standard",
    ref_img=None,
    merge_settings=None,
):
    total_pixels = max(1, binary_img.width * binary_img.height)
    if ref_img is not None and merge_settings is not None:
        region_infos, total_pixels = estimate_post_merge_region_stats(
            binary_img=binary_img,
            ref_img=ref_img,
            **merge_settings,
        )
        region_areas = [info["area"] for info in region_infos]
        evaluation_mode = "pre_generation_proxy_post_merge"
    else:
        regions = extract_regions(binary_img)
        region_areas = [len(region) for region in regions if len(region) > 1]
        evaluation_mode = "pre_generation_proxy"

    total_regions = len(region_areas)
    largest_pct = max(region_areas, default=0) * 100.0 / total_pixels
    tiny_regions = sum(1 for area in region_areas if area < 50)
    gameplay_metrics = calculate_gameplay_metrics(
        total_pixels=total_pixels,
        region_areas=region_areas,
        color_areas=None,
        profile=playability_profile,
    )
    proxy_gameplay_metrics = {
        **gameplay_metrics,
        "estimated_hidden_label_pct": None,
        "hidden_label_pct": None,
    }

    metrics = {
        "total_regions": total_regions,
        "unique_numbers": 0,
        "largest_region_pct": round(largest_pct, 2),
        "tiny_region_count_lt_50": tiny_regions,
        "mask_config_mismatch_count": 0,
        **proxy_gameplay_metrics,
    }
    quality = score_quality(metrics)
    return {
        "score": quality["quality_score"],
        "evaluation_mode": evaluation_mode,
        "candidate_proxy_score": quality["quality_score"],
        "candidate_proxy_grade": quality["quality_grade"],
        "candidate_playable_score": proxy_gameplay_metrics["playable_score"],
        "quality_score": quality["quality_score"],
        "quality_grade": quality["quality_grade"],
        "fail_reasons": quality["fail_reasons"],
        "warnings": quality["warnings"],
        "total_regions": total_regions,
        "largest_region_pct": round(largest_pct, 2),
        "top_3_region_pct": proxy_gameplay_metrics["top_3_region_pct"],
        "playable_region_count": proxy_gameplay_metrics["playable_region_count"],
        "giant_region_count": proxy_gameplay_metrics["giant_region_count"],
        "single_tap_completion_risk": proxy_gameplay_metrics["single_tap_completion_risk"],
        "playable_score": proxy_gameplay_metrics["playable_score"],
        "tiny_region_count_lt_50": tiny_regions,
        "preview_mae": None,
        "preview_mae_reason": "not_available_before_full_generation",
    }


def preprocessing_candidate_sort_key(item):
    """Thứ tự ưu tiên khi chọn candidate tiền xử lý.

    Gameplay (playable_score, quality_score) quyết định trước. Không dùng `lost_color_pct`
    để phá hoà nữa: tiêu chí đó từng kéo candidate về ngưỡng quá thấp và biến nét xám của
    ảnh gốc thành vùng tô, gây lỗi tô tràn qua vùng chưa khoanh.
    """
    return (
        item.get("playable_score", item.get("candidate_playable_score", 0)),
        item.get("quality_score", item.get("score", 0)),
        -abs(item.get("largest_region_pct", 0) - 35.0),
        item.get("total_regions", 0),
    )


def select_preprocessing_candidate(candidates):
    return max(candidates, key=preprocessing_candidate_sort_key)


# preprocess_profile="auto" thử ~17 candidate. Chấm điểm post-merge (mô phỏng
# merge_tiny_regions_into_neighbors + merge_small_attached_regions) tốn kém hơn flood-fill
# thô rất nhiều — với line art vỡ thành hàng nghìn mảnh vụn (vd ảnh nét mảnh/đứt quãng),
# chi phí này nhân với ~17 candidate khiến 1 lần generate rất chậm. Chỉ rescoring cho 1
# shortlist các candidate hứa hẹn nhất theo điểm rẻ (raw flood-fill), thay vì toàn bộ.
POST_MERGE_CANDIDATE_SHORTLIST_SIZE = 6


def select_binary_fill_map(
    line_art_path,
    brightness_threshold,
    line_close_radius,
    preprocess_profile,
    playability_profile="standard",
    ref_img=None,
    merge_settings=None,
    ink_core_radius=DEFAULT_INK_RECOVERY_RADIUS,
    min_chroma_value=DEFAULT_INK_MIN_CHROMA_VALUE,
    min_chroma_spread=DEFAULT_INK_MIN_CHROMA_SPREAD,
):
    line_img = Image.open(line_art_path).convert("RGB")
    gray = line_img.convert("L")
    # Kiểm tra ngay tại đây, trước mọi phép ảnh kết hợp line + reference: các phép này đòi
    # hai ảnh cùng kích thước, nếu không sẽ ném lỗi PIL khó hiểu. Khi chạy batch data lớn,
    # cặp ảnh lệch kích thước là lỗi input thường gặp nên cần báo rõ ràng.
    if ref_img is not None and ref_img.size != line_img.size:
        raise ValueError(
            "Kích thước của ảnh nét vẽ và ảnh tham chiếu phải trùng khớp "
            f"(line={line_img.size}, reference={ref_img.size})."
        )
    # Tính một lần cho audit metadata. Không dùng để khôi phục mực vào segmentation nữa:
    # ink recovery từng biến nét gốc thành vùng tô và gây tô tràn qua vùng chưa khoanh.
    chromatic_mask = (
        None
        if ref_img is None
        else build_chromatic_mask(
            ref_img,
            min_chroma_value=min_chroma_value,
            min_chroma_spread=min_chroma_spread,
        )
    )
    cheap_candidates = []
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
        binary = preserve_source_strokes_as_walls(binary, gray)
        recovered_ink_pixel_count = 0
        recovered_mask = None
        cheap_report = score_preprocessing_candidate(binary, playability_profile=playability_profile)
        cheap_candidates.append({
            **candidate,
            **cheap_report,
            "raw_total_regions": cheap_report["total_regions"],
            "recovered_ink_pixel_count": recovered_ink_pixel_count,
            # Ảnh, không phải dữ liệu báo cáo — bị lọc khỏi selected_report bên dưới.
            "recovered_mask": recovered_mask,
            "lost_color_pct": (
                None if chromatic_mask is None else measure_lost_color_pct(binary, chromatic_mask)
            ),
            "binary": binary,
        })

    use_post_merge_scoring = ref_img is not None and merge_settings is not None
    if use_post_merge_scoring:
        # Winner chỉ được chọn trong nhóm ĐÃ rescoring post-merge — không bao giờ để 1
        # candidate ngoài shortlist "thắng" nhờ điểm rẻ bị lạc quan giả (đây chính là bug
        # đã fix ở estimate_post_merge_region_stats: flood-fill thô trông tốt vì tạo nhiều
        # mảnh vụn nhỏ, nhưng bước gộp phía sau lại dồn cục chúng thành 1 vùng khổng lồ).
        shortlist = sorted(
            cheap_candidates,
            key=preprocessing_candidate_sort_key,
            reverse=True,
        )[:POST_MERGE_CANDIDATE_SHORTLIST_SIZE]
        scored_candidates = [
            {
                **candidate,
                **score_preprocessing_candidate(
                    candidate["binary"],
                    playability_profile=playability_profile,
                    ref_img=ref_img,
                    merge_settings=merge_settings,
                ),
                "raw_total_regions": candidate["raw_total_regions"],
            }
            for candidate in shortlist
        ]
    else:
        scored_candidates = cheap_candidates

    selected = select_preprocessing_candidate(scored_candidates)
    image_keys = ("binary", "recovered_mask")
    selected_report = {
        key: value for key, value in selected.items() if key not in image_keys
    }
    selected_report["candidate_count"] = len(cheap_candidates)
    selected_report["candidate_rescored_count"] = len(scored_candidates)
    selected_report["candidate_top"] = [
        {key: value for key, value in candidate.items() if key not in image_keys}
        for candidate in sorted(
            scored_candidates,
            key=preprocessing_candidate_sort_key,
            reverse=True,
        )[:3]
    ]
    return line_img, selected["binary"], selected.get("recovered_mask"), selected_report


def make_runtime_preprocessing_report(selected_preprocessing):
    return {
        key: selected_preprocessing[key]
        for key in ["profile", "brightness_threshold", "line_close_radius"]
        if key in selected_preprocessing
    }


def make_segmentation_line_report(selected_preprocessing):
    return {
        "mode": "selected_preprocessing_candidate",
        "selected_profile": selected_preprocessing.get("profile"),
        "brightness_threshold": selected_preprocessing.get("brightness_threshold"),
        "line_close_radius": selected_preprocessing.get("line_close_radius"),
        "evaluation_mode": selected_preprocessing.get("evaluation_mode"),
        "quality_score": selected_preprocessing.get("quality_score")
        or selected_preprocessing.get("score"),
        "playable_score": selected_preprocessing.get("playable_score")
        or selected_preprocessing.get("candidate_playable_score"),
        "raw_total_regions": selected_preprocessing.get("raw_total_regions"),
        "estimated_final_regions": selected_preprocessing.get("total_regions"),
        "largest_region_pct": selected_preprocessing.get("largest_region_pct"),
        "candidate_count": selected_preprocessing.get("candidate_count"),
        "candidate_rescored_count": selected_preprocessing.get("candidate_rescored_count"),
        "lost_color_pct": selected_preprocessing.get("lost_color_pct"),
        "recovered_ink_pixel_count": selected_preprocessing.get("recovered_ink_pixel_count"),
    }


def make_line_output_image(binary_img):
    return binary_img.convert("RGB")


def build_render_line_image(source_line_img, binary_img):
    """Ảnh nét dùng để dựng detail.png và preview_colored.png.

    Giữ sắc độ xám/anti-alias của nét gốc ở chỗ VẪN là mực, nhưng không bao giờ làm tối
    pixel đã được xếp là vùng tô được. Nếu dùng thẳng ảnh nét gốc, các mảng mực đặc vừa
    được ink recovery khôi phục (tóc, áo) sẽ bị bôi đen trở lại trên preview, và
    make_detail_overlay_image cũng bỏ qua chúng vì tưởng là nét — khiến vùng khôi phục chỉ
    có màu palette phẳng chứ không có màu chuẩn của ảnh gốc.

    Đây cũng là mô hình đúng với app: app nhân với line.png (chính là bản đồ nhị phân), nên
    pixel tô được không hề bị nét làm tối.
    """
    # lighter = max: binary=255 -> 255 (không làm tối); binary=0 -> giữ giá trị nét gốc.
    return ImageChops.lighter(source_line_img.convert("L"), binary_img).convert("RGB")


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


def reclaim_non_ink_pixels_into_regions(regions, width, height, source_gray, brightness_threshold):
    """Trả các pixel KHÔNG phải nét mực về cho vùng liền kề gần nhất.

    close_small_line_gaps() (MinFilter+MaxFilter) hi sinh một dải pixel giấy trắng để vá khe
    hở line art khi xác định ranh giới vùng — đúng mục đích, nhưng hệ quả là các pixel đó
    không thuộc vùng nào. Chúng giữ nguyên màu trắng trong make_flat_colored_image() và chỉ
    bị apply_line_overlay() nhân với độ sáng gốc (~243/255), nên hiện ra thành VỆT TRẮNG dọc
    ranh giới trên preview_colored.png, debug_regions.png và full-preview trong app.

    Sau khi ranh giới đã được chốt, pixel nào có độ sáng gốc > ngưỡng nhị phân (tức là giấy,
    không phải mực) sẽ được BFS đa nguồn gán về vùng gần nhất. Nét mực thật (<= ngưỡng) giữ
    nguyên là nét, không bị tô lấn.
    """
    owner = [-1] * (width * height)
    queue = deque()
    for region_idx, region in enumerate(regions):
        for x, y in region:
            index = y * width + x
            owner[index] = region_idx
            queue.append((x, y))

    gray_pixels = source_gray.load()
    reclaimed = [[] for _ in regions]

    def is_ink(x, y):
        return gray_pixels[x, y] <= brightness_threshold

    # Lượt 1 — chỉ lan qua giấy: xử lý dải pixel bị bào mòn dọc ranh giới, chiếm đa số.
    while queue:
        cx, cy = queue.popleft()
        region_idx = owner[cy * width + cx]
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            nx = cx + dx
            ny = cy + dy
            if not (0 <= nx < width and 0 <= ny < height):
                continue
            n_index = ny * width + nx
            if owner[n_index] != -1 or is_ink(nx, ny):
                continue
            owner[n_index] = region_idx
            reclaimed[region_idx].append((nx, ny))
            queue.append((nx, ny))

    # Lượt 2 — các "túi" giấy bị nét mực bao kín hoàn toàn: không có hàng xóm nào thuộc vùng
    # nào nên lượt 1 không với tới. Lan tiếp XUYÊN QUA nét mực để tìm vùng gần nhất, nhưng
    # chỉ GÁN pixel giấy; nét mực vẫn giữ nguyên là ranh giới, không bị tô lấn.
    has_unreached_paper = any(
        owner[y * width + x] == -1 and not is_ink(x, y)
        for y in range(height)
        for x in range(width)
    )
    if has_unreached_paper:
        route = list(owner)
        queue = deque(
            (index % width, index // width)
            for index, region_idx in enumerate(owner)
            if region_idx != -1
        )
        while queue:
            cx, cy = queue.popleft()
            region_idx = route[cy * width + cx]
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx = cx + dx
                ny = cy + dy
                if not (0 <= nx < width and 0 <= ny < height):
                    continue
                n_index = ny * width + nx
                if route[n_index] != -1:
                    continue
                route[n_index] = region_idx
                if not is_ink(nx, ny):
                    owner[n_index] = region_idx
                    reclaimed[region_idx].append((nx, ny))
                queue.append((nx, ny))

    reclaimed_count = sum(len(points) for points in reclaimed)
    if reclaimed_count:
        for region_idx, points in enumerate(reclaimed):
            regions[region_idx].extend(points)
    return regions, reclaimed_count


def get_region_bbox(region):
    xs = [point[0] for point in region]
    ys = [point[1] for point in region]
    return {
        "left": min(xs),
        "top": min(ys),
        "right": max(xs),
        "bottom": max(ys),
    }


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
        "casual": 1.0,
        "medium": 1.0,
        "hard": 0.92,
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


# Bán kính an toàn (label_anchor["radius"]) dưới ngưỡng này thì KHÔNG CÓ ZOOM NÀO trong app
# đủ để đọc được số — khớp với PaintCanvasView.kt: MIN_SCREEN_RADIUS_TO_SHOW_LABEL=25f (px
# màn hình tối thiểu để hiện số) / MAX_ZOOM=20f (zoom tối đa) = 1.25px, cộng biên an toàn.
# Những vùng dưới ngưỡng này phải bị ẩn số VÀ gộp bắt buộc vào láng giềng (xem
# force_merge_unreadable_regions) — chỉ ẩn số thôi vẫn để lại 1 vùng tap được nhưng user
# không biết là màu/số gì.
LABEL_UNREADABLE_RADIUS_PX = 1.5
DEFAULT_LABEL_GATE_MAX_HIDDEN_PCT = 80.0
PROTECTED_DETAIL_MIN_AREA = 20
PROTECTED_DETAIL_MAX_AREA = 600
PROTECTED_DETAIL_MAX_AREA_PCT = 1.0
PROTECTED_DETAIL_MAX_SIDE = 40
PROTECTED_DETAIL_COLOR_DISTANCE = 45.0
NON_MEANINGFUL_MERGE_MAX_THICKNESS = 2.5


# Chi phí bước đi của chamfer distance transform, xấp xỉ khoảng cách Euclid bằng số
# nguyên: đi ngang/dọc tốn 3, đi chéo tốn 4 (4/3 = 1.333, xấp xỉ căn 2 = 1.414). Chia lại
# cho 3 ở cuối để ra khoảng cách tính theo pixel.
CHAMFER_ORTHOGONAL_COST = 3
CHAMFER_DIAGONAL_COST = 4


def find_label_anchor(region, bbox, centroid):
    """Đặt số tại điểm RỘNG RÃI NHẤT của vùng (pole of inaccessibility), kèm bán kính
    chỗ trống thật tại đó.

    Cách cũ chấm điểm ứng viên bằng TỔNG độ dài 8 tia rồi lại báo cáo radius bằng MIN của
    chính 8 tia đó — hai hàm mục tiêu ngược nhau. Điểm nằm giữa một dải dài hẹp có tổng
    rất lớn (2 tia dọc trục dài mênh mông) nhưng min gần 0, nên thuật toán chủ động chọn
    đúng chỗ tệ nhất để đặt số rồi báo radius của chỗ tệ đó. Đo trên data thật: 12.4% vùng
    bị báo thiếu radius quá 2 lần, cá biệt vùng 39k pixel có radius thật 55 bị báo là 1.0.
    Vì app dùng chính con số này để quyết định hiện số (PaintCanvasView:
    radius * scaleFactor >= 25) nên sai số đó đẩy thẳng vào trải nghiệm: số hiện muộn hơn
    mức cần thiết, hoặc không bao giờ hiện.

    Ở đây dùng distance transform: tính một lần khoảng cách từ MỌI pixel trong vùng tới
    biên gần nhất, rồi lấy điểm xa biên nhất. Đó đúng là tâm hình tròn nội tiếp lớn nhất —
    vừa là chỗ đặt số hợp lý nhất, vừa cho radius nhất quán với điểm được chọn. Chamfer 2
    lượt quét nên chi phí O(diện tích bbox) thay vì O(số điểm mẫu × độ dài tia).

    radius giữ nguyên ĐƠN VỊ CŨ ("số pixel trống quanh điểm", pixel sát biên = 0) để mọi
    ngưỡng đã tinh chỉnh theo nó — kể cả MIN_SCREEN_RADIUS_TO_SHOW_LABEL bên Kotlin — không
    phải hiệu chỉnh lại.
    """
    left = bbox["left"]
    top = bbox["top"]
    # Đệm 1 pixel mỗi phía: viền đệm giữ giá trị 0 nên tự đóng vai trò "bên ngoài vùng",
    # khỏi phải kiểm tra biên trong vòng lặp nóng.
    grid_width = bbox["right"] - left + 3
    grid_height = bbox["bottom"] - top + 3
    unset = 1 << 28

    distances = [0] * (grid_width * grid_height)
    # Mỗi hàng chỉ quét đúng đoạn có pixel của vùng — vùng cong/chéo có bbox lớn hơn hẳn
    # diện tích thật, quét cả bbox sẽ phí.
    row_start = [grid_width] * grid_height
    row_end = [-1] * grid_height
    for x, y in region:
        gx = x - left + 1
        gy = y - top + 1
        distances[gy * grid_width + gx] = unset
        if gx < row_start[gy]:
            row_start[gy] = gx
        if gx > row_end[gy]:
            row_end[gy] = gx

    for gy in range(1, grid_height - 1):
        if row_end[gy] < 0:
            continue
        base = gy * grid_width
        above = base - grid_width
        for gx in range(row_start[gy], row_end[gy] + 1):
            index = base + gx
            if not distances[index]:
                continue
            best = distances[above + gx - 1] + CHAMFER_DIAGONAL_COST
            candidate = distances[above + gx] + CHAMFER_ORTHOGONAL_COST
            if candidate < best:
                best = candidate
            candidate = distances[above + gx + 1] + CHAMFER_DIAGONAL_COST
            if candidate < best:
                best = candidate
            candidate = distances[index - 1] + CHAMFER_ORTHOGONAL_COST
            if candidate < best:
                best = candidate
            if best < distances[index]:
                distances[index] = best

    for gy in range(grid_height - 2, 0, -1):
        if row_end[gy] < 0:
            continue
        base = gy * grid_width
        below = base + grid_width
        for gx in range(row_end[gy], row_start[gy] - 1, -1):
            index = base + gx
            if not distances[index]:
                continue
            best = distances[below + gx + 1] + CHAMFER_DIAGONAL_COST
            candidate = distances[below + gx] + CHAMFER_ORTHOGONAL_COST
            if candidate < best:
                best = candidate
            candidate = distances[below + gx - 1] + CHAMFER_DIAGONAL_COST
            if candidate < best:
                best = candidate
            candidate = distances[index + 1] + CHAMFER_ORTHOGONAL_COST
            if candidate < best:
                best = candidate
            if best < distances[index]:
                distances[index] = best

    # Vùng phẳng thường có cả một mảng điểm cùng khoảng cách lớn nhất; chọn điểm gần trọng
    # tâm nhất trong số đó để số không nhảy sang một bên khi vùng được gộp thêm.
    centroid_x = centroid["x"] - left + 1
    centroid_y = centroid["y"] - top + 1
    best_distance = -1
    best_penalty = 0.0
    best_point = region[0]
    for x, y in region:
        gx = x - left + 1
        gy = y - top + 1
        distance = distances[gy * grid_width + gx]
        if distance < best_distance:
            continue
        penalty = abs(gx - centroid_x) + abs(gy - centroid_y)
        if distance > best_distance or penalty < best_penalty:
            best_distance = distance
            best_penalty = penalty
            best_point = (x, y)

    radius = max(0.0, best_distance / float(CHAMFER_ORTHOGONAL_COST) - 1.0)
    return {"x": float(best_point[0]), "y": float(best_point[1]), "radius": radius}


def build_display_line_image(source_line_img, recovered_ink_mask):
    """Ảnh nét mà APP nhân lên khi chơi.

    Bắt đầu và kết thúc bằng ĐÚNG ảnh nét gốc. Ink recovery đã bị tắt khỏi pipeline vì nó
    từng xoá nét gốc khỏi line hiển thị và biến pixel nét thành vùng tô. Tham số
    recovered_ink_mask chỉ còn để giữ tương thích với test/call-site cũ.
    """
    _ = recovered_ink_mask
    return source_line_img.convert("RGB")


DISPLAY_LINE_CHANGED_PIXEL_THRESHOLD = 10
DISPLAY_LINE_CHANGED_PCT_FALLBACK_THRESHOLD = 10.0
DISPLAY_LINE_MAE_FALLBACK_THRESHOLD = 12.0
DISPLAY_LINE_STROKE_LIGHTENED_PCT_FALLBACK_THRESHOLD = 10.0
INK_RECOVERY_OVERREACH_RECOVERED_PCT_THRESHOLD = 12.0


def measure_line_difference(source_line_img, candidate_line_img, changed_threshold=DISPLAY_LINE_CHANGED_PIXEL_THRESHOLD):
    source = source_line_img.convert("L")
    candidate = candidate_line_img.convert("L")
    if candidate.size != source.size:
        candidate = candidate.resize(source.size, Image.Resampling.BILINEAR)
    diff = ImageChops.difference(source, candidate)
    stat = ImageStat.Stat(diff)
    changed_count = sum(1 for value in diff.getdata() if value > changed_threshold)
    total = max(1, diff.width * diff.height)
    return {
        "mae": round(stat.mean[0], 2),
        "changed_pct": round(changed_count * 100.0 / total, 2),
    }


def measure_stroke_preservation(
    source_line_img,
    candidate_line_img,
    stroke_threshold=245,
    lightened_threshold=DISPLAY_LINE_CHANGED_PIXEL_THRESHOLD,
):
    """Measure display-line damage only on pixels that belong to the source strokes."""
    source = source_line_img.convert("L")
    candidate = candidate_line_img.convert("L")
    if candidate.size != source.size:
        candidate = candidate.resize(source.size, Image.Resampling.BILINEAR)

    source_pixels = source.load()
    candidate_pixels = candidate.load()
    stroke_count = 0
    lightened_count = 0
    total_lightened = 0
    for y in range(source.height):
        for x in range(source.width):
            source_value = source_pixels[x, y]
            if source_value >= stroke_threshold:
                continue
            stroke_count += 1
            delta = candidate_pixels[x, y] - source_value
            if delta > lightened_threshold:
                lightened_count += 1
                total_lightened += delta

    if stroke_count == 0:
        return {
            "stroke_pixel_count": 0,
            "stroke_lightened_count": 0,
            "stroke_lightened_pct": 0.0,
            "stroke_lightened_mae": 0.0,
        }

    return {
        "stroke_pixel_count": stroke_count,
        "stroke_lightened_count": lightened_count,
        "stroke_lightened_pct": round(lightened_count * 100.0 / stroke_count, 2),
        "stroke_lightened_mae": round(total_lightened / stroke_count, 2),
    }


def build_safe_display_line_image(
    source_line_img,
    candidate_line_img,
    changed_pct_threshold=DISPLAY_LINE_CHANGED_PCT_FALLBACK_THRESHOLD,
    mae_threshold=DISPLAY_LINE_MAE_FALLBACK_THRESHOLD,
    stroke_lightened_pct_threshold=DISPLAY_LINE_STROKE_LIGHTENED_PCT_FALLBACK_THRESHOLD,
):
    """Return the UX-facing display line and a report describing any fallback.

    `candidate_line_img` is the legacy recovered-ink display line. It is useful when it only
    removes true solid fill ink, but harmful when recovery eats thin strokes/texture. The safe
    display line keeps the candidate only while it remains close to the source line; otherwise
    it falls back to the exact source art so user-facing strokes stay crisp.
    """
    diff = measure_line_difference(source_line_img, candidate_line_img)
    stroke_report = measure_stroke_preservation(source_line_img, candidate_line_img)
    stroke_guard_failed = (
        stroke_report["stroke_lightened_pct"] > stroke_lightened_pct_threshold
    )
    fallback = (
        diff["changed_pct"] > changed_pct_threshold
        or diff["mae"] > mae_threshold
        or stroke_guard_failed
    )
    reason = "within_threshold"
    if diff["changed_pct"] > changed_pct_threshold or diff["mae"] > mae_threshold:
        reason = "over_threshold"
    elif stroke_guard_failed:
        reason = "stroke_guard_failed"
    report = {
        "candidate_mae": diff["mae"],
        "candidate_changed_pct": diff["changed_pct"],
        "changed_pct_threshold": changed_pct_threshold,
        "mae_threshold": mae_threshold,
        "stroke_lightened_pct_threshold": stroke_lightened_pct_threshold,
        **stroke_report,
        "stroke_guard_failed": stroke_guard_failed,
        "fallback_to_source": fallback,
        "selected": "source_line" if fallback else "recovered_ink_candidate",
        "reason": reason,
        "ink_recovery_overreach": fallback,
    }
    if fallback:
        return source_line_img.convert("RGB"), report
    return candidate_line_img.convert("RGB"), report


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


def build_fill_coverage_image(
    width,
    height,
    mask_img,
    display_line_img,
    expansion_radius=2,
    line_halo_radius=2,
    line_threshold=250,
):
    """Build a display-only mask that lets color tuck under/near strokes.

    `mask.png` remains the gameplay source of truth. This extra map copies each mask color
    into a tiny candidate band around that mask only when the pixel is close to visible line
    art. Android can then color those pixels after a region is completed, hiding white pinholes
    near strokes without making touch regions larger.
    """
    mask_rgb = mask_img.convert("RGB")
    mask_gray = mask_img.convert("L").point(lambda value: 255 if value > 0 else 0, "L")
    dilated_mask = mask_gray.filter(ImageFilter.MaxFilter(expansion_radius * 2 + 1))
    edge_band = ImageChops.subtract(dilated_mask, mask_gray)

    line_mask = display_line_img.convert("L").point(
        lambda value: 255 if value < line_threshold else 0,
        "L",
    )
    line_halo = line_mask.filter(ImageFilter.MaxFilter(line_halo_radius * 2 + 1))
    candidate_band = ImageChops.multiply(edge_band, line_halo)

    mask_pixels = mask_rgb.load()
    candidate_pixels = candidate_band.load()
    coverage = Image.new("RGB", (width, height), (0, 0, 0))
    coverage_pixels = coverage.load()
    queue = deque()
    assigned_count = 0
    candidate_count = 0

    for y in range(height):
        for x in range(width):
            color = mask_pixels[x, y]
            if color != (0, 0, 0):
                coverage_pixels[x, y] = color
                queue.append((x, y))
            elif candidate_pixels[x, y] > 0:
                candidate_count += 1

    directions = (
        (-1, 0),
        (1, 0),
        (0, -1),
        (0, 1),
        (-1, -1),
        (1, -1),
        (-1, 1),
        (1, 1),
    )
    while queue:
        x, y = queue.popleft()
        color = coverage_pixels[x, y]
        for dx, dy in directions:
            nx = x + dx
            ny = y + dy
            if nx < 0 or ny < 0 or nx >= width or ny >= height:
                continue
            if candidate_pixels[nx, ny] == 0:
                continue
            if coverage_pixels[nx, ny] != (0, 0, 0):
                continue
            coverage_pixels[nx, ny] = color
            assigned_count += 1
            queue.append((nx, ny))

    report = {
        "fill_coverage_candidate_pixels": candidate_count,
        "fill_coverage_extra_pixels": assigned_count,
        "fill_coverage_extra_pct": round(assigned_count * 100.0 / max(1, width * height), 3),
        "fill_coverage_expansion_radius": expansion_radius,
        "fill_coverage_line_halo_radius": line_halo_radius,
        "fill_coverage_line_threshold": line_threshold,
    }
    return coverage, report


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
            # Mẫu số càng lớn thì lệch màu VỪA PHẢI (không chỉ lệch cực đoan) càng ít được
            # kéo về màu gốc — 96.0 (cũ) khiến phần lớn pixel chỉ đạt 1 phần nhỏ max_alpha dù
            # đã lệch đáng kể. Hạ xuống 45.0 (đo thật: MAE preview_colored.png so với ảnh gốc
            # giảm thêm so với chỉ tăng detail_alpha) để bão hoà sớm hơn, giữ preview gần màu
            # gốc hơn ở vùng shading/gradient — không đụng ranh giới vùng/mask/line.
            alpha = int(round(max_alpha * min(1.0, diff / 45.0)))
            if alpha <= 0:
                continue
            detail_pixels[x, y] = (reference[0], reference[1], reference[2], alpha)

    return detail


def composite_detail_over_flat(flat_preview_img, detail_img):
    if detail_img is None:
        return flat_preview_img.copy()
    return Image.alpha_composite(flat_preview_img.convert("RGBA"), detail_img).convert("RGB")


def make_preview_colored_image(
    width,
    height,
    mask_img,
    line_img,
    mask_to_target_rgb,
    detail_img=None,
    fill_coverage_img=None,
):
    flat_preview = make_flat_colored_image(
        width,
        height,
        fill_coverage_img if fill_coverage_img is not None else mask_img,
        mask_to_target_rgb,
    )
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


def region_thickness_diameter(info):
    """Bề ngang THẬT của vùng tại chỗ rộng nhất, quy về cùng đơn vị với bbox_min_side.

    bbox_min_side mù với hình dạng: một vòng cung dày 5px có bbox 60x40 nên bị chấm là
    "cạnh nhỏ nhất 40px" — thoải mái — trong khi user nhìn thấy một sợi chỉ. Đo trên data
    thật: 606/4711 vùng (12.9%) có bề ngang thật dưới 6px vẫn lọt qua mọi cổng lọc vì
    bbox của chúng lớn.

    label_anchor.radius (từ distance transform) là bán kính hình tròn nội tiếp lớn nhất,
    nên 2*radius+1 chính là bề ngang thật đó — cùng đơn vị với bbox_min_side, dùng lại
    được nguyên các ngưỡng đã tinh chỉnh. Luôn <= bbox_min_side, nên đổi sang nó chỉ làm
    các cổng CHẶT hơn chứ không nới ra.

    Đường candidate-scoring rẻ (skip_label_anchor=True) không tính anchor thật nên không có
    radius — lúc đó quay về bbox_min_side như cũ.
    """
    radius = (info.get("label_anchor") or {}).get("radius")
    if radius is None:
        return bbox_min_side(info["bbox"])
    return 2.0 * radius + 1.0


def is_tiny_display_region(info, tiny_area_threshold, tiny_side_threshold):
    return (
        info["area"] < tiny_area_threshold
        or region_thickness_diameter(info) < tiny_side_threshold
    )


def is_micro_region(info, tiny_merge_min_area, tiny_merge_min_side):
    return (
        info["area"] < tiny_merge_min_area
        or region_thickness_diameter(info) < tiny_merge_min_side
    )


def update_region_classification(
    info,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
    unreadable_radius_px=None,
):
    """unreadable_radius_px mặc định None (TẮT hoàn toàn) để giữ nguyên hành vi/test hiện có
    ở mọi lời gọi hiện tại — bật rõ ràng (LABEL_UNREADABLE_RADIUS_PX) chỉ ở 2 chỗ: (1) lúc
    tạo region lần đầu (để hide_number đúng ngay từ đầu), và (2) lượt gộp bắt buộc cuối cùng
    sau split_remaining_giant_regions (để đảm bảo luôn gộp được, không chỉ ẩn số). Nếu bật ở
    MỌI lời gọi merge trong suốt pipeline (kể cả các profile "strict" không cho fallback),
    vùng vừa gộp xong nhưng hình dạng vẫn mảnh sẽ liên tục bị đánh dấu "cần gộp tiếp" dù
    không có láng giềng màu hợp — gây kẹt vô ích ở những bước chưa có fallback đảm bảo.
    """
    min_side = bbox_min_side(info["bbox"])
    thickness = region_thickness_diameter(info)
    info["min_side"] = min_side
    info["thickness_diameter"] = thickness
    info["is_small_region"] = info["area"] < min_region_area

    unreadable_even_at_max_zoom = False
    if unreadable_radius_px is not None:
        # radius vắng mặt (đường candidate-scoring rẻ dùng skip_label_anchor=True, không
        # tính label_anchor thật) thì bỏ qua điều kiện này.
        label_radius = info.get("label_anchor", {}).get("radius")
        unreadable_even_at_max_zoom = (
            label_radius is not None and label_radius < unreadable_radius_px
        )

    info["is_tiny_display_region"] = (
        info["area"] < tiny_area_threshold
        or thickness < tiny_side_threshold
        or unreadable_even_at_max_zoom
    )
    info["is_micro_region"] = (
        info["area"] < tiny_merge_min_area
        or thickness < tiny_merge_min_side
        or unreadable_even_at_max_zoom
    )

    # hide_number tính lại MỖI LẦN từ hình dạng hiện tại (area/radius), không kế thừa kiểu
    # AND từ trước khi gộp — nếu không, 2 vùng nhỏ bị ẩn số gộp lại thành 1 vùng đủ lớn vẫn
    # có thể tiếp tục bị ẩn nhầm.
    #
    # KHÔNG dùng chung điều kiện với is_tiny_display_region: hide_number là ẩn VĨNH VIỄN,
    # nên chỉ đúng cho vùng mà không mức zoom nào cứu được. App vốn đã có luật động theo
    # zoom (PaintCanvasView: radius * scaleFactor >= 25) và luật đó giờ đáng tin vì radius
    # đã được đo đúng — vùng chỉ "hẹp ở zoom mặc định" cứ để app tự ẩn/hiện theo zoom. Ẩn
    # vĩnh viễn ở đây là lấy mất con số mà user chỉ cần zoom vào là đọc được.
    info["hide_number"] = info["area"] < tiny_area_threshold or unreadable_even_at_max_zoom
    return info


def classify_region_infos(
    region_infos,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
    unreadable_radius_px=None,
):
    for info in region_infos:
        update_region_classification(
            info=info,
            min_region_area=min_region_area,
            tiny_area_threshold=tiny_area_threshold,
            tiny_side_threshold=tiny_side_threshold,
            tiny_merge_min_area=tiny_merge_min_area,
            tiny_merge_min_side=tiny_merge_min_side,
            unreadable_radius_px=unreadable_radius_px,
        )
    return region_infos


def is_protected_detail_region(info):
    return bool(info and info.get("protect_from_merge"))


def is_non_meaningful_merge_noise(info):
    return (
        int(info.get("area", 0) or 0) < PROTECTED_DETAIL_MIN_AREA
        or (
            bbox_min_side(info["bbox"]) <= NON_MEANINGFUL_MERGE_MAX_THICKNESS
            and region_thickness_diameter(info) <= NON_MEANINGFUL_MERGE_MAX_THICKNESS
        )
    )


def can_merge_color_drift_region(info, color_gap, color_threshold):
    return (
        color_gap <= color_threshold
        or info.get("hide_number")
        or is_non_meaningful_merge_noise(info)
    )


def mark_protected_detail_regions(
    region_infos,
    attach_distance,
    color_distance_threshold=PROTECTED_DETAIL_COLOR_DISTANCE,
    min_area=PROTECTED_DETAIL_MIN_AREA,
    max_area=PROTECTED_DETAIL_MAX_AREA,
    total_pixels=None,
    max_area_pct=PROTECTED_DETAIL_MAX_AREA_PCT,
    max_side=PROTECTED_DETAIL_MAX_SIDE,
):
    """Protect small high-contrast islands from later merge passes.

    Examples: watermelon seeds, flower dots, tiny eyes. These may be too small to show a
    permanent label, but if their reference color differs sharply from nearby larger regions
    they should remain interactive regions instead of being swallowed by a parent color.
    """
    if not region_infos:
        return 0

    point_index = build_region_point_index(region_infos)
    protected_count = 0
    for idx, info in enumerate(region_infos):
        if is_protected_detail_region(info):
            protected_count += 1
            continue
        # A protected detail becomes a standalone paint target. If the target is already too
        # small to show a number or too narrow to tap reliably, keeping it separate creates the
        # exact UX failure users see as dozens of tiny highlighted dots. Let merge passes handle
        # those specks; only protect details that are still playable as independent regions.
        if (
            info.get("hide_number")
            or info.get("is_micro_region")
            or info.get("is_tiny_display_region")
        ):
            continue
        area = int(info.get("area", 0) or 0)
        if area < min_area or area > max_area:
            continue
        if (
            total_pixels is not None
            and area * 100.0 / max(1, total_pixels) > max_area_pct
        ):
            continue
        if bbox_min_side(info["bbox"]) <= 0:
            continue
        if max(bbox_width(info["bbox"]), bbox_height(info["bbox"])) > max_side:
            continue

        adjacent_counts, bridged_ink_gap = find_adjacent_region_indices(
            idx,
            region_infos,
            point_index,
            ink_gap_px=attach_distance,
        )
        candidates = []
        for candidate_idx, shared_boundary_count in adjacent_counts.items():
            candidate = region_infos[candidate_idx]
            if candidate["area"] <= area:
                continue
            gap = color_distance(info["target_color"], candidate["target_color"])
            score = gap - min(shared_boundary_count, 12) * 0.25
            candidates.append((score, gap, candidate_idx, bridged_ink_gap))
        if not candidates:
            continue

        _, best_gap, best_idx, used_ink_gap = max(candidates, key=lambda item: item[1])
        if best_gap < color_distance_threshold:
            continue

        neighbor = region_infos[best_idx]
        info["protect_from_merge"] = True
        info["protected_detail_reason"] = "small_high_contrast_island"
        info["protected_detail_color_distance"] = round(best_gap, 2)
        info["protected_detail_neighbor_area"] = neighbor["area"]
        info["protected_detail_neighbor_color"] = neighbor["target_color"]
        info["protected_detail_bridged_ink_gap"] = used_ink_gap
        protected_count += 1

    return protected_count


def count_remaining_micro_regions(
    region_infos,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
    unreadable_radius_px=None,
):
    classified = classify_region_infos(
        region_infos=[dict(info) for info in region_infos],
        min_region_area=min_region_area,
        tiny_area_threshold=tiny_area_threshold,
        tiny_side_threshold=tiny_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        unreadable_radius_px=unreadable_radius_px,
    )
    return sum(1 for info in classified if info.get("is_micro_region"))


def build_region_point_index(region_infos):
    point_index = {}
    for region_idx, info in enumerate(region_infos):
        for point in info["region"]:
            point_index[point] = region_idx
    return point_index


def find_adjacent_region_indices(small_idx, region_infos, point_index, ink_gap_px=0):
    """Trả về (adjacent_counts, bridged_ink_gap).

    bridged_ink_gap=True nghĩa là các láng giềng này tìm được bằng cách nhìn XUYÊN nét mực
    chứ không chạm trực tiếp — gộp vào chúng sẽ tạo ra vùng KHÔNG liền mạch, nên caller phải
    tính thêm vào hạn mức mảnh rời (xem MAX_REGION_FRAGMENTS).
    """
    adjacent_counts = Counter()
    for x, y in region_infos[small_idx]["region"]:
        for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            neighbor_idx = point_index.get((x + dx, y + dy))
            if neighbor_idx is None or neighbor_idx == small_idx:
                continue
            adjacent_counts[neighbor_idx] += 1

    if adjacent_counts or ink_gap_px <= 0:
        return adjacent_counts, False

    # Không có láng giềng nào CHẠM trực tiếp: vùng bị nét mực bao kín. Đo trên data thật thì
    # 33% số vùng (1561/4711) rơi vào trường hợp này, và 4 level có tỉ lệ 100% — ở đó MỌI
    # bước gộp dựa trên adjacency đều chạy không, `merged_tiny_regions_count` đúng bằng 0.
    # Việc giảm số vùng trên các level đó trước đây hoàn toàn là hệ quả phụ của lỗi radius
    # (vùng bị gắn cờ "không đọc được" sai nên rơi vào absorb-by-proximity); sửa lỗi radius
    # xong thì không còn gì gộp chúng nữa.
    #
    # Nên chỉ khi KHÔNG có tiếp xúc trực tiếp mới nới ra: cho phép nhìn xuyên tối đa
    # ink_gap_px pixel mực để tìm láng giềng. Chạy sau chứ không chạy thay, nên vùng có
    # tiếp xúc thật giữ nguyên hành vi cũ từng pixel — không đụng gì tới các level đang tốt.
    # Đo phân bố khe mực giữa 2 vùng: 4px phủ 78-90% số ranh giới, vẫn mỏng hơn hẳn nét viền
    # cố ý dày (vốn nên tách 2 vật thể khác nhau thật).
    for x, y in region_infos[small_idx]["region"]:
        for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            for step in range(2, ink_gap_px + 2):
                probe = point_index.get((x + dx * step, y + dy * step))
                if probe == small_idx:
                    # Quay lại chính mình (vùng hình chữ U) — hướng này không dẫn ra ngoài.
                    break
                if probe is not None:
                    adjacent_counts[probe] += 1
                    break
    return adjacent_counts, True


# Số mảnh rời tối đa của một vùng. Mỗi vùng chỉ được vẽ ĐÚNG MỘT con số (tại label_anchor),
# và một cú tap tô toàn bộ mask color đó trên cả canvas, nên vùng gồm N mảnh rải rác nghĩa là
# N-1 mảnh không có số nhưng vẫn tự tô khi user bấm chỗ khác. Đo trên data: 57% số vùng bị đứt
# rời, trung bình 6.5 mảnh, cá biệt 194 mảnh — tức 193 mảnh không số.
#
# Lưu ý khi chỉnh: TỔNG số mảnh của một level gần như cố định (do line art quyết định — đo
# được 592 mảnh trước và sau khi đổi hạn mức), nên hạn mức chỉ PHÂN BỔ LẠI mảnh vào nhiều
# vùng hơn chứ không làm mảnh biến mất. Hạ xuống 4 tiếp tục cắt đuôi nhưng làm số vùng gần
# gấp đôi (Animal/01: 180 -> 324) và đẩy tỉ lệ ẩn số lên; 8 là mức tốt hơn "không hạn mức" ở
# mọi level và mọi chỉ số đã đo, mà không làm nổ số vùng:
#   Animal/05  194 -> 8 mảnh (90 -> 118 vùng, ẩn số 54.4% -> 37.3%)
#   Animal/03   83 -> 18 mảnh (flat_similarity 84.7 -> 89.6)
#   Animal/01   27 -> 13 mảnh (180 -> 186 vùng)
MAX_REGION_FRAGMENTS = 8


def region_fragment_count(info):
    return info.get("fragment_count", 1)


def merged_fragment_count(parent_info, child_info, bridged_ink_gap):
    """Hai vùng CHẠM nhau nhập lại thành một khối liền -> tổng số mảnh giảm đi 1. Gộp xuyên
    nét mực thì không dính vào nhau nên số mảnh cộng dồn nguyên vẹn.
    """
    total = region_fragment_count(parent_info) + region_fragment_count(child_info)
    return total if bridged_ink_gap else total - 1


def tiny_region_neighbor_score(small_info, candidate_info, color_gap, shared_boundary_count):
    centroid_dx = small_info["centroid"]["x"] - candidate_info["centroid"]["x"]
    centroid_dy = small_info["centroid"]["y"] - candidate_info["centroid"]["y"]
    centroid_distance = math.sqrt(centroid_dx * centroid_dx + centroid_dy * centroid_dy)
    normalized_area_penalty = small_info["area"] / max(1.0, candidate_info["area"])
    return (
        color_gap
        + centroid_distance * 0.03
        + normalized_area_penalty * 5.0
        - min(shared_boundary_count, 12) * 0.25
    )


def merge_region_info_into_parent(
    parent_info,
    child_info,
    skip_label_anchor=False,
    preserve_label_anchor=False,
    bridged_ink_gap=False,
):
    merged_regions = list(parent_info["region"])
    merged_regions.extend(child_info["region"])
    merged_bbox = merge_bboxes([parent_info["bbox"], child_info["bbox"]])
    merged_centroid = get_region_centroid(merged_regions)
    if preserve_label_anchor:
        merged_label_anchor = parent_info["label_anchor"]
    elif skip_label_anchor:
        # Label anchor placement is expensive (directional boundary scan) and irrelevant
        # when this merge is only run to estimate post-merge region sizes for candidate scoring.
        merged_label_anchor = merged_centroid
    else:
        merged_label_anchor = find_label_anchor(merged_regions, merged_bbox, merged_centroid)
    parent_info["region"] = merged_regions
    parent_info["area"] = len(merged_regions)
    parent_info["bbox"] = merged_bbox
    parent_info["centroid"] = merged_centroid
    parent_info["label_anchor"] = merged_label_anchor
    # hide_number KHÔNG kế thừa kiểu AND từ parent/child nữa — update_region_classification()
    # (được gọi ngay sau mỗi lượt gộp ở cả 2 nơi gọi hàm này) sẽ tính lại từ hình dạng mới,
    # tránh việc 2 vùng nhỏ bị ẩn số gộp lại thành 1 vùng đủ lớn vẫn tiếp tục bị ẩn nhầm.
    parent_info["merged_region_count"] = parent_info.get("merged_region_count", 1) + child_info.get(
        "merged_region_count", 1
    )
    parent_info["fragment_count"] = merged_fragment_count(
        parent_info, child_info, bridged_ink_gap
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
    profile="medium",
    total_pixels=None,
    return_stats=False,
    skip_label_anchor=False,
    unreadable_radius_px=None,
):
    if not region_infos:
        empty_stats = {
            "tiny_merge_rejected_non_adjacent": 0,
            "tiny_merge_rejected_color_distance": 0,
            "tiny_merge_rejected_giant_target": 0,
            "tiny_merge_rejected_fragment_cap": 0,
            "tiny_merge_profile": resolve_generation_profile(profile),
        }
        if return_stats:
            return region_infos, 0, 0, 0, empty_stats
        return region_infos, 0, 0, 0

    policy = TINY_MERGE_POLICY_DEFAULTS[tiny_merge_policy]
    resolved_profile = resolve_generation_profile(profile)
    thresholds = profile_thresholds(resolved_profile)
    enforce_largest_target = total_pixels is not None
    total_pixels = max(1, int(total_pixels or sum(info["area"] for info in region_infos) or 1))
    effective_color_threshold = max(6.0, color_threshold * policy["color_threshold_multiplier"])

    region_infos = classify_region_infos(
        region_infos=region_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=tiny_area_threshold,
        tiny_side_threshold=tiny_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        unreadable_radius_px=unreadable_radius_px,
    )

    merged_tiny_regions_count = 0
    forced_tiny_region_merges_count = 0
    rejected_non_adjacent_count = 0
    rejected_color_distance_count = 0
    rejected_giant_target_count = 0
    rejected_fragment_cap_count = 0

    # 1 lượt (pass) = 1 lần build point_index, xử lý TOÀN BỘ micro_indices hiện có thay vì
    # chỉ gộp 1 cặp rồi rebuild lại từ đầu. Vùng "small" đã gộp được TOMBSTONE (đánh dấu,
    # không pop() ngay) để tránh lệch index trong cùng lượt; vùng "target" đã hấp thụ 1 vùng
    # nhỏ trong lượt này bị FREEZE (không dùng làm target hay small thêm trong lượt này) để
    # mọi candidate còn lại trong lượt luôn phản ánh đúng state tại thời điểm build
    # point_index — tránh phải suy luận về state lồng nhau giữa nhiều lần gộp liên tiếp.
    # Điều này giảm số lần rebuild point_index (quét toàn bộ pixel) từ O(số lần gộp) xuống
    # O(số lượt) — với ảnh vỡ thành hàng nghìn mảnh vụn, số lượt thường chỉ vài chục thay vì
    # hàng nghìn lần rebuild.
    while True:
        point_index = build_region_point_index(region_infos)
        micro_indices = sorted(
            [idx for idx, info in enumerate(region_infos) if info.get("is_micro_region")],
            key=lambda idx: region_infos[idx]["area"],
        )
        if not micro_indices:
            break

        tombstoned = set()
        frozen_targets = set()
        merged_any = False

        for small_idx in micro_indices:
            if small_idx in tombstoned or small_idx in frozen_targets:
                continue
            small_info = region_infos[small_idx]
            if is_protected_detail_region(small_info):
                continue
            if not small_info.get("is_micro_region"):
                continue

            best_candidate_idx = None
            best_candidate_score = None
            fallback_candidate_idx = None
            fallback_candidate_score = None

            adjacent_counts, bridged_ink_gap = find_adjacent_region_indices(
                small_idx, region_infos, point_index, ink_gap_px=attach_distance
            )
            if not adjacent_counts:
                rejected_non_adjacent_count += 1
            for candidate_idx, shared_boundary_count in adjacent_counts.items():
                if candidate_idx in tombstoned or candidate_idx in frozen_targets:
                    # Đã bị động tới trong cùng lượt này (đã gộp đi hoặc đã hấp thụ 1 vùng
                    # khác) — dữ liệu area/target_color không còn khớp với point_index chụp
                    # tại đầu lượt. Để lượt sau xử lý tiếp cho nhất quán.
                    continue
                candidate_info = region_infos[candidate_idx]
                if is_protected_detail_region(candidate_info):
                    continue
                if candidate_info["area"] <= small_info["area"]:
                    continue
                merged_pct = (candidate_info["area"] + small_info["area"]) * 100.0 / total_pixels
                if enforce_largest_target and merged_pct > thresholds["max_largest_region_pct"]:
                    rejected_giant_target_count += 1
                    continue
                if (
                    merged_fragment_count(candidate_info, small_info, bridged_ink_gap)
                    > MAX_REGION_FRAGMENTS
                ):
                    rejected_fragment_cap_count += 1
                    continue

                color_gap = color_distance(small_info["target_color"], candidate_info["target_color"])
                score = tiny_region_neighbor_score(
                    small_info,
                    candidate_info,
                    color_gap,
                    shared_boundary_count,
                )

                if color_gap <= effective_color_threshold:
                    if best_candidate_score is None or score < best_candidate_score:
                        best_candidate_score = score
                        best_candidate_idx = candidate_idx

                elif not policy["allow_fallback"]:
                    rejected_color_distance_count += 1

                elif policy["allow_fallback"] and not can_merge_color_drift_region(
                    small_info,
                    color_gap,
                    effective_color_threshold,
                ):
                    rejected_color_distance_count += 1

                if (
                    policy["allow_fallback"]
                    and can_merge_color_drift_region(
                        small_info,
                        color_gap,
                        effective_color_threshold,
                    )
                    and (fallback_candidate_score is None or score < fallback_candidate_score)
                ):
                    fallback_candidate_score = score
                    fallback_candidate_idx = candidate_idx

            target_idx = best_candidate_idx
            forced_merge = False
            if target_idx is None and policy["allow_fallback"]:
                target_idx = fallback_candidate_idx
                forced_merge = target_idx is not None

            if target_idx is None:
                continue

            merge_region_info_into_parent(
                region_infos[target_idx],
                small_info,
                skip_label_anchor=skip_label_anchor,
                bridged_ink_gap=bridged_ink_gap,
            )
            tombstoned.add(small_idx)
            frozen_targets.add(target_idx)
            merged_tiny_regions_count += 1
            if forced_merge and best_candidate_idx is None:
                forced_tiny_region_merges_count += 1
            merged_any = True

        if tombstoned:
            region_infos = [
                info for idx, info in enumerate(region_infos) if idx not in tombstoned
            ]
            classify_region_infos(
                region_infos=region_infos,
                min_region_area=min_region_area,
                tiny_area_threshold=tiny_area_threshold,
                tiny_side_threshold=tiny_side_threshold,
                tiny_merge_min_area=tiny_merge_min_area,
                tiny_merge_min_side=tiny_merge_min_side,
                unreadable_radius_px=unreadable_radius_px,
            )

        if not merged_any:
            break

    remaining_tiny_regions_count = sum(1 for info in region_infos if info.get("is_micro_region"))
    stats = {
        "tiny_merge_rejected_non_adjacent": rejected_non_adjacent_count,
        "tiny_merge_rejected_color_distance": rejected_color_distance_count,
        "tiny_merge_rejected_giant_target": rejected_giant_target_count,
        "tiny_merge_rejected_fragment_cap": rejected_fragment_cap_count,
        "tiny_merge_profile": resolved_profile,
    }
    result = (
        region_infos,
        merged_tiny_regions_count,
        forced_tiny_region_merges_count,
        remaining_tiny_regions_count,
    )
    if return_stats:
        return (*result, stats)
    return result


def absorb_small_region_colors(
    region_infos,
    attach_distance,
    color_threshold,
    tiny_area_threshold,
    tiny_side_threshold,
):
    absorbed_count = 0
    point_index = build_region_point_index(region_infos)
    sorted_indices = sorted(range(len(region_infos)), key=lambda idx: region_infos[idx]["area"])
    for small_idx in sorted_indices:
        info = region_infos[small_idx]
        if is_protected_detail_region(info):
            continue
        if not is_tiny_display_region(info, tiny_area_threshold, tiny_side_threshold):
            continue

        best_candidate = None
        best_score = None
        # Buoc nay chi CHEP MAU sang vung nho, khong nhap vung, nen khong lam tang so manh
        # roi -> khong can hạn mức fragment.
        adjacent_counts, _ = find_adjacent_region_indices(
            small_idx, region_infos, point_index, ink_gap_px=attach_distance
        )
        for large_idx, shared_boundary_count in adjacent_counts.items():
            candidate = region_infos[large_idx]
            if is_protected_detail_region(candidate):
                continue
            if candidate["area"] <= info["area"]:
                continue

            color_gap = color_distance(info["target_color"], candidate["target_color"])
            if color_gap > color_threshold:
                continue

            score = color_gap + (
                info["area"] / max(1.0, candidate["area"])
            ) * 10.0 - min(shared_boundary_count, 12) * 0.25
            if best_score is None or score < best_score:
                best_score = score
                best_candidate = candidate

        if best_candidate is not None and info["target_color"] != best_candidate["target_color"]:
            info["target_color"] = best_candidate["target_color"]
            absorbed_count += 1

    return absorbed_count


def absorb_isolated_unreadable_regions_by_proximity(
    region_infos,
    unreadable_radius_px,
    color_threshold=PROTECTED_DETAIL_COLOR_DISTANCE,
):
    """Phương án CUỐI CÙNG cho vùng không đọc được số dù zoom tối đa mà bị nét vẽ bao kín
    hoàn toàn (không có láng giềng liền kề nào — ví dụ 1 ô nhỏ trong hoạ tiết mandala, mỗi ô
    có viền riêng). merge_tiny_regions_into_neighbors (shared-boundary adjacency, đúng thiết
    kế để tránh gộp nhầm qua khe hở line art) không giúp được ở đây vì hoàn toàn không có
    adjacency để dùng.

    Gán trực tiếp vào vùng ĐỌC ĐƯỢC gần nhất theo khoảng cách centroid, bỏ qua yêu cầu liền
    kề nhưng vẫn giữ guard màu: chỉ nuốt vùng lệch màu nếu nó là noise/anti-alias thật sự.
    Vùng nhỏ có ý nghĩa màu (ví dụ hạt dưa, chấm hoa, mắt) phải được giữ lại cho dù label
    thường bị ẩn. Chỉ áp dụng cho vùng ĐÃ xác nhận không đọc được số; không đụng tới vùng khác.
    """
    survivors = []
    isolated = []
    for info in region_infos:
        if is_protected_detail_region(info):
            survivors.append(info)
            continue
        radius = info.get("label_anchor", {}).get("radius")
        if radius is not None and radius < unreadable_radius_px:
            isolated.append(info)
        else:
            survivors.append(info)

    absorbed_count = 0
    unresolved = []
    for small_info in isolated:
        if not survivors:
            unresolved.append(small_info)
            continue
        cx = small_info["centroid"]["x"]
        cy = small_info["centroid"]["y"]

        def distance_to(candidate):
            return (candidate["centroid"]["x"] - cx) ** 2 + (
                candidate["centroid"]["y"] - cy
            ) ** 2

        # Gộp theo khoảng cách thì hai vùng không hề dính nhau, nên luôn cộng thêm mảnh rời.
        # Hạn mức ở đây là MỀM: ưu tiên vùng gần nhất còn dưới hạn mức, nhưng nếu không còn
        # vùng nào như vậy thì vẫn gộp vào vùng gần nhất — đây là phương án CUỐI cho vùng
        # không đọc được số và không có láng giềng, để trống nó còn tệ hơn.
        eligible_survivors = [
            candidate
            for candidate in survivors
            if (
                not is_protected_detail_region(candidate)
                and can_merge_color_drift_region(
                    small_info,
                    color_distance(small_info["target_color"], candidate["target_color"]),
                    color_threshold,
                )
            )
        ]
        if not eligible_survivors:
            unresolved.append(small_info)
            continue

        under_cap = [
            candidate
            for candidate in eligible_survivors
            if merged_fragment_count(candidate, small_info, True) <= MAX_REGION_FRAGMENTS
        ]
        nearest = min(under_cap or eligible_survivors, key=distance_to)
        merge_region_info_into_parent(nearest, small_info, bridged_ink_gap=True)
        absorbed_count += 1

    return survivors + unresolved, absorbed_count


def merge_small_attached_regions(
    region_infos,
    min_region_area,
    attach_distance,
    color_threshold,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
    profile="medium",
    total_pixels=None,
    skip_label_anchor=False,
):
    if not region_infos:
        return region_infos, 0

    resolved_profile = resolve_generation_profile(profile)
    thresholds = profile_thresholds(resolved_profile)
    enforce_largest_target = total_pixels is not None
    total_pixels = max(1, int(total_pixels or sum(info["area"] for info in region_infos) or 1))

    merged_count = 0

    # Xử lý theo lượt/pass giống merge_tiny_regions_into_neighbors: 1 lần build point_index
    # xử lý toàn bộ small_indices hiện có, dùng tombstone + frozen_targets thay vì
    # pop()-rồi-rebuild sau mỗi 1 lần gộp — tránh O(số lần gộp) lần quét lại toàn bộ pixel.
    while True:
        point_index = build_region_point_index(region_infos)
        small_indices = sorted(
            [
                idx
                for idx, info in enumerate(region_infos)
                if info["area"] < min_region_area
                or is_tiny_display_region(info, tiny_area_threshold, tiny_side_threshold)
                if not is_protected_detail_region(info)
            ],
            key=lambda idx: region_infos[idx]["area"],
        )
        if not small_indices:
            break

        tombstoned = set()
        frozen_targets = set()
        merged_any = False

        for small_idx in small_indices:
            if small_idx in tombstoned or small_idx in frozen_targets:
                continue
            small_info = region_infos[small_idx]
            if is_protected_detail_region(small_info):
                continue
            small_or_tiny = (
                small_info["area"] < min_region_area
                or is_tiny_display_region(small_info, tiny_area_threshold, tiny_side_threshold)
            )
            if not small_or_tiny:
                continue

            best_candidate_idx = None
            best_candidate_score = None
            adjacent_counts, bridged_ink_gap = find_adjacent_region_indices(
                small_idx, region_infos, point_index, ink_gap_px=attach_distance
            )

            for large_idx, shared_boundary_count in adjacent_counts.items():
                if large_idx in tombstoned or large_idx in frozen_targets:
                    continue
                large_info = region_infos[large_idx]
                if is_protected_detail_region(large_info):
                    continue
                if large_info["area"] <= small_info["area"]:
                    continue
                merged_pct = (large_info["area"] + small_info["area"]) * 100.0 / total_pixels
                if enforce_largest_target and merged_pct > thresholds["max_largest_region_pct"]:
                    continue
                if (
                    merged_fragment_count(large_info, small_info, bridged_ink_gap)
                    > MAX_REGION_FRAGMENTS
                ):
                    continue

                color_gap = color_distance(small_info["target_color"], large_info["target_color"])
                if color_gap > color_threshold:
                    continue

                area_score = abs(large_info["area"] - small_info["area"]) / max(1, large_info["area"])
                score = color_gap + area_score - min(shared_boundary_count, 12) * 0.25
                if best_candidate_score is None or score < best_candidate_score:
                    best_candidate_score = score
                    best_candidate_idx = large_idx

            if best_candidate_idx is None:
                continue

            merge_region_info_into_parent(
                region_infos[best_candidate_idx],
                small_info,
                skip_label_anchor=skip_label_anchor,
                bridged_ink_gap=bridged_ink_gap,
            )
            tombstoned.add(small_idx)
            frozen_targets.add(best_candidate_idx)
            merged_count += 1
            merged_any = True

        if tombstoned:
            region_infos = [
                info for idx, info in enumerate(region_infos) if idx not in tombstoned
            ]
            classify_region_infos(
                region_infos=region_infos,
                min_region_area=min_region_area,
                tiny_area_threshold=tiny_area_threshold,
                tiny_side_threshold=tiny_side_threshold,
                tiny_merge_min_area=tiny_merge_min_area,
                tiny_merge_min_side=tiny_merge_min_side,
            )

        if not merged_any:
            break

    return region_infos, merged_count


DEFAULT_MIN_TOUCH_TARGET_DP = 24.0

# canvas_fit_scale quy đổi pixel canvas sang pixel MÀN HÌNH của khung xem 1080px
# (DEFAULT_VIEW_SIZE). 1080px ứng với 360dp — bề ngang điện thoại tiêu chuẩn — nên khung
# tham chiếu đó có density 3. Thiếu hệ số này thì 24dp bị dùng thẳng như 24 pixel, tức chỉ
# bằng 1/3 chuẩn chạm thật của Android.
REFERENCE_SCREEN_DENSITY = 3.0

# Mức zoom giả định người chơi dùng khi tô. KHÔNG phải max zoom của app (20x): lấy 20x nghĩa
# là "chỉ gộp khi vùng vẫn không chạm nổi dù đã phóng hết cỡ", cho ra ngưỡng 2px và bước gộp
# gần như không bao giờ chạy — đo trên toàn bộ data cũ: 0/4711 vùng bị gộp ở bước này. 6x là
# mức zoom thực tế người chơi hay dùng, và đo trên Animal/03 thì nó đưa untouchable_region_pct
# từ 42.9% xuống 12.2% (dưới ngưỡng 18% của profile medium) mà vẫn giữ 123 vùng.
DEFAULT_ASSUMED_ZOOM = 6.0


def resolve_min_touch_bbox_side(
    canvas_width,
    canvas_height,
    min_touch_dp=DEFAULT_MIN_TOUCH_TARGET_DP,
    assumed_zoom=DEFAULT_ASSUMED_ZOOM,
    screen_density=REFERENCE_SCREEN_DENSITY,
):
    """Bề ngang nhỏ nhất (tính bằng pixel canvas) để một vùng còn chạm được.

    Suy ngược từ chuẩn chạm 24dp của Android và tỷ lệ ảnh khi fit vào khung xem, thay vì
    hằng số cứng — nhờ vậy data với ảnh kích thước khác nhau vẫn ra ngưỡng đúng.
    """
    fit_scale = canvas_fit_scale(canvas_width, canvas_height)
    effective_scale = max(1e-6, fit_scale * max(1.0, assumed_zoom))
    return max(1, int(math.ceil(min_touch_dp * screen_density / effective_scale)))


def merge_untouchable_regions(
    region_infos,
    min_touch_bbox_side,
    color_threshold,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
    profile="medium",
    total_pixels=None,
    ink_gap_px=0,
):
    """Gộp các vùng quá nhỏ để chạm vào hàng xóm phù hợp nhất.

    Các bước gộp trước đó xét chủ yếu theo DIỆN TÍCH và rất khắt khe về màu, nên vùng dài
    mà hẹp (ví dụ lõi sợi tóc do ink recovery bào ra) vẫn sống sót: diện tích đủ lớn nhưng
    bề ngang chỉ vài pixel. Với user đó là một chấm gần như vô hình, lại thường bị ẩn số,
    biến level thành trò mò tìm điểm ảnh.

    Ở đây xét theo HÌNH DẠNG (bề ngang thật tại chỗ rộng nhất, xem region_thickness_diameter)
    và cho phép ngưỡng màu rộng hơn nhiều. Nới màu ở bước này gần như không mất gì:
    detail.png mang màu chuẩn theo từng pixel, nên sau khi gộp ảnh cuối vẫn hiện đúng màu
    ảnh gốc tại đó — chỉ thay đổi việc user bấm vào số nào.
    """
    if not region_infos or min_touch_bbox_side <= 1:
        return region_infos, 0

    resolved_profile = resolve_generation_profile(profile)
    thresholds = profile_thresholds(resolved_profile)
    enforce_largest_target = total_pixels is not None
    total_pixels = max(1, int(total_pixels or sum(info["area"] for info in region_infos) or 1))

    def is_untouchable(info):
        return region_thickness_diameter(info) < min_touch_bbox_side

    # Số vùng cần gộp ở bước này rất lớn (hàng trăm), nên dựng lại point_index sau mỗi lần
    # gộp như các bước trước sẽ quá chậm. Thay vào đó đánh dấu vùng đã gộp là None và cập
    # nhật point_index tại chỗ cho đúng các điểm vừa đổi chủ.
    working_infos = list(region_infos)
    point_index = build_region_point_index(working_infos)
    merged_count = 0

    while True:
        candidates = sorted(
            (
                index
                for index, info in enumerate(working_infos)
                if (
                    info is not None
                    and is_untouchable(info)
                    and not is_protected_detail_region(info)
                )
            ),
            key=lambda index: working_infos[index]["area"],
        )
        if not candidates:
            break

        merged_any = False
        for small_idx in candidates:
            small_info = working_infos[small_idx]
            if small_info is None or not is_untouchable(small_info):
                continue

            best_idx = None
            best_score = None
            fallback_idx = None
            fallback_score = None
            adjacent_counts, bridged_ink_gap = find_adjacent_region_indices(
                small_idx, working_infos, point_index, ink_gap_px=ink_gap_px
            )
            for candidate_idx, shared_boundary_count in adjacent_counts.items():
                candidate_info = working_infos[candidate_idx]
                if candidate_info is None:
                    continue
                if is_protected_detail_region(candidate_info):
                    continue
                merged_pct = (candidate_info["area"] + small_info["area"]) * 100.0 / total_pixels
                if enforce_largest_target and merged_pct > thresholds["max_largest_region_pct"]:
                    continue
                if (
                    merged_fragment_count(candidate_info, small_info, bridged_ink_gap)
                    > MAX_REGION_FRAGMENTS
                ):
                    continue

                color_gap = color_distance(
                    small_info["target_color"], candidate_info["target_color"]
                )
                # Ưu tiên hàng xóm đã chạm được: gộp vào một vùng cũng bé thì vẫn còn bé.
                untouchable_penalty = 40.0 if is_untouchable(candidate_info) else 0.0
                score = color_gap + untouchable_penalty - min(shared_boundary_count, 12) * 0.5
                if color_gap <= color_threshold and (
                    best_score is None or score < best_score
                ):
                    best_score = score
                    best_idx = candidate_idx
                if (
                    can_merge_color_drift_region(small_info, color_gap, color_threshold)
                    and (fallback_score is None or score < fallback_score)
                ):
                    fallback_score = score
                    fallback_idx = candidate_idx

            # Vùng vô hình thì màu của nó gần như không ảnh hưởng cảm nhận, nên nếu không có
            # hàng xóm nào đủ gần màu vẫn gộp vào hàng xóm hợp lý nhất thay vì bỏ mặc.
            target_idx = best_idx if best_idx is not None else fallback_idx
            if target_idx is None:
                continue

            merge_region_info_into_parent(
                working_infos[target_idx], small_info, bridged_ink_gap=bridged_ink_gap
            )
            for point in small_info["region"]:
                point_index[point] = target_idx
            working_infos[small_idx] = None
            merged_count += 1
            merged_any = True

        if not merged_any:
            break

    merged_infos = [info for info in working_infos if info is not None]
    classify_region_infos(
        region_infos=merged_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=tiny_area_threshold,
        tiny_side_threshold=tiny_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
    )
    return merged_infos, merged_count


def merge_label_hidden_regions_for_gate(
    region_infos,
    label_canvas_radius_threshold,
    color_threshold,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
    max_hidden_pct=DEFAULT_LABEL_GATE_MAX_HIDDEN_PCT,
    profile="medium",
    total_pixels=None,
):
    """Đo label ẩn ở zoom mặc định nhưng không gộp mask để làm đẹp metric.

    Nếu vùng vẫn chơi được khi zoom, giữ nó interactive và để quality/report nói rõ cho
    designer. Merge bắt buộc chỉ thuộc về các pass max-zoom unreadable/untouchable.
    """
    if not region_infos or label_canvas_radius_threshold <= 0:
        return region_infos, 0, 0

    def hidden_count(infos):
        return sum(
            1
            for info in infos
            if (info.get("label_anchor", {}).get("radius") or 0) < label_canvas_radius_threshold
        )

    _ = (
        color_threshold,
        min_region_area,
        tiny_area_threshold,
        tiny_side_threshold,
        tiny_merge_min_area,
        tiny_merge_min_side,
        max_hidden_pct,
        profile,
        total_pixels,
    )
    return region_infos, 0, hidden_count(region_infos)


def split_giant_region_into_color_segments(region_info, ref_img, max_sub_colors=8):
    """Chia 1 vùng khổng lồ thành các mảng con theo màu tham chiếu (color quantization,
    tương đương k-means trong không gian màu) rồi tách connected component trong từng
    nhóm màu, để khôi phục lại các mảng màu đã mất thay vì để nguyên 1 khối.
    """
    region_points = region_info["region"]
    if len(region_points) < 4:
        return [region_points]

    ref_pixels = ref_img.load()
    sample_img = Image.new("RGB", (len(region_points), 1))
    sample_img.putdata([tuple(ref_pixels[x, y][:3]) for x, y in region_points])
    palette_budget = max(2, min(max_sub_colors, 32))
    quantized = sample_img.quantize(
        colors=palette_budget,
        method=Image.Quantize.MEDIANCUT,
        dither=Image.Dither.NONE,
    ).convert("RGB")
    labels = list(quantized.getdata())
    label_by_point = dict(zip(region_points, labels))

    visited = set()
    components = []
    for point in region_points:
        if point in visited:
            continue
        label = label_by_point[point]
        queue = deque([point])
        visited.add(point)
        component = []
        while queue:
            cx, cy = queue.popleft()
            component.append((cx, cy))
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                neighbor = (cx + dx, cy + dy)
                if neighbor in visited or label_by_point.get(neighbor) != label:
                    continue
                visited.add(neighbor)
                queue.append(neighbor)
        components.append(component)
    return components


def build_region_info_from_points(
    points,
    ref_pixels,
    region_color_method,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
):
    representative_color = get_representative_color(
        ref_pixels, points, method=region_color_method, quantize_step=0
    )
    bbox = get_region_bbox(points)
    centroid = get_region_centroid(points)
    label_anchor = find_label_anchor(points, bbox, centroid)
    area = len(points)
    return update_region_classification(
        info={
            "region": points,
            "area": area,
            "bbox": bbox,
            "centroid": centroid,
            "label_anchor": label_anchor,
            "target_color": representative_color,
            "representative_color": representative_color,
            "merged_region_count": 1,
        },
        min_region_area=min_region_area,
        tiny_area_threshold=tiny_area_threshold,
        tiny_side_threshold=tiny_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        unreadable_radius_px=LABEL_UNREADABLE_RADIUS_PX,
    )


def component_average_color_deviation(ref_pixels, points):
    colors = [ref_pixels[x, y][:3] for x, y in points]
    channels = list(zip(*colors))
    mean = tuple(sum(channel) / len(channel) for channel in channels)
    return sum(color_distance(color, mean) for color in colors) / len(colors)


def giant_region_split_looks_like_flat_patches(
    components,
    ref_pixels,
    min_region_area,
    max_component_color_deviation,
    max_total_components=512,
    max_substantial_components=12,
):
    """True nếu kết quả tách trông giống các mảng màu PHẲNG thật sự bị dính vào nhau
    (một vài mảng "đáng kể" area >= min_region_area, và mỗi mảng gần như đồng màu nội
    bộ), thay vì chỉ là quantization cắt một vùng có nhiễu/gradient mịn (nền texture,
    anti-alias) thành hàng trăm/nghìn mảnh rời rạc cùng match 1 số màu cuối cùng.

    MEDIANCUT tối thiểu hoá variance trong mỗi bucket theo thiết kế, nên chỉ nhìn
    "variance thấp" không đủ phân biệt — ngưỡng max_component_color_deviation được hiệu
    chỉnh từ dữ liệu thật (mảng màu phẳng thật ~0-5, nửa gradient bị cắt cưỡng bức ~9+).

    Số lượng mảng "đáng kể" cũng phải nhỏ (dữ liệu thật: nền có nhiễu/anti-alias tách ra
    tới 345 mảng "đáng kể" trong 1 ca lỗi đã xác nhận, trong khi 2 mảng màu phẳng thật bị
    dính do khe hở line art chỉ tách ra đúng 2) — nếu không, hàng trăm mảnh rời rạc sẽ
    được chấp nhận tách, rồi không bao giờ được gộp lại hết vì mỗi mảnh đã đủ lớn để vượt
    ngưỡng "vùng nhỏ" của bước gộp phía sau, tạo hiệu ứng "vệt confetti" trên preview.
    """
    if len(components) > max_total_components:
        return False

    substantial = sorted(
        (points for points in components if len(points) >= min_region_area),
        key=len,
        reverse=True,
    )
    if len(substantial) < 2 or len(substantial) > max_substantial_components:
        return False
    return all(
        component_average_color_deviation(ref_pixels, points) < max_component_color_deviation
        for points in substantial[:2]
    )


def split_remaining_giant_regions(
    region_infos,
    ref_img,
    total_pixels,
    giant_pct_threshold,
    region_color_method,
    min_region_area,
    tiny_area_threshold,
    tiny_side_threshold,
    tiny_merge_min_area,
    tiny_merge_min_side,
    max_sub_colors=8,
    max_component_color_deviation=7.0,
):
    """Fallback cho vùng khổng lồ còn sót lại sau khi đã chọn candidate preprocessing tốt
    nhất (ví dụ ảnh nét vẽ gốc thật sự có khe hở lớn): sub-segment vùng đó bằng ảnh màu
    tham chiếu thay vì để nguyên 1 khối chiếm phần lớn canvas.

    Chỉ áp dụng khi kết quả tách trông giống các mảng màu phẳng thật sự bị dính vào nhau
    (xem giant_region_split_looks_like_flat_patches — vài mảng lớn, mỗi mảng gần như đồng
    màu nội bộ). Một mảng gradient/nhiễu mượt duy nhất (tách ra vẫn còn biến thiên màu nội
    bộ, hoặc tách ra thành hàng trăm/nghìn mảnh rời rạc) không bị đụng tới, để không phá vỡ
    thiết kế detail-overlay hiện có và không tạo hiệu ứng "vệt confetti" trên preview.

    LƯU Ý: trước đây có thêm điều kiện "merged_region_count > 1" (vùng này từng được gộp
    từ nhiều mảnh nhỏ) để coi là bằng chứng đủ tách — nhưng điều kiện đó gần như LUÔN đúng
    với bất kỳ vùng lớn nào (vì bước dọn vùng nhỏ bình thường, hợp lệ cũng làm tăng
    merged_region_count), nên nó vô hiệu hoá luôn bước kiểm tra an toàn phía dưới. Đã bỏ.
    """
    ref_pixels = ref_img.load()
    updated_infos = []
    split_count = 0
    new_sub_region_count = 0

    for info in region_infos:
        pct = info["area"] * 100.0 / max(1, total_pixels)
        if pct <= giant_pct_threshold:
            updated_infos.append(info)
            continue

        components = split_giant_region_into_color_segments(
            info, ref_img, max_sub_colors=max_sub_colors
        )
        if len(components) <= 1:
            updated_infos.append(info)
            continue

        should_split = giant_region_split_looks_like_flat_patches(
            components, ref_pixels, min_region_area, max_component_color_deviation
        )
        if not should_split:
            updated_infos.append(info)
            continue

        split_count += 1
        for points in components:
            updated_infos.append(
                build_region_info_from_points(
                    points=points,
                    ref_pixels=ref_pixels,
                    region_color_method=region_color_method,
                    min_region_area=min_region_area,
                    tiny_area_threshold=tiny_area_threshold,
                    tiny_side_threshold=tiny_side_threshold,
                    tiny_merge_min_area=tiny_merge_min_area,
                    tiny_merge_min_side=tiny_merge_min_side,
                )
            )
            new_sub_region_count += 1

    return updated_infos, {
        "giant_region_split_count": split_count,
        "giant_region_sub_segments": new_sub_region_count,
    }


def build_single_output_dir(args):
    if args.output_directory:
        return args.output_directory

    category = args.category or "Uncategorized"
    generated_id = args.generated_id or find_next_available_id(args.output_root)
    return os.path.join(args.output_root, category, generated_id)


class LevelQualityGateError(RuntimeError):
    """Raised khi asset vừa sinh ra không đạt ngưỡng chất lượng tối thiểu."""


def evaluate_quality_gate(quality_report):
    metrics = quality_report.get("metrics", {})
    reasons = []

    grade = quality_report.get("quality_grade")
    if grade in {"D", "F"}:
        reasons.append(f"quality_grade={grade}")

    playability_exception = (
        quality_report.get("playability_exception")
        or metrics.get("playability_exception")
        or (quality_report.get("generation_params") or {}).get("playability_exception")
    )
    has_documented_playability_exception = bool(
        isinstance(playability_exception, dict)
        and str(playability_exception.get("reason") or "").strip()
    )

    largest_region_pct = metrics.get("largest_region_pct") or 0
    giant_background_ok = (
        metrics.get("giant_region_legitimacy") == "intentional_flat_background"
        and (metrics.get("final_region_count") or metrics.get("total_regions") or 0) >= 10
    )
    if largest_region_pct > 55 and not giant_background_ok and not has_documented_playability_exception:
        reasons.append(f"largest_region_pct={largest_region_pct}>55")

    if not has_documented_playability_exception:
        hidden_label_pct = (
            metrics.get("actionable_hidden_label_pct")
            if metrics.get("actionable_hidden_label_pct") is not None
            else (
                metrics.get("estimated_hidden_label_pct")
                if metrics.get("estimated_hidden_label_pct") is not None
                else metrics.get("hidden_label_pct")
            )
        ) or 0
        tiny_region_pct_lt_100 = metrics.get("tiny_region_pct_lt_100") or 0
        if hidden_label_pct > 80:
            reasons.append(f"actionable_hidden_label_pct={hidden_label_pct}>80")
        if tiny_region_pct_lt_100 > 45:
            reasons.append(f"tiny_region_pct_lt_100={tiny_region_pct_lt_100}>45")
        if metrics.get("overmerge_risk") == "high":
            reasons.append("overmerge_risk=high")
        region_count_drop_pct = metrics.get("region_count_drop_pct") or 0
        if region_count_drop_pct > 80 and metrics.get("raw_region_count"):
            reasons.append(f"region_count_drop_pct={region_count_drop_pct}>80")

    # KHÔNG dùng giant_region_count làm điều kiện chặn: nó đếm số vùng > 50% diện tích
    # (asset_quality.py) — một ngưỡng cứng, không đồng bộ với ngưỡng 55% ở trên. Vì chỉ có
    # thể có tối đa 1 vùng > 50% diện tích cùng lúc, tín hiệu này chỉ thêm 1 dải 50-55% bị
    # chặn oan mà largest_region_pct > 55 chưa bắt — đã xác nhận bằng dữ liệu thật: toàn bộ
    # ca "giant_region_count=1" nhưng largest_region_pct <= 55 đều là level grade B/74-82đ
    # hợp lệ, không phải lỗi. Giữ lại giant_region_count trong debug_report để tham khảo,
    # chỉ bỏ khỏi hard gate.

    return reasons


def generate_level_assets(
    line_art_path,
    reference_path,
    output_dir,
    category_name="",
    data_name="",
    generated_id="",
    brightness_threshold=120,
    color_merge_threshold=15.0,
    min_region_area=None,
    line_close_radius=0,
    hide_small_label_threshold=None,
    small_region_attach_distance=None,
    tiny_region_side_threshold=None,
    tiny_merge_min_area=None,
    tiny_merge_min_side=None,
    tiny_merge_attach_distance=None,
    tiny_merge_color_threshold=None,
    tiny_merge_policy=None,
    target_unique_colors=None,
    category_profile=None,
    quantize_method="mediancut",
    adaptive_palette=True,
    preprocess_profile="standard",
    region_color_method="median",
    detail_alpha=0.85,
    allow_low_quality=False,
    ink_core_radius=DEFAULT_INK_RECOVERY_RADIUS,
    ink_min_chroma_value=DEFAULT_INK_MIN_CHROMA_VALUE,
    ink_min_chroma_spread=DEFAULT_INK_MIN_CHROMA_SPREAD,
    min_touch_dp=DEFAULT_MIN_TOUCH_TARGET_DP,
    assumed_zoom=DEFAULT_ASSUMED_ZOOM,
    untouchable_merge_color_threshold=None,
    ink_gap_attach_px=DEFAULT_INK_GAP_ATTACH_PX,
    source_line_format=DEFAULT_SOURCE_LINE_FORMAT,
):
    if source_line_format not in SOURCE_LINE_FORMATS:
        raise ValueError(
            f"source_line_format phải là một trong: {', '.join(SOURCE_LINE_FORMATS)}."
        )

    original_line_art_path = line_art_path
    display_line_svg_source_path = None
    svg_raster_temp_dir = None

    if source_line_format == "svg":
        if not is_svg_path(line_art_path):
            raise ValueError(
                "source_line_format=svg yêu cầu file line đầu vào là .svg "
                f"(đang nhận: {line_art_path})."
            )
        with Image.open(reference_path) as reference_probe_img:
            raster_width, raster_height = reference_probe_img.size
        svg_raster_temp_dir = tempfile.mkdtemp(prefix=".svg-line-raster-")
        line_art_path = os.path.join(svg_raster_temp_dir, "line.png")
        print(
            f"Đang rasterize SVG line để sinh mask/regions: {original_line_art_path} "
            f"-> {line_art_path} ({raster_width}x{raster_height})"
        )
        rasterize_svg_to_png(
            original_line_art_path,
            line_art_path,
            width=raster_width,
            height=raster_height,
        )
        display_line_svg_source_path = original_line_art_path
    elif is_svg_path(line_art_path):
        raise ValueError(
            "File line đầu vào là SVG nhưng generator đang ở mode raster. "
            "Hãy truyền --source-line-format svg."
        )

    print(f"Đang tải ảnh nét vẽ: {line_art_path}")
    resolved_profile, resolved_target_unique_colors = resolve_target_unique_colors(
        category_name=category_name,
        requested_profile=category_profile,
        explicit_target=target_unique_colors,
    )
    # Đọc size ảnh trước (thao tác rẻ, PIL không decode hết pixel chỉ để lấy .size) để
    # hiệu chỉnh ngưỡng vùng nhỏ theo đúng kích thước hiển thị thật trên màn hình — ảnh
    # nguồn phân giải càng cao hơn REFERENCE_CANVAS_SIZE thì ngưỡng pixel thô càng cần lớn
    # hơn để tương ứng cùng 1 kích thước vật lý trên điện thoại.
    with Image.open(line_art_path) as probe_img:
        probe_width, probe_height = probe_img.size
    screen_scale = screen_size_scale_factor(probe_width, probe_height)
    if screen_scale > 1.01:
        print(
            f"Ảnh nguồn {probe_width}x{probe_height} lớn hơn mốc tham chiếu "
            f"{REFERENCE_CANVAS_SIZE}px — nhân ngưỡng vùng nhỏ lên x{screen_scale:.2f} "
            "để giữ đúng kích thước hiển thị trên màn hình."
        )
    profile_settings = resolve_generation_profile_settings(
        resolved_profile,
        explicit_target=target_unique_colors,
        overrides={
            "min_region_area": min_region_area,
            "hide_small_label_threshold": hide_small_label_threshold,
            "tiny_region_side_threshold": tiny_region_side_threshold,
            "tiny_merge_min_area": tiny_merge_min_area,
            "tiny_merge_min_side": tiny_merge_min_side,
            "tiny_merge_policy": tiny_merge_policy,
        },
        screen_scale=screen_scale,
    )
    resolved_target_unique_colors = profile_settings["target_unique_colors"]
    min_region_area = profile_settings["min_region_area"]
    hide_small_label_threshold = profile_settings["hide_small_label_threshold"]
    # Deprecated/no-op. Kept only so old CLI invocations do not fail.
    _ = small_region_attach_distance
    tiny_region_side_threshold = profile_settings["tiny_region_side_threshold"]
    tiny_merge_min_area = profile_settings["tiny_merge_min_area"]
    tiny_merge_min_side = profile_settings["tiny_merge_min_side"]
    tiny_merge_policy = profile_settings["tiny_merge_policy"]
    # Ô mục tiêu cũng là ngưỡng DIỆN TÍCH nên phải nhân bình phương screen_scale, giống các
    # khoá trong SCREEN_SCALE_AREA_KEYS — nếu không, ảnh nguồn 2048px sẽ bị chia thành số ô
    # gấp 4 lần cần thiết.

    # Deprecated/no-op. Kept only so old CLI invocations do not fail.
    _ = tiny_merge_attach_distance
    effective_tiny_merge_color_threshold = (
        tiny_merge_color_threshold
        if tiny_merge_color_threshold is not None
        else max(10.0, color_merge_threshold * 1.5)
    )
    small_merge_color_threshold = max(6.0, color_merge_threshold * 0.65)
    # Rộng hơn hẳn các bước gộp khác: vùng không chạm được thì màu của nó gần như không ảnh
    # hưởng cảm nhận, và detail.png vẫn giữ đúng màu ảnh gốc tại đó sau khi gộp.
    if untouchable_merge_color_threshold is None:
        untouchable_merge_color_threshold = max(24.0, color_merge_threshold * 3.0)

    print(f"Đang tải ảnh tham chiếu màu: {reference_path}")
    ref_img = Image.open(reference_path).convert("RGB")

    # Candidate scoring cần thấy được hệ quả của bước gộp vùng nhỏ (không chỉ flood-fill
    # thô), nên truyền sẵn ảnh tham chiếu + tham số gộp để select_binary_fill_map mô
    # phỏng merge_tiny_regions_into_neighbors/merge_small_attached_regions cho mỗi candidate.
    merge_settings_for_scoring = {
        "min_region_area": min_region_area,
        "tiny_area_threshold": hide_small_label_threshold,
        "tiny_side_threshold": tiny_region_side_threshold,
        "tiny_merge_min_area": tiny_merge_min_area,
        "tiny_merge_min_side": tiny_merge_min_side,
        "tiny_merge_color_threshold": effective_tiny_merge_color_threshold,
        "small_merge_color_threshold": small_merge_color_threshold,
        "tiny_merge_policy": tiny_merge_policy,
        "profile": resolved_profile,
        "ink_gap_attach_px": ink_gap_attach_px,
    }

    source_line_img, binary_img, recovered_ink_mask, selected_preprocessing = select_binary_fill_map(
        line_art_path,
        brightness_threshold=brightness_threshold,
        line_close_radius=line_close_radius,
        preprocess_profile=preprocess_profile,
        playability_profile=resolved_profile,
        ref_img=ref_img,
        merge_settings=merge_settings_for_scoring,
        ink_core_radius=ink_core_radius,
        min_chroma_value=ink_min_chroma_value,
        min_chroma_spread=ink_min_chroma_spread,
    )
    print(
        "Preprocess được chọn: "
        f"profile={selected_preprocessing['profile']}, "
        f"threshold={selected_preprocessing['brightness_threshold']}, "
        f"close_radius={selected_preprocessing['line_close_radius']}, "
        f"candidate_score={selected_preprocessing['score']}"
    )
    recovered_ink_pixel_count = selected_preprocessing.get("recovered_ink_pixel_count") or 0
    if recovered_ink_pixel_count:
        print(
            f"Ink recovery: đã khôi phục {recovered_ink_pixel_count} pixel mực đặc thành vùng tô "
            f"được (còn bỏ sót {selected_preprocessing.get('lost_color_pct')}% canvas có màu thật)."
        )

    if source_line_img.size != ref_img.size:
        raise ValueError("Kích thước của ảnh nét vẽ và ảnh tham chiếu phải trùng khớp.")

    width, height = source_line_img.size
    line_img = make_line_output_image(binary_img)
    render_line_img = build_render_line_image(source_line_img, binary_img)
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

    # Thu hồi các pixel giấy trắng bị close_small_line_gaps hi sinh, nếu không chúng sẽ
    # thành vệt trắng dọc ranh giới trên preview và trong app.
    regions, reclaimed_pixel_count = reclaim_non_ink_pixels_into_regions(
        regions=regions,
        width=width,
        height=height,
        source_gray=source_line_img.convert("L"),
        brightness_threshold=selected_preprocessing["brightness_threshold"],
    )
    if reclaimed_pixel_count:
        print(
            f"Đã thu hồi {reclaimed_pixel_count} pixel không phải nét mực về vùng liền kề "
            f"({reclaimed_pixel_count * 100.0 / max(1, width * height):.2f}% ảnh)."
        )

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
                    "merged_region_count": 1,
                },
                min_region_area=min_region_area,
                tiny_area_threshold=hide_small_label_threshold,
                tiny_side_threshold=tiny_region_side_threshold,
                tiny_merge_min_area=tiny_merge_min_area,
                tiny_merge_min_side=tiny_merge_min_side,
                unreadable_radius_px=LABEL_UNREADABLE_RADIUS_PX,
            )
        )

    protected_detail_candidate_count = mark_protected_detail_regions(
        region_infos=region_infos,
        attach_distance=ink_gap_attach_px,
        total_pixels=width * height,
    )
    if protected_detail_candidate_count:
        print(
            f"Đã bảo vệ {protected_detail_candidate_count} chi tiết nhỏ khác màu "
            "khỏi các bước gộp vùng."
        )

    (
        region_infos,
        merged_tiny_regions_count,
        forced_tiny_region_merges_count,
        remaining_tiny_regions_count,
        tiny_merge_stats,
    ) = merge_tiny_regions_into_neighbors(
        region_infos=region_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        attach_distance=ink_gap_attach_px,
        color_threshold=effective_tiny_merge_color_threshold,
        tiny_merge_policy=tiny_merge_policy,
        profile=resolved_profile,
        total_pixels=width * height,
        return_stats=True,
    )

    region_infos, merged_small_region_count = merge_small_attached_regions(
        region_infos=region_infos,
        min_region_area=min_region_area,
        attach_distance=ink_gap_attach_px,
        color_threshold=small_merge_color_threshold,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        profile=resolved_profile,
        total_pixels=width * height,
    )
    # Chạy SAU các bước gộp theo diện tích: những gì còn sót lại ở đây là vùng dài mà hẹp,
    # diện tích đủ lớn nên lọt qua mọi bước trước, nhưng bề ngang chỉ vài pixel.
    min_touch_bbox_side = resolve_min_touch_bbox_side(
        canvas_width=width,
        canvas_height=height,
        min_touch_dp=min_touch_dp,
        assumed_zoom=assumed_zoom,
    )
    region_infos, merged_untouchable_region_count = merge_untouchable_regions(
        region_infos=region_infos,
        min_touch_bbox_side=min_touch_bbox_side,
        color_threshold=untouchable_merge_color_threshold,
        min_region_area=min_region_area,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        profile=resolved_profile,
        total_pixels=width * height,
        ink_gap_px=ink_gap_attach_px,
    )
    if merged_untouchable_region_count:
        print(
            f"Đã gộp {merged_untouchable_region_count} vùng quá nhỏ để chạm "
            f"(cạnh bbox < {min_touch_bbox_side}px) vào vùng lân cận."
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
        attach_distance=ink_gap_attach_px,
        color_threshold=max(10.0, color_merge_threshold * 1.5),
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
    )

    giant_split_stats = {"giant_region_split_count": 0, "giant_region_sub_segments": 0}
    giant_split_pct_threshold = max(
        55.0, profile_thresholds(resolved_profile)["max_largest_region_pct"]
    )
    region_infos, giant_split_stats = split_remaining_giant_regions(
        region_infos=region_infos,
        ref_img=ref_img,
        total_pixels=width * height,
        giant_pct_threshold=giant_split_pct_threshold,
        region_color_method=region_color_method,
        min_region_area=min_region_area,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
    )
    if giant_split_stats["giant_region_split_count"] > 0:
        protected_after_split_count = mark_protected_detail_regions(
            region_infos=region_infos,
            attach_distance=ink_gap_attach_px,
            total_pixels=width * height,
        )
        protected_detail_candidate_count = max(
            protected_detail_candidate_count,
            protected_after_split_count,
        )
        print(
            "Phát hiện vùng khổng lồ còn sót lại sau khi chọn candidate tốt nhất; "
            f"đã sub-segment {giant_split_stats['giant_region_split_count']} vùng thành "
            f"{giant_split_stats['giant_region_sub_segments']} mảng màu con dựa trên ảnh tham chiếu."
        )
        (
            region_infos,
            merged_tiny_regions_count_after_split,
            forced_tiny_region_merges_count_after_split,
            remaining_tiny_regions_count,
            tiny_merge_stats,
        ) = merge_tiny_regions_into_neighbors(
            region_infos=region_infos,
            min_region_area=min_region_area,
            tiny_area_threshold=hide_small_label_threshold,
            tiny_side_threshold=tiny_region_side_threshold,
            tiny_merge_min_area=tiny_merge_min_area,
            tiny_merge_min_side=tiny_merge_min_side,
            attach_distance=ink_gap_attach_px,
            color_threshold=effective_tiny_merge_color_threshold,
            tiny_merge_policy=tiny_merge_policy,
            profile=resolved_profile,
            total_pixels=width * height,
            return_stats=True,
        )
        merged_tiny_regions_count += merged_tiny_regions_count_after_split
        forced_tiny_region_merges_count += forced_tiny_region_merges_count_after_split

        region_infos, merged_small_region_count_after_split = merge_small_attached_regions(
            region_infos=region_infos,
            min_region_area=min_region_area,
            attach_distance=ink_gap_attach_px,
            color_threshold=small_merge_color_threshold,
            tiny_area_threshold=hide_small_label_threshold,
            tiny_side_threshold=tiny_region_side_threshold,
            tiny_merge_min_area=tiny_merge_min_area,
            tiny_merge_min_side=tiny_merge_min_side,
            profile=resolved_profile,
            total_pixels=width * height,
        )
        merged_small_region_count += merged_small_region_count_after_split

    # Lượt gộp bắt buộc cuối cùng: vùng nào không thể đọc được số DÙ ZOOM TỐI ĐA (radius <
    # LABEL_UNREADABLE_RADIUS_PX) phải được gộp vào láng giềng, không được để đứng riêng —
    # để lại 1 vùng user tap được nhưng không bao giờ biết là màu/số gì luôn tệ hơn 1 màu bị
    # gộp lệch nhẹ. Ép tiny_merge_policy="relaxed" (bật allow_fallback) BẤT KỂ profile ảnh
    # đang dùng policy gì, để đảm bảo luôn tìm được láng giềng nếu có (profile "hard"/"strict"
    # vẫn tôn trọng ngưỡng size/gộp bình thường ở các bước trước đó — chỉ riêng vùng không
    # đọc được số mới bị ép gộp bất kể màu có khớp tốt hay không).
    (
        region_infos,
        merged_unreadable_count,
        forced_unreadable_count,
        remaining_unreadable_count,
        _unreadable_merge_stats,
    ) = merge_tiny_regions_into_neighbors(
        region_infos=region_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        attach_distance=ink_gap_attach_px,
        color_threshold=effective_tiny_merge_color_threshold,
        tiny_merge_policy="relaxed",
        profile=resolved_profile,
        total_pixels=width * height,
        return_stats=True,
        unreadable_radius_px=LABEL_UNREADABLE_RADIUS_PX,
    )
    merged_tiny_regions_count += merged_unreadable_count
    forced_tiny_region_merges_count += forced_unreadable_count
    if merged_unreadable_count:
        print(
            f"Đã gộp bắt buộc {merged_unreadable_count} vùng không thể đọc số dù zoom tối đa "
            f"vào láng giềng gần nhất ({forced_unreadable_count} trong số đó bị lệch màu, "
            "chấp nhận đổi lấy việc user luôn xác định được vùng đó là gì)."
        )
    total_absorbed_isolated_count = 0
    if remaining_unreadable_count:
        # Không có adjacency (bị nét vẽ bao kín hoàn toàn, vd 1 ô trong hoạ tiết mandala) —
        # phương án cuối: gán theo khoảng cách gần nhất, bỏ qua yêu cầu liền kề/màu khớp. Lặp
        # tối đa vài lượt: vùng "survivor" vừa hấp thụ 1 ô cô lập có thể vẫn còn mảnh (hình
        # dạng phức tạp/phân nhánh), cần thêm lượt để tìm survivor khác phù hợp hơn — dừng
        # ngay khi không còn hấp thụ được gì thêm (hội tụ) hoặc đã hết vùng không đọc được.
        for _ in range(3):
            still_unreadable_before = sum(
                1
                for info in region_infos
                if (info.get("label_anchor", {}).get("radius") or 0) < LABEL_UNREADABLE_RADIUS_PX
            )
            if not still_unreadable_before:
                break
            region_infos, absorbed_isolated_count = absorb_isolated_unreadable_regions_by_proximity(
                region_infos=region_infos,
                unreadable_radius_px=LABEL_UNREADABLE_RADIUS_PX,
                color_threshold=untouchable_merge_color_threshold,
            )
            classify_region_infos(
                region_infos=region_infos,
                min_region_area=min_region_area,
                tiny_area_threshold=hide_small_label_threshold,
                tiny_side_threshold=tiny_region_side_threshold,
                tiny_merge_min_area=tiny_merge_min_area,
                tiny_merge_min_side=tiny_merge_min_side,
                unreadable_radius_px=LABEL_UNREADABLE_RADIUS_PX,
            )
            total_absorbed_isolated_count += absorbed_isolated_count
            if not absorbed_isolated_count:
                break
        if total_absorbed_isolated_count:
            print(
                f"Đã gán {total_absorbed_isolated_count} vùng bị nét vẽ bao kín hoàn toàn "
                "(không có láng giềng liền kề) vào vùng gần nhất theo khoảng cách khi màu "
                "tương thích hoặc vùng chỉ là noise/anti-alias thật sự."
            )
        still_unreadable = sum(
            1
            for info in region_infos
            if (info.get("label_anchor", {}).get("radius") or 0) < LABEL_UNREADABLE_RADIUS_PX
        )
        if still_unreadable:
            print(
                f"CẢNH BÁO: vẫn còn {still_unreadable} vùng không thể đọc số dù zoom tối đa "
                "sau mọi phương án gộp (level có thể chỉ có 1 vùng duy nhất, không có gì để "
                "gộp vào)."
            )

    fit_scale_for_label_gate = canvas_fit_scale(width, height)
    label_gate_canvas_radius_px = (
        25.0 / fit_scale_for_label_gate if fit_scale_for_label_gate > 0 else 0.0
    )
    (
        region_infos,
        merged_hidden_label_region_count,
        remaining_hidden_label_region_count,
    ) = merge_label_hidden_regions_for_gate(
        region_infos=region_infos,
        label_canvas_radius_threshold=label_gate_canvas_radius_px,
        color_threshold=untouchable_merge_color_threshold,
        min_region_area=min_region_area,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        profile=resolved_profile,
        total_pixels=width * height,
    )
    if merged_hidden_label_region_count:
        merged_tiny_regions_count += merged_hidden_label_region_count
        print(
            f"Đã gộp thêm {merged_hidden_label_region_count} vùng có label quá nhỏ ở zoom mặc định "
            f"(radius canvas < {label_gate_canvas_radius_px:.2f}px); còn "
            f"{remaining_hidden_label_region_count} vùng ước tính sẽ ẩn số."
        )

    # Chốt lại phân loại trên hình dạng CUỐI CÙNG, có bật luật max-zoom: hide_number ghi ra
    # config phải phản ánh đúng vùng sau mọi bước gộp, không phải trạng thái còn sót lại từ
    # một lượt phân loại giữa chừng (các bước gộp chỉ classify khi thực sự có gộp).
    region_infos = classify_region_infos(
        region_infos=region_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        unreadable_radius_px=LABEL_UNREADABLE_RADIUS_PX,
    )

    remaining_tiny_regions_count = count_remaining_micro_regions(
        region_infos=region_infos,
        min_region_area=min_region_area,
        tiny_area_threshold=hide_small_label_threshold,
        tiny_side_threshold=tiny_region_side_threshold,
        tiny_merge_min_area=tiny_merge_min_area,
        tiny_merge_min_side=tiny_merge_min_side,
        unreadable_radius_px=LABEL_UNREADABLE_RADIUS_PX,
    )

    palette_result = build_palette_with_adaptive_merge(
        region_infos=region_infos,
        merge_threshold=color_merge_threshold,
        target_unique_colors=resolved_target_unique_colors,
        category_profile=resolved_profile,
        adaptive_enabled=adaptive_palette,
    )

    # Chia ô SAU khi đã dựng bảng màu: các ô con dùng chung target_color của vùng gốc nên
    # tra bảng vẫn ra đúng một màu/một số, mà bảng màu không bị lệch vì bỗng dưng có thêm
    # hàng chục vùng cùng màu (build_palette_with_adaptive_merge cân nhắc theo số vùng và
    # diện tích, thêm bản sao sẽ làm màu đó có vẻ quan trọng hơn thực tế).
    palette_colors = palette_result["palette_colors"]
    color_to_number = {color: index + 1 for index, color in enumerate(palette_colors)}

    # Ghi asset vào thư mục staging trước; chỉ move đè lên output_dir thật khi asset đạt
    # hard gate chất lượng (hoặc allow_low_quality=True), để không bao giờ âm thầm để lại
    # asset D/F hay level cũ bị xóa dở dang khi generation thất bại.
    final_output_dir = os.path.normpath(output_dir)
    staging_parent = os.path.dirname(final_output_dir) or "."
    os.makedirs(staging_parent, exist_ok=True)
    staging_dir = tempfile.mkdtemp(prefix=".level-staging-", dir=staging_parent)
    output_dir = staging_dir

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
                    "label_visible": not info["hide_number"],
                    "touchable": True,
                    "merged_region_count": info.get("merged_region_count", 1),
                    "protected_detail": is_protected_detail_region(info),
                    "protected_detail_reason": info.get("protected_detail_reason"),
                    "protected_detail_color_distance": info.get(
                        "protected_detail_color_distance"
                    ),
                },
            }
        )
        region_pixel_sets.append(region)

    small_regions_count = sum(1 for info in region_infos if info["is_small_region"])
    protected_detail_region_count = sum(
        1 for info in region_infos if is_protected_detail_region(info)
    )
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
        "raw_region_count": raw_region_count,
        "final_region_count": len(region_configs),
        "region_count_drop_pct": round(
            max(0, raw_region_count - len(region_configs)) * 100.0 / max(1, raw_region_count),
            2,
        ),
        "unique_numbers": unique_numbers,
        "estimated_difficulty": difficulty,
        "small_regions_count": small_regions_count,
        "protected_detail_region_count": protected_detail_region_count,
        "giant_regions_count": giant_regions_count,
    }
    legacy_display_line_img = build_display_line_image(source_line_img, None)
    display_line_img, display_line_report = build_safe_display_line_image(
        source_line_img,
        legacy_display_line_img,
    )
    display_line_report = {
        **display_line_report,
        "selected": "source_line",
        "reason": "ink_recovery_disabled",
        "fallback_to_source": False,
        "ink_recovery_overreach": False,
    }
    recovered_ink_pixel_pct = round(
        recovered_ink_pixel_count * 100.0 / max(1, width * height),
        2,
    )
    ink_recovery_overreach = (
        display_line_report["ink_recovery_overreach"]
        or recovered_ink_pixel_pct > INK_RECOVERY_OVERREACH_RECOVERED_PCT_THRESHOLD
    )
    display_line_report = {
        **display_line_report,
        "mode": "source_similarity_guard",
        "candidate_source": "source_line_no_ink_recovery",
        "uses_segmentation_score": False,
        "svg_canvas_normalized": bool(display_line_svg_source_path),
        "svg_canvas_size": [width, height] if display_line_svg_source_path else None,
        "recovered_ink_pixel_pct": recovered_ink_pixel_pct,
        "recovered_ink_overreach_threshold_pct": INK_RECOVERY_OVERREACH_RECOVERED_PCT_THRESHOLD,
        "ink_recovery_overreach": ink_recovery_overreach,
    }
    segmentation_line_report = make_segmentation_line_report(selected_preprocessing)
    debug_generation_params = {
        "brightness_threshold": brightness_threshold,
        "merge_threshold": color_merge_threshold,
        "line_close_radius": line_close_radius,
        "min_region_area": min_region_area,
        "hide_small_label_threshold": hide_small_label_threshold,
        "tiny_region_side_threshold": tiny_region_side_threshold,
        "tiny_merge_min_area": tiny_merge_min_area,
        "tiny_merge_min_side": tiny_merge_min_side,
        "small_region_merge_mode": "shared_boundary_adjacency",
        "small_color_absorb_mode": "shared_boundary_adjacency",
        "tiny_region_merge_mode": "shared_boundary_adjacency",
        "raw_region_count": raw_region_count,
        "final_region_count": len(region_configs),
        "region_count_drop_pct": stats["region_count_drop_pct"],
        "tiny_merge_color_threshold": effective_tiny_merge_color_threshold,
        "tiny_merge_policy": tiny_merge_policy,
        **tiny_merge_stats,
        "adaptive_palette": adaptive_palette,
        "quantize_method": quantize_method,
        "region_color_method": region_color_method,
        "category_profile": resolved_profile,
        "target_unique_colors": resolved_target_unique_colors,
        "preprocess_profile": preprocess_profile,
        "min_touch_dp": min_touch_dp,
        "assumed_zoom": assumed_zoom,
        "min_touch_bbox_side": min_touch_bbox_side,
        "untouchable_merge_color_threshold": untouchable_merge_color_threshold,
        "merged_untouchable_region_count": merged_untouchable_region_count,
        "merged_unreadable_region_count": merged_unreadable_count,
        "forced_unreadable_region_merges_count": forced_unreadable_count,
        "absorbed_isolated_unreadable_region_count": total_absorbed_isolated_count,
        "label_gate_canvas_radius_px": round(label_gate_canvas_radius_px, 3),
        "merged_hidden_label_region_count": merged_hidden_label_region_count,
        "remaining_hidden_label_region_count": remaining_hidden_label_region_count,
        "protected_detail_candidate_count": protected_detail_candidate_count,
        "protected_detail_region_count": protected_detail_region_count,
        "protected_detail_merge_policy": "skip_all_region_merge_passes",
        "ink_recovery_enabled": False,
        "ink_core_radius": ink_core_radius,
        "ink_min_chroma_value": ink_min_chroma_value,
        "ink_min_chroma_spread": ink_min_chroma_spread,
        "recovered_ink_pixel_count": recovered_ink_pixel_count,
        "recovered_ink_pixel_pct": recovered_ink_pixel_pct,
        "ink_recovery_overreach": ink_recovery_overreach,
        "segmentation_line_mode": "selected_preprocessing_candidate",
        "segmentation_line_report": segmentation_line_report,
        "display_line_mode": "safe_source_fallback",
        "source_line_format": source_line_format,
        "display_line_asset_format": "svg" if display_line_svg_source_path else "raster",
        "display_line_uses_segmentation_score": False,
        "display_line_report": display_line_report,
        "lost_color_pct": selected_preprocessing.get("lost_color_pct"),
        "selected_preprocessing": selected_preprocessing,
        "has_detail": True,
        "detail_mode": "reference_lerp_rgba",
        "detail_alpha": detail_alpha,
    }
    runtime_generation_params = {
        **debug_generation_params,
        "selected_preprocessing": make_runtime_preprocessing_report(selected_preprocessing),
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

    legacy_display_line_out_path = os.path.join(output_dir, "line_render.png")
    legacy_display_line_img.save(legacy_display_line_out_path)
    print(f"Đã lưu ảnh Line hiển thị legacy cho app: {legacy_display_line_out_path}")

    display_line_asset_name = "display_line.png"
    if display_line_svg_source_path:
        display_line_asset_name = "display_line.svg"
        display_line_out_path = os.path.join(output_dir, display_line_asset_name)
        display_line_svg_normalization = normalize_svg_to_canvas(
            display_line_svg_source_path,
            display_line_out_path,
            width,
            height,
        )
        display_line_raster_out_path = os.path.join(output_dir, "debug_display_line_raster.png")
        display_line_img.save(display_line_raster_out_path)
        print(
            "Đã lưu SVG Line hiển thị đã normalize cho app: "
            f"{display_line_out_path} "
            f"(source_viewBox={display_line_svg_normalization['source_view_box']}, "
            f"target_viewBox={display_line_svg_normalization['target_view_box']}, "
            f"scale={display_line_svg_normalization['scale_x']:.3f}x"
            f"{display_line_svg_normalization['scale_y']:.3f}, "
            "removed_visible_white_fills="
            f"{display_line_svg_normalization['removed_visible_white_fill_count']})"
        )
        print(f"Đã lưu bản raster debug của SVG Line: {display_line_raster_out_path}")
    else:
        display_line_out_path = os.path.join(output_dir, display_line_asset_name)
        display_line_img.save(display_line_out_path)
        print(
            "Đã lưu ảnh Line hiển thị an toàn: "
            f"{display_line_out_path} "
            f"(selected={display_line_report['selected']}, "
            f"changed={display_line_report['candidate_changed_pct']}%, "
            f"mae={display_line_report['candidate_mae']})"
        )

    source_line_out_path = os.path.join(output_dir, "debug_source_line.png")
    source_line_img.save(source_line_out_path)
    print(f"Đã lưu ảnh Line gốc để debug: {source_line_out_path}")

    fill_coverage_img, fill_coverage_report = build_fill_coverage_image(
        width=width,
        height=height,
        mask_img=mask_img,
        display_line_img=display_line_img,
    )
    fill_coverage_out_path = os.path.join(output_dir, "fill_coverage.png")
    fill_coverage_img.save(fill_coverage_out_path)
    print(
        f"Đã lưu mask coverage chống hở viền: {fill_coverage_out_path} "
        f"(extra_pixels={fill_coverage_report['fill_coverage_extra_pixels']})"
    )

    flat_preview_img = make_flat_colored_image(
        width=width,
        height=height,
        mask_img=fill_coverage_img,
        mask_to_target_rgb=mask_to_target_rgb,
    )
    flat_preview_out_path = os.path.join(output_dir, "debug_preview_flat.png")
    apply_line_overlay(flat_preview_img, display_line_img).save(flat_preview_out_path)
    print(f"Đã lưu ảnh Preview flat để debug: {flat_preview_out_path}")

    detail_img = make_detail_overlay_image(
        reference_img=ref_img,
        flat_preview_img=flat_preview_img,
        line_img=render_line_img,
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
        line_img=display_line_img,
        mask_to_target_rgb=mask_to_target_rgb,
        detail_img=detail_img,
        fill_coverage_img=fill_coverage_img,
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
        generation_params=debug_generation_params,
    )
    config_data = {
        "schema_version": 2,
        "id": generated_id if generated_id else os.path.basename(final_output_dir),
        "name": data_name if data_name else os.path.basename(final_output_dir),
        "category": category_name if category_name else "Uncategorized",
        "width": width,
        "height": height,
        "assets": {
            # Role-based names: make the line-art contract explicit.
            # source_line is the Data line copied for visual/debug comparison.
            # segmentation_line is the processed binary map used to build regions/mask.
            # display_line is the line art users see in the app.
            "source_line": "debug_source_line.png",
            "segmentation_line": "line.png",
            "display_line": display_line_asset_name,
            "legacy_line_render": "line_render.png",
            # Legacy names kept for current Android/backend compatibility.
            "line_render": "line_render.png",
            "line": "line.png",
            "mask": "mask.png",
            "fill_coverage": "fill_coverage.png",
            "preview": "preview_colored.png",
            "detail": "detail.png",
            "debug_regions": "debug_regions.png",
            "debug_report": "debug_report.json",
            "debug_source_line": "debug_source_line.png",
            "debug_preview_flat": "debug_preview_flat.png",
            **({"debug_display_line_raster": "debug_display_line_raster.png"} if display_line_svg_source_path else {}),
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
            "source_mode": (
                "svg_line_plus_reference"
                if display_line_svg_source_path
                else "line_plus_reference"
            ),
            **runtime_generation_params,
        },
        "generation_params": runtime_generation_params,
        "category_profile": resolved_profile,
        "target_unique_colors": resolved_target_unique_colors,
        "initial_unique_numbers": palette_result["initial_unique_numbers"],
        "final_unique_numbers": palette_result["final_unique_numbers"],
        "quantization_method": quantize_method,
        "quantized_reference_color_count": quantized_palette_count,
        "final_merge_threshold": palette_result["final_merge_threshold"],
        "palette_reduction_passes": palette_result["palette_reduction_passes"],
        "palette_reduction_reason": palette_result["palette_reduction_reason"],
        "fill_coverage": fill_coverage_report,
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
    quality_report.setdefault("metrics", {}).update(fill_coverage_report)
    quality_report["fill_coverage"] = fill_coverage_report
    debug_report_out_path = os.path.join(output_dir, "debug_report.json")
    with open(debug_report_out_path, "w", encoding="utf-8") as output_file:
        json.dump(quality_report, output_file, indent=2, ensure_ascii=False)
    print(
        f"Đã lưu báo cáo Debug: {debug_report_out_path} "
        f"(grade={quality_report['quality_grade']}, score={quality_report['quality_score']})"
    )

    gate_failures = evaluate_quality_gate(quality_report)
    if gate_failures and not allow_low_quality:
        shutil.rmtree(staging_dir, ignore_errors=True)
        if svg_raster_temp_dir:
            shutil.rmtree(svg_raster_temp_dir, ignore_errors=True)
        raise LevelQualityGateError(
            "Level '{level_id}' không đạt chất lượng tối thiểu ({reasons}). "
            "Asset KHÔNG được ghi vào '{final_dir}' (thư mục cũ nếu có vẫn giữ nguyên). "
            "Dùng --allow-low-quality nếu cần ghi ra để debug.".format(
                level_id=generated_id or os.path.basename(final_output_dir),
                reasons=", ".join(gate_failures),
                final_dir=final_output_dir,
            )
        )

    if os.path.isdir(final_output_dir):
        shutil.rmtree(final_output_dir)
    shutil.move(staging_dir, final_output_dir)
    if svg_raster_temp_dir:
        shutil.rmtree(svg_raster_temp_dir, ignore_errors=True)

    if gate_failures:
        print(
            "CẢNH BÁO: level không đạt hard gate chất lượng "
            f"({', '.join(gate_failures)}) nhưng vẫn được ghi vì --allow-low-quality."
        )
    print(f"Đã ghi asset vào: {final_output_dir}")
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
        help="Ngưỡng vùng nhỏ để đánh dấu small region. Mặc định lấy theo --category-profile.",
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
        help="Ẩn số ở các vùng quá nhỏ hơn ngưỡng này. Mặc định lấy theo --category-profile.",
    )
    parser.add_argument(
        "--small-region-attach-distance",
        type=int,
        help="Tùy chọn legacy/no-op; merge và color absorb vùng nhỏ đều dùng shared boundary.",
    )
    parser.add_argument(
        "--tiny-region-side-threshold",
        type=int,
        help="Ngưỡng cạnh nhỏ nhất của bbox để xem vùng là quá nhỏ cho việc hiển thị số. Mặc định lấy theo --category-profile.",
    )
    parser.add_argument(
        "--tiny-merge-min-area",
        type=int,
        help="Ngưỡng area tối thiểu; nhỏ hơn ngưỡng này sẽ ưu tiên gộp hình học vào vùng lân cận. Mặc định lấy theo --category-profile.",
    )
    parser.add_argument(
        "--tiny-merge-min-side",
        type=int,
        help="Ngưỡng cạnh nhỏ nhất; nhỏ hơn ngưỡng này sẽ ưu tiên gộp hình học vào vùng lân cận. Mặc định lấy theo --category-profile.",
    )
    parser.add_argument(
        "--tiny-merge-attach-distance",
        type=int,
        help="Tùy chọn legacy/no-op; Tiny Merge V2 dùng shared boundary thay vì khoảng cách.",
    )
    parser.add_argument(
        "--tiny-merge-color-threshold",
        type=float,
        help="Ngưỡng lệch màu tối đa ưu tiên khi gộp chấm siêu nhỏ.",
    )
    parser.add_argument(
        "--tiny-merge-policy",
        choices=sorted(TINY_MERGE_POLICY_DEFAULTS.keys()),
        help="Chính sách gộp chấm siêu nhỏ: relaxed, strict hoặc mandala. Mặc định lấy theo --category-profile.",
    )
    parser.add_argument(
        "--target-unique-colors",
        type=int,
        help="Số lượng màu mục tiêu tối đa cho palette cuối.",
    )
    parser.add_argument(
        "--category-profile",
        choices=sorted(DEFAULT_PROFILE_TARGETS.keys()),
        help=(
            "Profile palette/playability: casual, medium, hard hoặc mandala. "
            "Alias cũ: easy=casual, standard=medium."
        ),
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
        choices=["standard", "thin-line", "manga", "mandala", "source-line", "poster", "auto"],
        default="auto",
        help=(
            "Profile xử lý line trước flood-fill. Mặc định auto thử nhiều threshold/close radius "
            "để giữ cả nét xám/anti-alias; standard giữ hành vi cũ."
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
        default=0.85,
        help="Độ mạnh tối đa của detail.png khi blend lại với flat fill, trong khoảng 0..1.",
    )
    parser.add_argument(
        "--min-touch-dp",
        type=float,
        default=DEFAULT_MIN_TOUCH_TARGET_DP,
        help=(
            "Kích thước chạm tối thiểu (dp) để một vùng được coi là bấm được. Vùng nhỏ hơn sẽ "
            "bị gộp vào lân cận thay vì để user mò tìm chấm gần như vô hình."
        ),
    )
    parser.add_argument(
        "--assumed-zoom",
        type=float,
        default=DEFAULT_ASSUMED_ZOOM,
        help=(
            "Mức phóng to giả định user sẽ dùng khi tô. 1.0 = phải chạm được ngay ở zoom mặc "
            "định (gộp mạnh nhất, ít vùng nhất); 2.0 = chấp nhận phải zoom 2x (giữ nhiều chi "
            "tiết hơn)."
        ),
    )
    parser.add_argument(
        "--untouchable-merge-color-threshold",
        type=float,
        help=(
            "Ngưỡng lệch màu tối đa khi gộp vùng không chạm được. Mặc định rộng hơn hẳn các "
            "bước gộp khác vì detail.png vẫn giữ đúng màu ảnh gốc sau khi gộp."
        ),
    )
    parser.add_argument(
        "--ink-core-radius",
        type=int,
        default=DEFAULT_INK_RECOVERY_RADIUS,
        help=(
            "Deprecated/no-op. Ink recovery đã bị tắt vì từng biến nét gốc thành vùng tô và "
            "gây tô tràn qua vùng chưa khoanh."
        ),
    )
    parser.add_argument(
        "--ink-min-chroma-value",
        type=int,
        default=DEFAULT_INK_MIN_CHROMA_VALUE,
        help=(
            "Kênh màu sáng nhất của ảnh gốc phải vượt ngưỡng này thì mảng mực mới được coi là "
            "có màu thật và đáng khôi phục. Nâng lên nếu nét viền tối bị khôi phục nhầm."
        ),
    )
    parser.add_argument(
        "--ink-min-chroma-spread",
        type=int,
        default=DEFAULT_INK_MIN_CHROMA_SPREAD,
        help=(
            "Độ lệch giữa kênh sáng nhất và tối nhất của ảnh gốc phải vượt ngưỡng này thì mảng "
            "mực mới được coi là có sắc (không phải đen/xám trung tính) và đáng khôi phục."
        ),
    )
    parser.add_argument(
        "--allow-low-quality",
        action="store_true",
        help=(
            "Cho phép ghi asset ra output_dir dù không đạt hard gate chất lượng "
            "(quality_grade D/F, largest_region_pct > 55%%, hoặc giant_region_count > 0). "
            "Mặc định generation sẽ raise lỗi và không ghi asset. Chỉ dùng để debug."
        ),
    )
    parser.add_argument(
        "--source-line-format",
        choices=SOURCE_LINE_FORMATS,
        default=DEFAULT_SOURCE_LINE_FORMAT,
        help=(
            "Định dạng file line trong Data. raster = mode cũ dùng ảnh line.png/jpg/webp; "
            "svg = mode mới dùng line.svg làm display_line đã normalize theo canvas output, "
            "đồng thời rasterize tạm để sinh mask."
        ),
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
    batch_source_category.add_argument(
        "--only-ids",
        help=(
            "Chỉ regenerate các ID này trong category nguồn (phẩy phân cách), ví dụ 02,03,04. "
            "Bỏ qua sẽ xử lý toàn bộ category."
        ),
    )
    batch_source_category.add_argument(
        "--overwrite",
        action="store_true",
        help="Cho phép ghi đè lên level đã tồn tại trong assets (dùng khi regenerate level hỏng).",
    )
    batch_source_category.add_argument(
        "--continue-on-error",
        action="store_true",
        help=(
            "Không dừng cả category khi một level lỗi hoặc không đạt quality gate; "
            "ghi nhận level lỗi rồi tiếp tục xử lý các ID còn lại."
        ),
    )

    return parser


def collect_levels_from_data(input_root, source_line_format=DEFAULT_SOURCE_LINE_FORMAT):
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
                if (
                    "line" in lower_name
                    and (
                        (source_line_format == "svg" and lower_name.endswith(".svg"))
                        or (
                            source_line_format == "raster"
                            and lower_name.endswith((".png", ".webp", ".jpg", ".jpeg"))
                        )
                    )
                ):
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


def collect_levels_from_single_category(
    input_category_directory,
    source_line_format=DEFAULT_SOURCE_LINE_FORMAT,
):
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
            if (
                "line" in lower_name
                and (
                    (source_line_format == "svg" and lower_name.endswith(".svg"))
                    or (
                        source_line_format == "raster"
                        and lower_name.endswith((".png", ".webp", ".jpg", ".jpeg"))
                    )
                )
            ):
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
    levels_to_process = collect_levels_from_data(
        input_root,
        source_line_format=args.source_line_format,
    )
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
            allow_low_quality=args.allow_low_quality,
            ink_core_radius=args.ink_core_radius,
            ink_min_chroma_value=args.ink_min_chroma_value,
            ink_min_chroma_spread=args.ink_min_chroma_spread,
            min_touch_dp=args.min_touch_dp,
            assumed_zoom=args.assumed_zoom,
            untouchable_merge_color_threshold=args.untouchable_merge_color_threshold,
            source_line_format=args.source_line_format,
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
    levels_to_process = collect_levels_from_data(
        input_root,
        source_line_format=args.source_line_format,
    )
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
            allow_low_quality=args.allow_low_quality,
            ink_core_radius=args.ink_core_radius,
            ink_min_chroma_value=args.ink_min_chroma_value,
            ink_min_chroma_spread=args.ink_min_chroma_spread,
            min_touch_dp=args.min_touch_dp,
            assumed_zoom=args.assumed_zoom,
            untouchable_merge_color_threshold=args.untouchable_merge_color_threshold,
            source_line_format=args.source_line_format,
        )
        processed_count += 1

    print(
        f"\n=== HOÀN TẤT GOM CATEGORY! Đã tạo thành công {processed_count} tác phẩm vào "
        f"'{target_category}'. ==="
    )


def run_batch_source_category(args):
    input_category_directory = args.input_category_directory
    output_root = args.output_root
    levels_to_process = collect_levels_from_single_category(
        input_category_directory,
        source_line_format=args.source_line_format,
    )
    source_category = os.path.basename(os.path.normpath(input_category_directory))
    target_category = args.target_category or source_category

    only_ids = getattr(args, "only_ids", None)
    if only_ids:
        wanted_ids = {item.strip() for item in only_ids.split(",") if item.strip()}
        levels_to_process = [level for level in levels_to_process if level["name"] in wanted_ids]

    print(
        f"=== BẮT ĐẦU IMPORT CATEGORY NGUỒN: {source_category} "
        f"-> CATEGORY ĐÍCH: {target_category} ==="
    )
    print(f"Tìm thấy tổng cộng {len(levels_to_process)} tác phẩm hợp lệ.")

    processed_count = 0
    failed_levels = []
    skipped_existing_levels = []
    overwrite = getattr(args, "overwrite", False)
    continue_on_error = getattr(args, "continue_on_error", False)

    for level in levels_to_process:
        generated_id = level["name"]
        level_out_dir = os.path.join(output_root, target_category, generated_id)

        if os.path.exists(level_out_dir) and not overwrite:
            message = (
                f"Thư mục đích đã tồn tại: {level_out_dir}. "
                "Hãy xóa/sửa folder cũ, dùng --target-category khác, hoặc --overwrite nếu "
                "đang cố tình regenerate level hỏng."
            )
            if continue_on_error:
                print(f"BỎ QUA ID {generated_id}: {message}")
                skipped_existing_levels.append(generated_id)
                continue
            raise FileExistsError(message)

        print(
            f"\n[XỬ LÝ - ID: {generated_id}] "
            f"Source: {source_category}/{level['name']} -> Category: {target_category}"
        )
        try:
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
                allow_low_quality=args.allow_low_quality,
                ink_core_radius=args.ink_core_radius,
                ink_min_chroma_value=args.ink_min_chroma_value,
                ink_min_chroma_spread=args.ink_min_chroma_spread,
                min_touch_dp=args.min_touch_dp,
                assumed_zoom=args.assumed_zoom,
                untouchable_merge_color_threshold=args.untouchable_merge_color_threshold,
                source_line_format=args.source_line_format,
            )
            processed_count += 1
        except Exception as error:
            if not continue_on_error:
                raise
            failed_levels.append((generated_id, str(error)))
            print(f"LỖI ID {generated_id}: {error}")
            print("Tiếp tục xử lý level kế tiếp vì --continue-on-error.")

    print(
        f"\n=== HOÀN TẤT IMPORT CATEGORY! Thành công {processed_count}, "
        f"bỏ qua đã tồn tại {len(skipped_existing_levels)}, lỗi {len(failed_levels)} "
        f"trong '{target_category}'. ==="
    )
    if skipped_existing_levels:
        print("ID đã tồn tại bị bỏ qua: " + ", ".join(skipped_existing_levels))
    if failed_levels:
        print("ID lỗi:")
        for level_id, error in failed_levels:
            print(f"  - {level_id}: {error}")


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
        allow_low_quality=args.allow_low_quality,
        ink_core_radius=args.ink_core_radius,
        ink_min_chroma_value=args.ink_min_chroma_value,
        ink_min_chroma_spread=args.ink_min_chroma_spread,
        min_touch_dp=args.min_touch_dp,
        assumed_zoom=args.assumed_zoom,
        untouchable_merge_color_threshold=args.untouchable_merge_color_threshold,
        source_line_format=args.source_line_format,
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
