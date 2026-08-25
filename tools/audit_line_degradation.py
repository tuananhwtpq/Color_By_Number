import argparse
import base64
import csv
import io
import json
import os
from html import escape

from PIL import Image, ImageChops, ImageStat

try:
    from validate_assets import iter_level_dirs, infer_category_level
except ImportError:
    from tools.validate_assets import iter_level_dirs, infer_category_level


IMAGE_EXTENSIONS = (".png", ".webp", ".jpg", ".jpeg")
DEFAULT_DISPLAY_CHANGED_THRESHOLD = 10.0
DEFAULT_DISPLAY_MAE_THRESHOLD = 12.0
DEFAULT_SEGMENTATION_CHANGED_THRESHOLD = 35.0
DEFAULT_INK_RECOVERY_OVERREACH_THRESHOLD = 12.0
THUMB_SIZE = 280
THUMB_QUALITY = 90

CSV_COLUMNS = [
    "category",
    "level",
    "level_key",
    "source_line_asset_key",
    "display_line_asset_key",
    "segmentation_line_asset_key",
    "segmentation_selected_profile",
    "segmentation_brightness_threshold",
    "segmentation_line_close_radius",
    "segmentation_evaluation_mode",
    "segmentation_quality_score",
    "segmentation_playable_score",
    "segmentation_lost_color_pct",
    "display_line_selected",
    "display_line_fallback_to_source",
    "display_line_uses_segmentation_score",
    "display_line_candidate_mae",
    "display_line_candidate_changed_pct",
    "display_line_stroke_lightened_pct",
    "display_line_stroke_lightened_mae",
    "display_line_stroke_pixel_count",
    "display_line_stroke_guard_failed",
    "ink_recovery_overreach",
    "line_issue_tags",
    "source_line_path",
    "debug_source_line_path",
    "display_line_path",
    "segmentation_line_path",
    "source_line_mae",
    "source_line_changed_pct",
    "display_line_mae",
    "display_line_changed_pct",
    "segmentation_line_mae",
    "segmentation_line_changed_pct",
    "source_dark_pct_lt_80",
    "display_dark_pct_lt_80",
    "segmentation_dark_pct_lt_80",
    "source_ink_pct_lt_180",
    "display_ink_pct_lt_180",
    "segmentation_ink_pct_lt_180",
    "recovered_ink_pixel_count",
    "recovered_ink_pixel_pct",
    "line_display_degraded",
    "segmentation_line_aggressive",
    "missing_source_line",
    "missing_display_line",
    "missing_segmentation_line",
    "path",
]


def find_data_line_image(data_root, category, level):
    if not data_root:
        return None
    level_dir = os.path.join(data_root, category, level)
    if not os.path.isdir(level_dir):
        return None

    candidates = []
    for file_name in os.listdir(level_dir):
        lower_name = file_name.lower()
        if not lower_name.endswith(IMAGE_EXTENSIONS):
            continue
        if "line" in lower_name:
            candidates.append(os.path.join(level_dir, file_name))
    return sorted(candidates)[0] if candidates else None


def load_json(path):
    if not os.path.exists(path):
        return {}
    with open(path, "r", encoding="utf-8") as input_file:
        return json.load(input_file)


def resolve_configured_asset(level_dir, config, asset_key, fallback_name):
    configured = (config.get("assets") or {}).get(asset_key)
    if configured:
        path = os.path.join(level_dir, configured)
        if os.path.exists(path):
            return path

    fallback = os.path.join(level_dir, fallback_name)
    if os.path.exists(fallback):
        return fallback
    return None


def resolve_role_asset(level_dir, config, candidates):
    assets = config.get("assets") or {}
    for asset_key, fallback_name in candidates:
        configured = assets.get(asset_key)
        if configured:
            path = os.path.join(level_dir, configured)
            if os.path.exists(path):
                return path, asset_key
    for asset_key, fallback_name in candidates:
        path = os.path.join(level_dir, fallback_name)
        if os.path.exists(path):
            return path, asset_key
    return None, ""


def percent_dark(image, threshold):
    histogram = image.histogram()
    return round(sum(histogram[:threshold]) * 100.0 / max(1, image.width * image.height), 2)


