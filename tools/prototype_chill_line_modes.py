#!/usr/bin/env python3
"""Render offline "chill" line/canvas variants for color-by-number play state.

This is a prototype tool only. It does not modify app assets or runtime code.
"""

import argparse
import json
import shutil
import subprocess
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps


def hex_to_rgb(value):
    value = value.strip().lstrip("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


def hex_to_int(value):
    value = value.strip().lstrip("#")
    return int(value, 16)


def rgb_to_int(rgb):
    return (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]


def load_region_palette(config):
    items = config.get("region_palette") or []
    if not items:
        items = config.get("regions") or []
    palette = {}
    for item in items:
        mask_color = item.get("mask_color")
        target_color = item.get("target_color")
        number = item.get("number")
        if mask_color and target_color and number is not None:
            palette[hex_to_int(mask_color)] = (int(number), hex_to_rgb(target_color))
    return palette


def asset_path(level_dir, config, key, fallback):
    name = (config.get("assets") or {}).get(key) or fallback
    path = level_dir / name
    return path if path.exists() else None


def compose_play_state(level_dir, filled_numbers, background_rgb):
    config = json.loads((level_dir / "config.json").read_text(encoding="utf-8"))
    palette = load_region_palette(config)
    allowed_numbers = set(filled_numbers)

    mask = Image.open(asset_path(level_dir, config, "mask", "mask.png")).convert("RGB")
    coverage_path = asset_path(level_dir, config, "fill_coverage", "fill_coverage.png")
    coverage = Image.open(coverage_path).convert("RGB") if coverage_path else mask
    detail_path = asset_path(level_dir, config, "detail", "detail.png")
    detail = Image.open(detail_path).convert("RGBA") if detail_path else None

    width, height = mask.size
    base = Image.new("RGBA", (width, height), (*background_rgb, 255))
    out = base.load()
    mask_pixels = list(mask.getdata())
    coverage_pixels = list(coverage.getdata())
    detail_pixels = list(detail.getdata()) if detail else None

    for idx, (mask_rgb, coverage_rgb) in enumerate(zip(mask_pixels, coverage_pixels)):
        mask_entry = palette.get(rgb_to_int(mask_rgb))
        coverage_entry = palette.get(rgb_to_int(coverage_rgb))
        entry = coverage_entry or mask_entry
        if entry is None:
            continue
        number, target_rgb = entry
        if number not in allowed_numbers:
            continue

        x = idx % width
        y = idx // width
        color = target_rgb
        if detail_pixels and mask_entry is not None and mask_entry[0] in allowed_numbers:
            dr, dg, db, da = detail_pixels[idx]
            if da:
                alpha = da / 255.0
                color = (
                    round(target_rgb[0] * (1 - alpha) + dr * alpha),
                    round(target_rgb[1] * (1 - alpha) + dg * alpha),
                    round(target_rgb[2] * (1 - alpha) + db * alpha),
                )
        out[x, y] = (*color, 255)

    return base, config


def line_luma(level_dir, config, output_dir, scale):
    svg_path = asset_path(level_dir, config, "display_line", None)
    converter = shutil.which("rsvg-convert")
    if svg_path and converter:
        width = int(config.get("width") or Image.open(level_dir / "mask.png").width)
        height = int(config.get("height") or Image.open(level_dir / "mask.png").height)
        rendered_path = output_dir / f"{config.get('category', level_dir.parent.name)}_{config.get('id', level_dir.name)}_svg_{scale}x.png"
        subprocess.run(
            [
                converter,
                "--width",
                str(width * scale),
                "--height",
                str(height * scale),
                "--output",
                str(rendered_path),
                str(svg_path),
            ],
            check=True,
        )
        rendered = Image.open(rendered_path).convert("RGBA")
        white = Image.new("RGBA", rendered.size, (255, 255, 255, 255))
        white.alpha_composite(rendered)
        return ImageOps.grayscale(white.convert("RGB"))

    path = asset_path(level_dir, config, "debug_display_line_raster", None)
    if path is None:
        path = asset_path(level_dir, config, "line_render", "line_render.png")
    if path is None:
        raise SystemExit("Missing raster line proxy")
    return ImageOps.grayscale(Image.open(path).convert("RGB"))


def apply_multiply_line(base, luma, blur=0.0, strength=1.0):
    source = luma.filter(ImageFilter.GaussianBlur(blur)) if blur > 0 else luma
    base_rgba = base.convert("RGBA")
    out = []
    for (r, g, b, a), value in zip(base_rgba.getdata(), source.getdata()):
        if value >= 250:
            out.append((r, g, b, a))
            continue
        factor = (value / 255.0) * strength + (1.0 - strength)
        out.append((round(r * factor), round(g * factor), round(b * factor), a))
    result = Image.new("RGBA", base.size)
    result.putdata(out)
    return result


def apply_soft_shadow_then_line(base, luma):
    ink = luma.point(lambda value: 255 if value < 235 else 0)
    shadow = ink.filter(ImageFilter.GaussianBlur(0.9))
    shadow_rgba = Image.new("RGBA", base.size, (24, 24, 24, 0))
    shadow_rgba.putalpha(shadow.point(lambda value: round(value * 0.22)))
    softened = Image.alpha_composite(base.convert("RGBA"), shadow_rgba)
    return apply_multiply_line(softened, luma, blur=0.35, strength=0.88)


def crop_box(size, crop):
    if crop:
        left, top, right, bottom = [int(value) for value in crop.split(",")]
        return left, top, right, bottom
    width, height = size
    return 0, 0, width, height


def label_panel(image, label):
    header = 34
    panel = Image.new("RGBA", (image.width, image.height + header), (242, 242, 242, 255))
    panel.alpha_composite(image, (0, header))
    draw = ImageDraw.Draw(panel)
    draw.text((10, 10), label, fill=(20, 20, 20, 255), font=ImageFont.load_default())
    return panel


def render(level_dir, output_dir, filled_numbers, crop, zoom):
    base_white, config = compose_play_state(level_dir, filled_numbers, (255, 255, 255))
    base_warm, _ = compose_play_state(level_dir, filled_numbers, (250, 247, 241))
    output_dir.mkdir(parents=True, exist_ok=True)
    luma = line_luma(level_dir, config, output_dir, zoom)

    if zoom != 1:
        base_white = base_white.resize((base_white.width * zoom, base_white.height * zoom), Image.Resampling.BILINEAR)
        base_warm = base_warm.resize((base_warm.width * zoom, base_warm.height * zoom), Image.Resampling.BILINEAR)

    variants = [
        ("svg hard line", apply_multiply_line(base_white, luma, blur=0.0, strength=1.0)),
        ("soft 0.45px", apply_multiply_line(base_white, luma, blur=0.45, strength=0.94)),
        ("soft shadow line", apply_soft_shadow_then_line(base_white, luma)),
        ("warm paper + soft", apply_multiply_line(base_warm, luma, blur=0.45, strength=0.94)),
    ]

    if crop:
        left, top, right, bottom = [int(value) * zoom for value in crop.split(",")]
        box = left, top, right, bottom
    else:
        box = crop_box(base_white.size, crop)
    panels = []
    for label, image in variants:
        cropped = image.crop(box)
        panels.append(label_panel(cropped, label))

    gap = 12
    sheet = Image.new(
        "RGBA",
        (sum(panel.width for panel in panels) + gap * (len(panels) - 1), max(panel.height for panel in panels)),
        (225, 225, 225, 255),
    )
    x = 0
    for panel in panels:
        sheet.alpha_composite(panel, (x, 0))
        x += panel.width + gap

    out_path = output_dir / f"{config.get('category', level_dir.parent.name)}_{config.get('id', level_dir.name)}_chill_modes.png"
    sheet.save(out_path)
    print(out_path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("level_dir", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path("outputs/chill_line_modes"))
    parser.add_argument("--filled-numbers", default="1,2,3,4,5,6")
    parser.add_argument("--crop", help="left,top,right,bottom crop in image coordinates")
    parser.add_argument("--zoom", type=int, default=4)
    args = parser.parse_args()
    filled_numbers = [int(value.strip()) for value in args.filled_numbers.split(",") if value.strip()]
    render(args.level_dir, args.output_dir, filled_numbers, args.crop, args.zoom)


if __name__ == "__main__":
    main()
