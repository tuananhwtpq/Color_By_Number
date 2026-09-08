#!/usr/bin/env python3
import argparse
import json
from collections import Counter
from pathlib import Path

from PIL import Image, ImageFilter


INK_THRESHOLD = 245


def rgb_to_int(rgb):
    return (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]


def parse_hex_color(value):
    value = value.strip()
    if value.startswith("#"):
        value = value[1:]
    return int(value, 16)


def load_palette(config):
    items = [item for item in config.get("region_palette") or [] if item.get("mask_color")]
    if not items:
        items = [item for item in config.get("regions") or [] if item.get("mask_color")]
    return {
        parse_hex_color(item["mask_color"]): int(item["number"])
        for item in items
    }


def dilate(mask, radius):
    if radius <= 0:
        return mask
    return mask.filter(ImageFilter.MaxFilter(radius * 2 + 1))


def erode(mask, radius):
    if radius <= 0:
        return mask
    return mask.filter(ImageFilter.MinFilter(radius * 2 + 1))


def make_binary_from_line(line_img):
    gray = line_img.convert("L")
    return gray.point(lambda value: 255 if value < INK_THRESHOLD else 0)


def make_coverage_mask(mask_img, coverage_img, palette_by_mask_color, filled_numbers=None):
    allowed_numbers = set(filled_numbers) if filled_numbers else None
    width, height = mask_img.size
    mask_pixels = list(mask_img.convert("RGB").getdata())
    coverage_pixels = list(coverage_img.convert("RGB").getdata()) if coverage_img else mask_pixels
    output = bytearray(width * height)

    for idx, (mask_rgb, coverage_rgb) in enumerate(zip(mask_pixels, coverage_pixels)):
        mask_number = palette_by_mask_color.get(rgb_to_int(mask_rgb))
        coverage_number = palette_by_mask_color.get(rgb_to_int(coverage_rgb))
        if (
            (mask_number is not None and (allowed_numbers is None or mask_number in allowed_numbers)) or
            (coverage_number is not None and (allowed_numbers is None or coverage_number in allowed_numbers))
        ):
            output[idx] = 255

    return Image.frombytes("L", (width, height), bytes(output))


def count_mask(mask):
    return sum(1 for value in mask.getdata() if value)


def gap_mask(line_ink, coverage, radius):
    near_line = dilate(line_ink, radius)
    safe_near_line = Image.eval(near_line, lambda value: 255 if value else 0)
    ink_pixels = line_ink.load()
    coverage_pixels = coverage.load()
    near_pixels = safe_near_line.load()
    width, height = coverage.size

    output = bytearray(width * height)
    for y in range(height):
        row = y * width
        for x in range(width):
            if near_pixels[x, y] and not ink_pixels[x, y] and not coverage_pixels[x, y]:
                output[row + x] = 255
    return Image.frombytes("L", (width, height), bytes(output))


def adjacent_gap_mask(line_ink, coverage, radius):
    base_gap = gap_mask(line_ink, coverage, radius=radius)
    adjacent_to_coverage = dilate(coverage, 1)
    gap_pixels = base_gap.load()
    adjacent_pixels = adjacent_to_coverage.load()
    width, height = coverage.size
    output = bytearray(width * height)
    for y in range(height):
        row = y * width
        for x in range(width):
            if gap_pixels[x, y] and adjacent_pixels[x, y]:
                output[row + x] = 255
    return Image.frombytes("L", (width, height), bytes(output))


def assign_bleed_coverage(mask_img, coverage_img, palette_by_mask_color, allowed_gap, radius, filled_numbers=None):
    allowed_numbers = set(filled_numbers) if filled_numbers else None
    width, height = mask_img.size
    mask_pixels = list(mask_img.convert("RGB").getdata())
    coverage_pixels = list(coverage_img.convert("RGB").getdata()) if coverage_img else mask_pixels
    allowed_pixels = allowed_gap.load()

    source_colors = []
    covered = bytearray(width * height)
    for idx, (mask_rgb, coverage_rgb) in enumerate(zip(mask_pixels, coverage_pixels)):
        mask_color = rgb_to_int(mask_rgb)
        coverage_color = rgb_to_int(coverage_rgb)
        coverage_number = palette_by_mask_color.get(coverage_color)
        mask_number = palette_by_mask_color.get(mask_color)
        color = coverage_color if coverage_number is not None else mask_color
        number = coverage_number if coverage_number is not None else mask_number
        if number is not None and (allowed_numbers is None or number in allowed_numbers):
            source_colors.append(color)
            covered[idx] = 255
        else:
            source_colors.append(None)

    current_colors = source_colors[:]
    for _ in range(radius):
        next_colors = current_colors[:]
        for y in range(height):
            for x in range(width):
                idx = y * width + x
                if current_colors[idx] is not None or not allowed_pixels[x, y]:
                    continue

                neighbors = []
                for ny in range(max(0, y - 1), min(height, y + 2)):
                    for nx in range(max(0, x - 1), min(width, x + 2)):
                        if nx == x and ny == y:
                            continue
                        color = current_colors[ny * width + nx]
                        if color is not None:
                            neighbors.append(color)

                if not neighbors:
                    continue
                color, count = Counter(neighbors).most_common(1)[0]
                if count >= 2:
                    next_colors[idx] = color
        current_colors = next_colors

    output = bytearray(width * height)
    for idx, color in enumerate(current_colors):
        if color is not None:
            output[idx] = 255
    return Image.frombytes("L", (width, height), bytes(output))


