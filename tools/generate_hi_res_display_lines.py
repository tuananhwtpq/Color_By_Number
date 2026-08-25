import argparse
import json
import os
from pathlib import Path

from PIL import Image, ImageFilter


def iter_level_dirs(path):
    path = Path(path)
    if (path / "config.json").is_file():
        yield path
        return

    for config_path in sorted(path.glob("*/*/config.json")):
        yield config_path.parent


def load_json(path):
    with open(path, encoding="utf-8") as input_file:
        return json.load(input_file)


def save_json(path, data):
    with open(path, "w", encoding="utf-8") as output_file:
        json.dump(data, output_file, ensure_ascii=False, indent=2)
        output_file.write("\n")


def configured_asset(config, key, default_name):
    return (config.get("assets") or {}).get(key) or default_name


def sharpen_line(image, amount):
    if amount <= 0:
        return image
    return image.filter(ImageFilter.UnsharpMask(radius=1.0, percent=amount, threshold=2))


def generate_for_level(level_dir, scales, resampling, sharpen_amount, overwrite):
    config_path = level_dir / "config.json"
    config = load_json(config_path)
    assets = config.setdefault("assets", {})
    source_name = configured_asset(config, "display_line", "display_line.png")
    source_path = level_dir / source_name
    if not source_path.exists():
        raise FileNotFoundError(f"Missing display line asset: {source_path}")

    source = Image.open(source_path).convert("RGB")
    generated = []
    for scale in scales:
        output_name = f"display_line_{scale}x.png"
        output_path = level_dir / output_name
        if output_path.exists() and not overwrite:
            assets[f"display_line_{scale}x"] = output_name
            generated.append(output_path)
            continue

        scaled = source.resize(
            (source.width * scale, source.height * scale),
            resampling,
        )
        scaled = sharpen_line(scaled, sharpen_amount)
        scaled.save(output_path)
        assets[f"display_line_{scale}x"] = output_name
        generated.append(output_path)

    save_json(config_path, config)
    return generated


def parse_scales(value):
    scales = []
    for item in value.split(","):
        item = item.strip().lower().removesuffix("x")
        if not item:
            continue
        scale = int(item)
        if scale not in (2, 4):
            raise argparse.ArgumentTypeError("Only 2x and 4x display lines are supported")
        scales.append(scale)
    if not scales:
        raise argparse.ArgumentTypeError("At least one scale is required")
    return sorted(set(scales))


def main():
    parser = argparse.ArgumentParser(
        description="Generate optional hi-res display_line_2x/4x assets without regenerating masks or regions."
    )
    parser.add_argument(
        "assets_path",
        help="Assets root or one level folder containing config.json.",
    )
    parser.add_argument(
        "--scales",
        type=parse_scales,
        default=parse_scales("2"),
        help="Comma-separated scales to generate: 2, 4, or 2,4. Default: 2.",
    )
    parser.add_argument(
        "--nearest",
        action="store_true",
        help="Use nearest-neighbor upscale for very hard pixel edges. Default is Lanczos.",
    )
    parser.add_argument(
        "--sharpen",
        type=int,
        default=120,
        help="UnsharpMask amount after resizing. Use 0 to disable. Default: 120.",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Overwrite existing display_line_2x/4x files.",
    )
    args = parser.parse_args()

    resampling = Image.Resampling.NEAREST if args.nearest else Image.Resampling.LANCZOS
    total = 0
    for level_dir in iter_level_dirs(args.assets_path):
        generated = generate_for_level(
            level_dir=level_dir,
            scales=args.scales,
            resampling=resampling,
            sharpen_amount=args.sharpen,
            overwrite=args.overwrite,
        )
        total += len(generated)
        for path in generated:
            print(os.path.relpath(path))
    print(f"Generated/registered {total} hi-res display line asset(s).")


if __name__ == "__main__":
    main()