def compare_lines(source_path, candidate_path, changed_threshold=10):
    if not source_path or not candidate_path:
        return {
            "mae": None,
            "changed_pct": None,
            "dark_pct_lt_80": None,
            "ink_pct_lt_180": None,
        }

    with Image.open(source_path).convert("L") as source_img:
        with Image.open(candidate_path).convert("L") as candidate_img:
            if candidate_img.size != source_img.size:
                candidate_img = candidate_img.resize(source_img.size, Image.Resampling.BILINEAR)
            diff = ImageChops.difference(source_img, candidate_img)
            stat = ImageStat.Stat(diff)
            changed_count = sum(1 for value in diff.getdata() if value > changed_threshold)
            total = max(1, diff.width * diff.height)
            return {
                "mae": round(stat.mean[0], 2),
                "changed_pct": round(changed_count * 100.0 / total, 2),
                "dark_pct_lt_80": percent_dark(candidate_img, 80),
                "ink_pct_lt_180": percent_dark(candidate_img, 180),
            }


def source_line_stats(source_path):
    if not source_path:
        return {"dark_pct_lt_80": None, "ink_pct_lt_180": None}
    with Image.open(source_path).convert("L") as image:
        return {
            "dark_pct_lt_80": percent_dark(image, 80),
            "ink_pct_lt_180": percent_dark(image, 180),
        }


def read_generation_params(level_dir):
    report = load_json(os.path.join(level_dir, "debug_report.json"))
    return report.get("generation_params") or {}


def read_recovered_ink_count(generation):
    selected = generation.get("selected_preprocessing") or {}
    return (
        generation.get("recovered_ink_pixel_count")
        or selected.get("recovered_ink_pixel_count")
        or ""
    )


def recovered_ink_pct(recovered_count, source_path):
    if recovered_count in ("", None) or not source_path:
        return ""
    with Image.open(source_path) as image:
        total = max(1, image.width * image.height)
    return round(float(recovered_count) * 100.0 / total, 2)


def bool_cell(value):
    return "Y" if value else "N"


def number_cell(value):
    return "" if value is None else value


def truthy_cell(value):
    if value is True:
        return "Y"
    if value is False:
        return "N"
    return ""


def line_issue_tags(row):
    tags = []
    if row.get("line_display_degraded") == "Y":
        tags.append("line_display_degraded")
    if row.get("segmentation_line_aggressive") == "Y":
        tags.append("segmentation_line_aggressive")
    if row.get("ink_recovery_overreach") == "Y":
        tags.append("ink_recovery_overreach")
    if row.get("display_line_stroke_guard_failed") == "Y":
        tags.append("thin_stroke_loss")
    if row.get("display_line_fallback_to_source") == "Y":
        tags.append("display_line_fallback_source")
    return ";".join(tags) if tags else "ok"