def save_overlay(path, base_gap, reduced_gap):
    width, height = base_gap.size
    base_pixels = base_gap.load()
    reduced_pixels = reduced_gap.load()
    overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    out = overlay.load()
    for y in range(height):
        for x in range(width):
            if base_pixels[x, y] and not reduced_pixels[x, y]:
                out[x, y] = (0, 180, 80, 210)
            elif reduced_pixels[x, y]:
                out[x, y] = (255, 40, 40, 230)
    overlay.save(path)


def analyze(level_dir, output_dir, max_radius, filled_numbers):
    level_dir = Path(level_dir)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    config = json.loads((level_dir / "config.json").read_text(encoding="utf-8"))
    palette_by_mask_color = load_palette(config)
    mask_img = Image.open(level_dir / "mask.png").convert("RGB")
    coverage_path = level_dir / "fill_coverage.png"
    coverage_img = Image.open(coverage_path).convert("RGB") if coverage_path.exists() else None

    line_path = level_dir / "debug_display_line_raster.png"
    if not line_path.exists():
        line_path = level_dir / "line_render.png"
    line_ink = make_binary_from_line(Image.open(line_path))
    coverage = make_coverage_mask(mask_img, coverage_img, palette_by_mask_color, filled_numbers=filled_numbers)

    base_gap = gap_mask(line_ink, coverage, radius=max_radius)
    base_gap_count = count_mask(base_gap)
    base_adjacent_gap = adjacent_gap_mask(line_ink, coverage, radius=max_radius)
    base_adjacent_gap_count = count_mask(base_adjacent_gap)
    line_pixels = count_mask(line_ink)
    coverage_pixels = count_mask(coverage)

    rows = []
    for radius in range(1, max_radius + 1):
        allowed_gap = gap_mask(line_ink, coverage, radius=radius)
        bleed_coverage = assign_bleed_coverage(
            mask_img=mask_img,
            coverage_img=coverage_img,
            palette_by_mask_color=palette_by_mask_color,
            allowed_gap=allowed_gap,
            radius=radius,
            filled_numbers=filled_numbers,
        )
        remaining_gap = gap_mask(line_ink, bleed_coverage, radius=max_radius)
        remaining_adjacent_gap = adjacent_gap_mask(line_ink, bleed_coverage, radius=max_radius)
        remaining = count_mask(remaining_gap)
        remaining_adjacent = count_mask(remaining_adjacent_gap)
        filled = base_gap_count - remaining
        rows.append(
            {
                "bleed_radius": radius,
                "remaining_gap_pixels": remaining,
                "remaining_adjacent_gap_pixels": remaining_adjacent,
                "filled_gap_pixels": filled,
                "gap_reduction_pct": 0.0 if base_gap_count == 0 else round(filled * 100.0 / base_gap_count, 2),
                "adjacent_gap_reduction_pct": 0.0 if base_adjacent_gap_count == 0 else round(
                    (base_adjacent_gap_count - remaining_adjacent) * 100.0 / base_adjacent_gap_count,
                    2,
                ),
            }
        )
        save_overlay(output_dir / f"gap_overlay_bleed_{radius}px.png", base_adjacent_gap, remaining_adjacent_gap)

    report = {
        "level": str(level_dir),
        "line_raster": str(line_path),
        "image_size": list(mask_img.size),
        "filled_numbers": filled_numbers,
        "palette_mask_colors": len(palette_by_mask_color),
        "line_ink_pixels": line_pixels,
        "coverage_pixels": coverage_pixels,
        "baseline_gap_radius": max_radius,
        "baseline_gap_pixels": base_gap_count,
        "baseline_adjacent_gap_pixels": base_adjacent_gap_count,
        "variants": rows,
    }
    (output_dir / "bleed_analysis.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    base_gap.save(output_dir / "baseline_gap_mask.png")
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description="Analyze near-line fill gaps and offline bleed variants.")
    parser.add_argument("level_dir", help="Path to a generated level folder, e.g. app/src/main/assets/Cartoon/01")
    parser.add_argument("--output-dir", default="outputs/fill_bleed_analysis/Cartoon_01")
    parser.add_argument("--max-radius", type=int, default=3)
    parser.add_argument(
        "--filled-numbers",
        help="Comma-separated palette numbers to simulate as already filled, e.g. 1,2,3,5. Omit for completed artwork.",
    )
    args = parser.parse_args()
    filled_numbers = None
    if args.filled_numbers:
        filled_numbers = [int(value.strip()) for value in args.filled_numbers.split(",") if value.strip()]
    analyze(args.level_dir, args.output_dir, args.max_radius, filled_numbers)


if __name__ == "__main__":
    main()
