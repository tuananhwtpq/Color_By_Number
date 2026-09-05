#!/usr/bin/env python3
"""Compare fill-edge strategies on generated color-by-number assets.

This is a visual diagnostic tool, not part of the Android runtime. It renders
one selected mask region with several candidate edge treatments so edge bleed,
white gaps, and stair-step artifacts can be compared on the same crop.
"""

from __future__ import annotations

import argparse
import json
import math
from collections import deque
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps


WHITE = (255, 255, 255, 255)
BLACK = (0, 0, 0, 255)


def hex_to_rgb(value: str) -> tuple[int, int, int]:
    value = value.strip().lstrip("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


def hex_to_mask_int(value: str) -> int:
    value = value.strip().lstrip("#")
    return int(value, 16)


def rgb_to_mask_int(rgb: tuple[int, int, int]) -> int:
    return (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]


def load_asset(level_dir: Path, config: dict, key: str, fallback: str | None = None) -> Image.Image | None:
    assets = config.get("assets", {})
    name = assets.get(key) or fallback
    if not name:
        return None
    path = level_dir / name
    if not path.exists():
        return None
    return Image.open(path).convert("RGBA")


def choose_region(config: dict, number: int | None, mask_color: str | None) -> tuple[int, tuple[int, int, int]]:
    candidates = config.get("region_palette", [])
    if mask_color:
        target_mask = hex_to_mask_int(mask_color)
        for entry in candidates:
            if hex_to_mask_int(entry["mask_color"]) == target_mask:
                return target_mask, hex_to_rgb(entry["target_color"])
        raise SystemExit(f"Mask color {mask_color} was not found in region_palette")

    if number is None:
        number = max(config.get("palette", []), key=lambda item: item.get("total_area", 0))["number"]

    matches = [entry for entry in candidates if entry["number"] == number]
    if not matches:
        raise SystemExit(f"Number {number} was not found in region_palette")

    target = hex_to_mask_int(matches[0]["mask_color"])
    color = hex_to_rgb(matches[0]["target_color"])
    return target, color


def mask_for_color(mask_image: Image.Image, mask_color: int) -> Image.Image:
    mask_rgb = mask_image.convert("RGB")
    data = [255 if rgb_to_mask_int(px) == mask_color else 0 for px in mask_rgb.getdata()]
    out = Image.new("L", mask_image.size)
    out.putdata(data)
    return out


def largest_component(alpha: Image.Image) -> Image.Image:
    width, height = alpha.size
    data = bytes(alpha.tobytes())
    visited = bytearray(width * height)
    best: list[int] = []

    for start, value in enumerate(data):
        if value == 0 or visited[start]:
            continue
        queue: deque[int] = deque([start])
        visited[start] = 1
        current: list[int] = []

        while queue:
            idx = queue.popleft()
            current.append(idx)
            x = idx % width
            y = idx // width
            for nx, ny in (
                (x - 1, y),
                (x + 1, y),
                (x, y - 1),
                (x, y + 1),
                (x - 1, y - 1),
                (x + 1, y - 1),
                (x - 1, y + 1),
                (x + 1, y + 1),
            ):
                if nx < 0 or ny < 0 or nx >= width or ny >= height:
                    continue
                nidx = ny * width + nx
                if data[nidx] == 0 or visited[nidx]:
                    continue
                visited[nidx] = 1
                queue.append(nidx)

        if len(current) > len(best):
            best = current

    out = Image.new("L", alpha.size, 0)
    if best:
        out_data = bytearray(width * height)
        for idx in best:
            out_data[idx] = 255
        out.putdata(out_data)
    return out


def component_bbox(alpha: Image.Image, padding: int) -> tuple[int, int, int, int]:
    bbox = alpha.getbbox()
    if not bbox:
        raise SystemExit("Selected region is empty")
    left, top, right, bottom = bbox
    return (
        max(0, left - padding),
        max(0, top - padding),
        min(alpha.width, right + padding),
        min(alpha.height, bottom + padding),
    )


def color_with_detail(region_alpha: Image.Image, target_color: tuple[int, int, int], detail: Image.Image | None) -> Image.Image:
    flat = Image.new("RGBA", region_alpha.size, (*target_color, 255))
    flat.putalpha(region_alpha)
    if detail is None:
        return flat

    detail_rgba = detail.convert("RGBA")
    detail_alpha = ImageChops.multiply(region_alpha, detail_rgba.getchannel("A"))
    detail_rgb = Image.new("RGBA", detail_rgba.size, (0, 0, 0, 0))
    detail_rgb.paste(detail_rgba, (0, 0), detail_alpha)
    return Image.alpha_composite(flat, detail_rgb)


def dark_line_alpha(line: Image.Image, threshold: int) -> Image.Image:
    gray = ImageOps.grayscale(line.convert("RGB"))
    lut = [255 if value < threshold else 0 for value in range(256)]
    return gray.point(lut, "L")


def apply_line_overlay(base: Image.Image, line: Image.Image) -> Image.Image:
    line_rgb = line.convert("RGB")
    base_rgba = base.convert("RGBA")
    pixels = []
    for br, bg, bb, ba in base_rgba.getdata():
        pixels.append((br, bg, bb, ba))

    line_data = list(line_rgb.getdata())
    out = []
    for idx, (r, g, b, a) in enumerate(pixels):
        lr, lg, lb = line_data[idx]
        lum = (lr + lg + lb) // 3
        if lum < 245:
            factor = lum / 255.0
            out.append((round(r * factor), round(g * factor), round(b * factor), a))
        else:
            out.append((r, g, b, a))
    result = Image.new("RGBA", base.size)
    result.putdata(out)
    return result


def compose_variant(
    region_alpha: Image.Image,
    line: Image.Image,
    detail: Image.Image | None,
    target_color: tuple[int, int, int],
    mode: str,
    fill_coverage: Image.Image | None = None,
    mask_color: int | None = None,
) -> Image.Image:
    if mode == "mask_only":
        fill_alpha = region_alpha
    elif mode == "old_bleed_240":
        fill_alpha = ImageChops.lighter(region_alpha, ImageChops.multiply(region_alpha.filter(ImageFilter.MaxFilter(3)), dark_line_alpha(line, 240)))
    elif mode == "block_underpaint_160":
        fill_alpha = ImageChops.lighter(region_alpha, ImageChops.multiply(region_alpha.filter(ImageFilter.MaxFilter(3)), dark_line_alpha(line, 160)))
    elif mode == "soft_line_guard":
        dilated = region_alpha.filter(ImageFilter.MaxFilter(5))
        edge_band = ImageChops.subtract(dilated, region_alpha)
        line_guard = dark_line_alpha(line, 225).filter(ImageFilter.GaussianBlur(0.65))
        soft_edge = ImageChops.multiply(edge_band, line_guard)
        fill_alpha = ImageChops.lighter(region_alpha, soft_edge)
    elif mode == "generated_fill_coverage":
        if fill_coverage is None or mask_color is None:
            fill_alpha = region_alpha
        else:
            fill_rgb = fill_coverage.convert("RGB")
            data = [255 if rgb_to_mask_int(px) == mask_color else 0 for px in fill_rgb.getdata()]
            fill_alpha = Image.new("L", region_alpha.size)
            fill_alpha.putdata(data)
    else:
        raise ValueError(mode)

    canvas = Image.new("RGBA", region_alpha.size, WHITE)
    canvas.alpha_composite(color_with_detail(fill_alpha, target_color, detail))
    return apply_line_overlay(canvas, line)


def label_panel(image: Image.Image, label: str) -> Image.Image:
    header = 36
    panel = Image.new("RGBA", (image.width, image.height + header), (246, 246, 246, 255))
    panel.alpha_composite(image, (0, header))
    draw = ImageDraw.Draw(panel)
    draw.text((10, 10), label, fill=(20, 20, 20, 255), font=ImageFont.load_default())
    return panel


def render_level(level_dir: Path, output_dir: Path, number: int | None, mask_color: str | None, zoom: int, padding: int) -> Path:
    config = json.loads((level_dir / "config.json").read_text())
    selected_mask, target_color = choose_region(config, number, mask_color)

    mask = load_asset(level_dir, config, "mask", "mask.png")
    detail = load_asset(level_dir, config, "detail", "detail.png")
    fill_coverage = load_asset(level_dir, config, "fill_coverage", "fill_coverage.png")
    line = load_asset(level_dir, config, "debug_display_line_raster", "display_line.png")
    if line is None:
        line = load_asset(level_dir, config, "display_line", "display_line.png")
    if mask is None or line is None:
        raise SystemExit(f"{level_dir} is missing mask/display line assets")

    region_alpha = largest_component(mask_for_color(mask, selected_mask))
    crop = component_bbox(region_alpha, padding)

    variants = [
        ("mask only", "mask_only"),
        ("old bleed <240", "old_bleed_240"),
        ("block underpaint <160", "block_underpaint_160"),
        ("soft line guard", "soft_line_guard"),
    ]
    if fill_coverage is not None:
        variants.append(("generated fill_coverage", "generated_fill_coverage"))

    panels = []
    for label, mode in variants:
        rendered = compose_variant(
            region_alpha,
            line,
            detail,
            target_color,
            mode,
            fill_coverage=fill_coverage,
            mask_color=selected_mask,
        ).crop(crop)
        rendered = rendered.resize((rendered.width * zoom, rendered.height * zoom), Image.Resampling.NEAREST)
        panels.append(label_panel(rendered, label))

    gap = 12
    sheet_w = sum(panel.width for panel in panels) + gap * (len(panels) - 1)
    sheet_h = max(panel.height for panel in panels)
    sheet = Image.new("RGBA", (sheet_w, sheet_h), (230, 230, 230, 255))
    x = 0
    for panel in panels:
        sheet.alpha_composite(panel, (x, 0))
        x += panel.width + gap

    output_dir.mkdir(parents=True, exist_ok=True)
    name = f"{config.get('category', level_dir.parent.name)}_{config.get('id', level_dir.name)}_{selected_mask:06x}.png"
    out_path = output_dir / name
    sheet.save(out_path)
    return out_path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("level_dirs", nargs="+", type=Path)
    parser.add_argument("--number", type=int)
    parser.add_argument("--mask-color")
    parser.add_argument("--output-dir", type=Path, default=Path("outputs/fill_edge_diagnostics"))
    parser.add_argument("--zoom", type=int, default=6)
    parser.add_argument("--padding", type=int, default=10)
    args = parser.parse_args()

    for level_dir in args.level_dirs:
        out = render_level(level_dir, args.output_dir, args.number, args.mask_color, args.zoom, args.padding)
        print(out)


if __name__ == "__main__":
    main()