def audit_level(
    root_path,
    level_dir,
    data_root,
    display_changed_threshold=DEFAULT_DISPLAY_CHANGED_THRESHOLD,
    display_mae_threshold=DEFAULT_DISPLAY_MAE_THRESHOLD,
    segmentation_changed_threshold=DEFAULT_SEGMENTATION_CHANGED_THRESHOLD,
):
    category, level = infer_category_level(root_path, level_dir)
    config = load_json(os.path.join(level_dir, "config.json"))

    source_path = find_data_line_image(data_root, category, level)
    debug_source_path, source_asset_key = resolve_role_asset(
        level_dir,
        config,
        (
            ("source_line", "debug_source_line.png"),
            ("debug_source_line", "debug_source_line.png"),
        ),
    )
    display_path, display_asset_key = resolve_role_asset(
        level_dir,
        config,
        (
            ("display_line", "line_render.png"),
            ("line_render", "line_render.png"),
            ("legacy_line_render", "line_render.png"),
        ),
    )
    segmentation_path, segmentation_asset_key = resolve_role_asset(
        level_dir,
        config,
        (
            ("segmentation_line", "line.png"),
            ("line", "line.png"),
        ),
    )

    source_compare = compare_lines(source_path, debug_source_path)
    display_compare = compare_lines(source_path, display_path)
    segmentation_compare = compare_lines(source_path, segmentation_path)
    source_stats = source_line_stats(source_path)
    generation = read_generation_params(level_dir)
    segmentation_report = generation.get("segmentation_line_report") or {}
    display_report = generation.get("display_line_report") or {}
    selected_preprocessing = generation.get("selected_preprocessing") or {}
    recovered_count = read_recovered_ink_count(generation)

    display_degraded = (
        display_compare["changed_pct"] is not None
        and (
            display_compare["changed_pct"] > display_changed_threshold
            or display_compare["mae"] > display_mae_threshold
        )
    )
    segmentation_aggressive = (
        segmentation_compare["changed_pct"] is not None
        and segmentation_compare["changed_pct"] > segmentation_changed_threshold
    )
    recovered_pct_value = recovered_ink_pct(recovered_count, source_path)
    display_report_overreach = display_report.get("ink_recovery_overreach")
    if display_report_overreach is None:
        ink_recovery_overreach = (
            recovered_pct_value != ""
            and float(recovered_pct_value) > DEFAULT_INK_RECOVERY_OVERREACH_THRESHOLD
        ) or display_degraded
    else:
        ink_recovery_overreach = bool(display_report_overreach)

    row = {
        "category": category,
        "level": level,
        "level_key": f"{category}/{level}",
        "source_line_asset_key": source_asset_key,
        "display_line_asset_key": display_asset_key,
        "segmentation_line_asset_key": segmentation_asset_key,
        "segmentation_selected_profile": (
            segmentation_report.get("selected_profile")
            or selected_preprocessing.get("profile")
            or ""
        ),
        "segmentation_brightness_threshold": (
            segmentation_report.get("brightness_threshold")
            or selected_preprocessing.get("brightness_threshold")
            or ""
        ),
        "segmentation_line_close_radius": (
            segmentation_report.get("line_close_radius")
            or selected_preprocessing.get("line_close_radius")
            or ""
        ),
        "segmentation_evaluation_mode": (
            segmentation_report.get("evaluation_mode")
            or selected_preprocessing.get("evaluation_mode")
            or ""
        ),
        "segmentation_quality_score": (
            segmentation_report.get("quality_score")
            or selected_preprocessing.get("quality_score")
            or selected_preprocessing.get("score")
            or ""
        ),
        "segmentation_playable_score": (
            segmentation_report.get("playable_score")
            or selected_preprocessing.get("playable_score")
            or selected_preprocessing.get("candidate_playable_score")
            or ""
        ),
        "segmentation_lost_color_pct": (
            segmentation_report.get("lost_color_pct")
            or selected_preprocessing.get("lost_color_pct")
            or ""
        ),
        "display_line_selected": display_report.get("selected", ""),
        "display_line_fallback_to_source": truthy_cell(display_report.get("fallback_to_source")),
        "display_line_uses_segmentation_score": truthy_cell(
            display_report.get("uses_segmentation_score")
        ),
        "display_line_candidate_mae": display_report.get("candidate_mae", ""),
        "display_line_candidate_changed_pct": display_report.get("candidate_changed_pct", ""),
        "display_line_stroke_lightened_pct": display_report.get("stroke_lightened_pct", ""),
        "display_line_stroke_lightened_mae": display_report.get("stroke_lightened_mae", ""),
        "display_line_stroke_pixel_count": display_report.get("stroke_pixel_count", ""),
        "display_line_stroke_guard_failed": truthy_cell(
            display_report.get("stroke_guard_failed")
        ),
        "ink_recovery_overreach": bool_cell(ink_recovery_overreach),
        "source_line_path": source_path or "",
        "debug_source_line_path": debug_source_path or "",
        "display_line_path": display_path or "",
        "segmentation_line_path": segmentation_path or "",
        "source_line_mae": number_cell(source_compare["mae"]),
        "source_line_changed_pct": number_cell(source_compare["changed_pct"]),
        "display_line_mae": number_cell(display_compare["mae"]),
        "display_line_changed_pct": number_cell(display_compare["changed_pct"]),
        "segmentation_line_mae": number_cell(segmentation_compare["mae"]),
        "segmentation_line_changed_pct": number_cell(segmentation_compare["changed_pct"]),
        "source_dark_pct_lt_80": number_cell(source_stats["dark_pct_lt_80"]),
        "display_dark_pct_lt_80": number_cell(display_compare["dark_pct_lt_80"]),
        "segmentation_dark_pct_lt_80": number_cell(segmentation_compare["dark_pct_lt_80"]),
        "source_ink_pct_lt_180": number_cell(source_stats["ink_pct_lt_180"]),
        "display_ink_pct_lt_180": number_cell(display_compare["ink_pct_lt_180"]),
        "segmentation_ink_pct_lt_180": number_cell(segmentation_compare["ink_pct_lt_180"]),
        "recovered_ink_pixel_count": recovered_count,
        "recovered_ink_pixel_pct": recovered_pct_value,
        "line_display_degraded": bool_cell(display_degraded),
        "segmentation_line_aggressive": bool_cell(segmentation_aggressive),
        "missing_source_line": bool_cell(source_path is None),
        "missing_display_line": bool_cell(display_path is None),
        "missing_segmentation_line": bool_cell(segmentation_path is None),
        "path": level_dir,
    }
    row["line_issue_tags"] = line_issue_tags(row)
    return row


def ensure_parent_dir(path):
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)


def write_csv(rows, output_path):
    ensure_parent_dir(output_path)
    with open(output_path, "w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def encode_image_data_uri(source_path, size=THUMB_SIZE):
    if not source_path or not os.path.exists(source_path):
        return None

    buffer = io.BytesIO()
    with Image.open(source_path) as image:
        image = image.convert("RGB")
        image.thumbnail((size, size), Image.LANCZOS)
        image.save(buffer, "WEBP", quality=THUMB_QUALITY, method=6)

    payload = base64.b64encode(buffer.getvalue()).decode("ascii")
    return f"data:image/webp;base64,{payload}"


def level_asset_path(row, file_name):
    level_dir = row.get("path") or ""
    return os.path.join(level_dir, file_name) if level_dir else ""


def image_cell(row, source_path, caption, asset_ref):
    data_uri = encode_image_data_uri(source_path)
    if data_uri is None:
        return (
            '<figure class="shot missing">'
            f'<div class="placeholder">thiếu<br>{escape(caption)}</div>'
            f"<figcaption>{escape(caption)}</figcaption>"
            f'<div class="path">{escape(asset_ref)}</div>'
            "</figure>"
        )
    return (
        '<figure class="shot">'
        f'<img src="{data_uri}" alt="{escape(caption)}" loading="lazy">'
        f"<figcaption>{escape(caption)}</figcaption>"
        f'<div class="path">{escape(asset_ref)}</div>'
        "</figure>"
    )


def row_priority(row):
    display_bad = row.get("line_display_degraded") == "Y"
    segmentation_bad = row.get("segmentation_line_aggressive") == "Y"
    try:
        display_changed = float(row.get("display_line_changed_pct") or 0)
    except (TypeError, ValueError):
        display_changed = 0.0
    try:
        segmentation_changed = float(row.get("segmentation_line_changed_pct") or 0)
    except (TypeError, ValueError):
        segmentation_changed = 0.0
    return (
        0 if display_bad or segmentation_bad else 1,
        -display_changed,
        -segmentation_changed,
        row.get("level_key", ""),
    )


def metric_table(row):
    metrics = [
        ("issue tags", row.get("line_issue_tags")),
        ("segmentation profile", row.get("segmentation_selected_profile")),
        ("segmentation threshold", row.get("segmentation_brightness_threshold")),
        ("segmentation close radius", row.get("segmentation_line_close_radius")),
        ("segmentation score", row.get("segmentation_quality_score")),
        ("display selected", row.get("display_line_selected")),
        ("display fallback", row.get("display_line_fallback_to_source")),
        ("display uses segmentation score", row.get("display_line_uses_segmentation_score")),
        ("display changed %", row.get("display_line_changed_pct")),
        ("display MAE", row.get("display_line_mae")),
        ("display candidate changed %", row.get("display_line_candidate_changed_pct")),
        ("display candidate MAE", row.get("display_line_candidate_mae")),
        ("display stroke lightened %", row.get("display_line_stroke_lightened_pct")),
        ("display stroke guard failed", row.get("display_line_stroke_guard_failed")),
        ("segmentation changed %", row.get("segmentation_line_changed_pct")),
        ("segmentation MAE", row.get("segmentation_line_mae")),
        ("recovered ink %", row.get("recovered_ink_pixel_pct")),
        ("source ink <180 %", row.get("source_ink_pct_lt_180")),
        ("display ink <180 %", row.get("display_ink_pct_lt_180")),
        ("segmentation ink <180 %", row.get("segmentation_ink_pct_lt_180")),
    ]
    return "".join(
        f"<tr><td>{escape(label)}</td><td>{escape(str(value))}</td></tr>"
        for label, value in metrics
    )


def write_html_report(rows, output_path):
    ensure_parent_dir(output_path)
    sorted_rows = sorted(rows, key=row_priority)
    cards = []
    for row in sorted_rows:
        level_key = row.get("level_key", "")
        images = [
            (
                row.get("source_line_path", ""),
                "Data line",
                row.get("source_line_path", ""),
            ),
            (
                row.get("debug_source_line_path", ""),
                f"source line ({row.get('source_line_asset_key', '-')})",
                f"{level_key}/{os.path.basename(row.get('debug_source_line_path', ''))}",
            ),
            (
                row.get("display_line_path", ""),
                f"display line ({row.get('display_line_asset_key', '-')})",
                f"{level_key}/{os.path.basename(row.get('display_line_path', ''))}",
            ),
            (
                row.get("segmentation_line_path", ""),
                f"segmentation line ({row.get('segmentation_line_asset_key', '-')})",
                f"{level_key}/{os.path.basename(row.get('segmentation_line_path', ''))}",
            ),
            (
                level_asset_path(row, "debug_regions.png"),
                "debug_regions",
                f"{level_key}/debug_regions.png",
            ),
            (
                level_asset_path(row, "preview_colored.png"),
                "preview",
                f"{level_key}/preview_colored.png",
            ),
        ]
        shots = "".join(
            image_cell(row, source_path, caption, asset_ref)
            for source_path, caption, asset_ref in images
        )
        badges = []
        if row.get("line_display_degraded") == "Y":
            badges.append('<span class="badge bad">line_display_degraded</span>')
        if row.get("segmentation_line_aggressive") == "Y":
            badges.append('<span class="badge warn">segmentation_line_aggressive</span>')
        if row.get("ink_recovery_overreach") == "Y":
            badges.append('<span class="badge bad">ink_recovery_overreach</span>')
        if row.get("display_line_fallback_to_source") == "Y":
            badges.append('<span class="badge ok">display_line_fallback_source</span>')
        if not badges:
            badges.append('<span class="badge ok">ok</span>')
        cards.append(
            '<article class="level">'
            f'<div class="shots">{shots}</div>'
            '<div class="meta">'
            f'<h2>{escape(level_key)}</h2>'
            f'<div class="badges">{"".join(badges)}</div>'
            f"<table>{metric_table(row)}</table>"
            f'<div class="asset-path">{escape(row.get("path", ""))}</div>'
            "</div>"
            "</article>"
        )

    display_bad_count = sum(row.get("line_display_degraded") == "Y" for row in rows)
    segmentation_bad_count = sum(row.get("segmentation_line_aggressive") == "Y" for row in rows)
    html = (
        "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">"
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        "<title>Line degradation audit</title>"
        f"<style>{HTML_CSS}</style></head><body>"
        "<header><h1>Line degradation audit</h1>"
        f'<div class="sub">{len(rows)} level · '
        f"line_display_degraded={display_bad_count} · "
        f"segmentation_line_aggressive={segmentation_bad_count}</div>"
        "</header>"
        f'<main>{"".join(cards)}</main>'
        "</body></html>"
    )
    with open(output_path, "w", encoding="utf-8") as output_file:
        output_file.write(html)


HTML_CSS = """
:root { color-scheme: light dark; }
* { box-sizing: border-box; }
body { margin: 0; font: 14px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
       background: #f5f6f8; color: #18191b; }
@media (prefers-color-scheme: dark) { body { background: #15161a; color: #f1f2f4; } }
header { position: sticky; top: 0; z-index: 2; padding: 16px 22px; background: inherit;
         border-bottom: 1px solid #8a8f982e; }
h1 { margin: 0 0 4px; font-size: 20px; }
.sub { opacity: .72; font-size: 13px; }
main { padding: 18px 22px 48px; display: grid; gap: 16px; }
.level { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 18px; padding: 14px;
         border: 1px solid #8a8f982e; background: #fff; border-radius: 10px; }
@media (prefers-color-scheme: dark) { .level { background: #202229; } }
@media (max-width: 1200px) { .level { grid-template-columns: 1fr; } }
.shots { display: grid; grid-template-columns: repeat(6, minmax(120px, 1fr)); gap: 10px; }
@media (max-width: 1500px) { .shots { grid-template-columns: repeat(3, minmax(150px, 1fr)); } }
@media (max-width: 760px) { .shots { grid-template-columns: repeat(2, minmax(120px, 1fr)); } }
.shot { margin: 0; min-width: 0; }
.shot img, .placeholder { width: 100%; aspect-ratio: 1 / 1; object-fit: contain; display: block;
                          background: #fff; border: 1px solid #8a8f9826; border-radius: 6px; }
.placeholder { display: grid; place-items: center; text-align: center; opacity: .55; font-size: 12px; }
figcaption { margin-top: 5px; text-align: center; font-size: 12px; opacity: .72; }
.path { margin-top: 2px; text-align: center; font: 10px/1.3 ui-monospace, Menlo, monospace;
        opacity: .45; overflow-wrap: anywhere; }
.meta h2 { margin: 0 0 10px; font-size: 17px; }
.badges { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.badge { padding: 3px 8px; border-radius: 6px; font-size: 12px; background: #8a8f9826; }
.badge.bad { background: #ff475726; border: 1px solid #ff475766; }
.badge.warn { background: #ffa50226; border: 1px solid #ffa50266; }
.badge.ok { background: #2ed57324; border: 1px solid #2ed57366; }
table { border-collapse: collapse; width: 100%; font-size: 12px; }
td { padding: 3px 0; border-bottom: 1px solid #8a8f981c; }
td:first-child { opacity: .66; padding-right: 10px; }
td:last-child { text-align: right; font-variant-numeric: tabular-nums; }
.asset-path { margin-top: 10px; font: 11px/1.35 ui-monospace, Menlo, monospace;
              opacity: .55; overflow-wrap: anywhere; }
"""


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Audit how far generated display/segmentation line assets drift from Data line art."
        )
    )
    parser.add_argument(
        "assets_path",
        nargs="?",
        default=os.path.join("app", "src", "main", "assets"),
        help="Assets root hoặc một thư mục level có config.json.",
    )
    parser.add_argument(
        "--data-root",
        default="Data",
        help="Thư mục Data chứa line.png gốc để đối chiếu.",
    )
    parser.add_argument(
        "--output",
        default=os.path.join("outputs", "line_degradation_audit.csv"),
        help="CSV output path.",
    )
    parser.add_argument(
        "--html-output",
        default="",
        help="HTML output path để xem Data/debug/display/segmentation/debug_regions/preview cạnh nhau.",
    )
    parser.add_argument(
        "--display-changed-threshold",
        type=float,
        default=DEFAULT_DISPLAY_CHANGED_THRESHOLD,
        help="Ngưỡng %% pixel thay đổi để tag line_display_degraded.",
    )
    parser.add_argument(
        "--display-mae-threshold",
        type=float,
        default=DEFAULT_DISPLAY_MAE_THRESHOLD,
        help="Ngưỡng MAE để tag line_display_degraded.",
    )
    parser.add_argument(
        "--segmentation-changed-threshold",
        type=float,
        default=DEFAULT_SEGMENTATION_CHANGED_THRESHOLD,
        help="Ngưỡng %% pixel thay đổi để tag segmentation_line_aggressive.",
    )
    args = parser.parse_args()

    root_path = os.path.abspath(args.assets_path)
    rows = [
        audit_level(
            root_path=root_path,
            level_dir=level_dir,
            data_root=args.data_root,
            display_changed_threshold=args.display_changed_threshold,
            display_mae_threshold=args.display_mae_threshold,
            segmentation_changed_threshold=args.segmentation_changed_threshold,
        )
        for level_dir in iter_level_dirs(root_path)
    ]

    write_csv(rows, args.output)
    if args.html_output:
        write_html_report(rows, args.html_output)
    display_degraded_count = sum(row["line_display_degraded"] == "Y" for row in rows)
    segmentation_aggressive_count = sum(
        row["segmentation_line_aggressive"] == "Y" for row in rows
    )
    print(
        f"Wrote {len(rows)} rows to {args.output}. "
        f"line_display_degraded={display_degraded_count}, "
        f"segmentation_line_aggressive={segmentation_aggressive_count}"
    )
    if args.html_output:
        print(f"Wrote visual HTML report to {args.html_output}")


if __name__ == "__main__":
    main()
