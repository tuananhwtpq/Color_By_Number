import argparse
import base64
import csv
import io
import json
import os
from html import escape

from PIL import Image

try:
    from validate_assets import (
        find_reference_image,
        flatten_row,
        infer_category_level,
        iter_level_dirs,
        selected_profile_from_report,
    )
    from asset_quality import evaluate_level_dir, load_json
except ImportError:
    from tools.validate_assets import (
        find_reference_image,
        flatten_row,
        infer_category_level,
        iter_level_dirs,
        selected_profile_from_report,
    )
    from tools.asset_quality import evaluate_level_dir, load_json


def number(row, key, default=0.0):
    try:
        value = row.get(key, "")
        if value in ("", "-", None):
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def text(row, key):
    return row.get(key, "") or ""


def format_number(value, digits=1):
    if value in ("", "-", None):
        return ""
    try:
        return f"{float(value):.{digits}f}"
    except (TypeError, ValueError):
        return str(value)


def has_issue(row, code):
    return code in (text(row, "fail_reasons") + "," + text(row, "warnings"))


def classify_for_designer(row):
    largest = number(row, "largest_region_pct")
    hidden = number(row, "estimated_hidden_label_pct")
    tiny100 = number(row, "tiny_region_pct_lt_100")
    tiny200 = number(row, "tiny_region_pct_lt_200")
    untouchable = number(row, "untouchable_region_count")
    regions = number(row, "regions")
    similarity = number(row, "preview_similarity_score")
    detail_dependency = number(row, "detail_dependency_score")
    overmerge_risk = text(row, "overmerge_risk")
    region_drop = number(row, "region_count_drop_pct")
    final_regions = number(row, "final_region_count") or number(row, "regions")
    legitimacy = text(row, "giant_region_legitimacy")
    giant_std = text(row, "giant_region_reference_color_std")

    reasons = []
    priority = "P3"
    bucket = "Keep / low risk"
    action = "Keep"
    background = "No giant-background concern"
    tiny_detail = "Tiny detail acceptable"

    if overmerge_risk == "high":
        bucket = "Foreground over-merge"
        action = "Review/fix source line separation; do not accept based on preview similarity"
        priority = "P1"
        reasons.append(
            f"overmerge high, final regions {final_regions:.0f}, drop {region_drop:.1f}%, detail dependency {detail_dependency:.1f}"
        )
    elif overmerge_risk == "medium":
        bucket = "Over-merge review"
        action = "Inspect debug_regions against source before accepting"
        priority = "P2"
        reasons.append(f"overmerge medium, drop {region_drop:.1f}%")

    if largest > 55:
        if legitimacy == "intentional_flat_background":
            background = (
                "Large flat background: likely valid one-tap background if designer intended it"
            )
            if overmerge_risk != "high":
                bucket = "Background exception candidate"
                action = "Designer confirm background is intended; mark exception if yes"
                priority = "P2"
            reasons.append(f"largest {largest:.1f}% but flat reference std {giant_std}")
        elif legitimacy == "needs_review":
            background = "Large non-flat region: likely open line/merged content"
            bucket = "Designer line fix"
            action = "Fix/close line separation or simplify background region"
            priority = "P1"
            reasons.append(f"largest {largest:.1f}% with non-flat std {giant_std}")
        else:
            background = "Large region: inspect intended background vs line leak"
            bucket = "Review giant region"
            action = "Inspect source; decide exception vs line fix"
            priority = "P1"
            reasons.append(f"largest {largest:.1f}%")
    elif largest > 50:
        background = "Moderate large region warning; check one-tap completion feel"
        if bucket == "Keep / low risk":
            bucket = "Review giant region"
            action = "Quick visual check for background/merged region"
            priority = "P2"
        reasons.append(f"largest warning {largest:.1f}%")

    tiny_hard = hidden > 80 or tiny100 > 45
    tiny_severe = hidden > 90 or tiny100 > 60 or tiny200 > 75 or regions > 1000
    untouchable_issue = has_issue(row, "UNTOUCHABLE_REGIONS") or has_issue(
        row, "UNTOUCHABLE_REGIONS_WARNING"
    )
    if tiny_hard or untouchable_issue:
        parts = []
        if hidden > 80:
            parts.append(f"hidden labels {hidden:.1f}%")
        if tiny100 > 45:
            parts.append(f"tiny<100 {tiny100:.1f}%")
        if tiny200 > 60:
            parts.append(f"tiny<200 {tiny200:.1f}%")
        if untouchable_issue:
            parts.append(f"untouchable count {untouchable:.0f}")
        tiny_detail = "Too many unreadable/tiny targets: " + ", ".join(parts)
        if tiny_severe:
            bucket = "Tiny detail too dense"
            action = "Simplify/remove micro details or replace source; generator should not over-merge to pass"
            priority = "P1"
        elif bucket == "Keep / low risk":
            bucket = "Playable-at-zoom review"
            action = "Check max-zoom playability; fix source if truly unreadable/tiny"
            priority = "P2"
        reasons.extend(parts)

    if similarity < 93:
        if bucket == "Keep / low risk":
            bucket = "Color fidelity review"
            action = "Compare preview against color source"
            priority = "P2"
        reasons.append(f"similarity {similarity:.1f}%")

    if text(row, "grade") == "A" and not reasons:
        priority = "P4"
    elif text(row, "grade") == "B" and bucket == "Keep / low risk":
        bucket = "Soft warning"
        action = "Optional review; likely usable"
        priority = "P3"

    return {
        "priority": priority,
        "designer_status": "Needs Review" if priority in {"P1", "P2"} else "Optional",
        "owner": "Designer",
        "review_bucket": bucket,
        "designer_action": action,
        "background_interpretation": background,
        "tiny_detail_interpretation": tiny_detail,
        "why_flagged": "; ".join(dict.fromkeys(reasons)) or "No major issue",
        "exception_candidate": "Yes" if bucket == "Background exception candidate" else "No",
    }


def classify_compact_issue(row):
    largest = number(row, "largest_region_pct")
    top2 = number(row, "top_2_region_pct")
    hidden = number(row, "estimated_hidden_label_pct") or number(row, "hidden_label_pct")
    tiny100 = number(row, "tiny_region_pct_lt_100")
    region_drop = number(row, "region_count_drop_pct")
    final_regions = number(row, "final_region_count") or number(row, "regions")
    similarity = number(row, "preview_similarity_score")
    overmerge_risk = text(row, "overmerge_risk")
    legitimacy = text(row, "giant_region_legitimacy")

    tags = []
    if largest >= 25 or top2 >= 45:
        tags.append("large_regions")
    elif largest >= 18:
        tags.append("large_region_review")

    if region_drop > 80 or overmerge_risk == "high":
        tags.append("overmerge")
    elif region_drop > 65 or overmerge_risk == "medium":
        tags.append("overmerge_review")

    if hidden > 80 or tiny100 > 45:
        tags.append("tiny_regions")
    elif hidden > 50:
        tags.append("many_hidden_labels")

    if final_regions < 80:
        tags.append("too_few_regions")
    elif final_regions < 120:
        tags.append("low_region_count")

    if similarity and similarity < 93:
        tags.append("color_fidelity")

    if legitimacy == "intentional_flat_background" and "large_regions" in tags:
        tags.append("flat_background_possible")

    if "large_regions" in tags and "overmerge" in tags:
        decision = "replace_or_redraw"
    elif "tiny_regions" in tags or "too_few_regions" in tags:
        decision = "replace_or_redraw"
    elif any(tag in tags for tag in ("large_regions", "overmerge", "many_hidden_labels")):
        decision = "designer_review"
    elif any(tag.endswith("_review") for tag in tags) or "low_region_count" in tags:
        decision = "quick_review"
    else:
        decision = "keep"

    return {
        "decision": decision,
        "issue_tags": ";".join(dict.fromkeys(tags)) or "ok",
    }


def top_region_stats(level_dir):
    config_path = os.path.join(level_dir, "config.json")
    if not os.path.exists(config_path):
        return {"top_2_region_pct": "", "final_region_count_from_config": ""}
    try:
        config = load_json(config_path)
    except (OSError, json.JSONDecodeError):
        return {"top_2_region_pct": "", "final_region_count_from_config": ""}

    width = int(config.get("width") or 0)
    height = int(config.get("height") or 0)
    total_pixels = max(1, width * height)
    areas = sorted(
        (int(region.get("area") or 0) for region in config.get("regions", [])),
        reverse=True,
    )
    top_2_pct = round(sum(areas[:2]) * 100.0 / total_pixels, 2) if areas else ""
    return {
        "top_2_region_pct": top_2_pct,
        "final_region_count_from_config": len(areas),
    }


def collect_rows(assets_path, data_root, require_reference=False):
    root_path = os.path.abspath(assets_path)
    rows = []
    for level_dir in iter_level_dirs(root_path):
        category, level = infer_category_level(root_path, level_dir)
        reference_path = find_reference_image(data_root, category, level)
        report = evaluate_level_dir(
            level_dir,
            reference_path=reference_path,
            require_reference=require_reference,
        )
        debug_report = {}
        debug_path = os.path.join(level_dir, "debug_report.json")
        if os.path.exists(debug_path):
            debug_report = load_json(debug_path)
        row = {
            "name": f"{category}/{level}",
            "category": category,
            "level": level,
            "path": level_dir,
            "selected_profile": selected_profile_from_report(debug_report),
            **report,
        }
        flat = flatten_row(row)
        metrics = report.get("metrics", {})
        flat["giant_region_legitimacy"] = metrics.get("giant_region_legitimacy", "")
        flat["giant_region_reference_color_std"] = (
            json.dumps(metrics.get("giant_region_reference_color_std"), ensure_ascii=False)
            if metrics.get("giant_region_reference_color_std") is not None
            else ""
        )
        flat["background_region_candidate"] = metrics.get("background_region_candidate", "")
        flat["similarity"] = metrics.get("preview_similarity_score", "")
        flat["flat_similarity"] = metrics.get("flat_similarity_score", "")
        flat["detail_dependency_score"] = metrics.get("detail_dependency_score", "")
        flat["raw_region_count"] = metrics.get("raw_region_count", "")
        flat["final_region_count"] = metrics.get("final_region_count", metrics.get("total_regions", ""))
        flat["region_count_drop_pct"] = metrics.get("region_count_drop_pct", "")
        flat["largest_region_pct"] = metrics.get("largest_region_pct", flat.get("largest_region_pct", ""))
        flat["hidden_label_pct"] = metrics.get("hidden_label_pct", "")
        flat["tiny_region_pct_lt_100"] = metrics.get("tiny_region_pct_lt_100", "")
        flat["overmerge_risk"] = metrics.get("overmerge_risk", "")
        flat["level_key"] = f"{category}/{level}"
        flat.update(top_region_stats(level_dir))
        if not flat.get("final_region_count") and flat.get("final_region_count_from_config"):
            flat["final_region_count"] = flat["final_region_count_from_config"]
        flat.update(classify_for_designer(flat))
        flat.update(classify_compact_issue(flat))
        rows.append(flat)
    return rows


COMPACT_FIELDS = [
    "priority",
    "designer_action",
    "level_key",
    "decision",
    "issue_tags",
    "grade",
    "score",
    "final_region_count",
    "largest_region_pct",
    "top_2_region_pct",
    "region_count_drop_pct",
    "hidden_label_pct",
]


def compact_row(row):
    return {
        "priority": row.get("priority", ""),
        "designer_action": row.get("designer_action", ""),
        "level_key": row.get("level_key", ""),
        "decision": row.get("decision", ""),
        "issue_tags": row.get("issue_tags", ""),
        "grade": row.get("grade", ""),
        "score": format_number(row.get("score"), digits=0),
        "final_region_count": format_number(row.get("final_region_count"), digits=0),
        "largest_region_pct": format_number(row.get("largest_region_pct"), digits=1),
        "top_2_region_pct": format_number(row.get("top_2_region_pct"), digits=1),
        "region_count_drop_pct": format_number(row.get("region_count_drop_pct"), digits=1),
        "hidden_label_pct": format_number(row.get("hidden_label_pct"), digits=1),
    }


# Bản dịch chỉ dùng để HIỂN THỊ trong HTML. CSV vẫn giữ nguyên mã tiếng Anh để lọc/sort và
# để không phá các script khác đang đọc những giá trị này.
DECISION_VI = {
    "replace_or_redraw": "Cần vẽ lại hoặc đổi ảnh",
    "designer_review": "Designer xem lại",
    "quick_review": "Liếc qua một lượt",
    "keep": "Dùng được",
}

ISSUE_TAG_VI = {
    "large_regions": "Có mảng màu quá to",
    "large_region_review": "Mảng màu hơi to, nên xem",
    "overmerge": "Các vùng bị gộp quá nhiều",
    "overmerge_review": "Có dấu hiệu gộp vùng, nên xem",
    "tiny_regions": "Quá nhiều vùng vụn li ti",
    "many_hidden_labels": "Nhiều vùng nhỏ tới mức không hiện được số",
    "too_few_regions": "Quá ít vùng, tô vài nốt là xong",
    "low_region_count": "Số vùng hơi ít",
    "color_fidelity": "Màu lệch so với ảnh gốc",
    "flat_background_possible": "Nền phẳng, có thể là chủ ý",
    "ok": "Không có vấn đề gì",
}

ACTION_VI = {
    "Keep": "Giữ nguyên, dùng được.",
    "Optional review; likely usable":
        "Xem cũng được mà không xem cũng không sao, nhiều khả năng vẫn dùng tốt.",
    "Inspect debug_regions against source before accepting":
        "So bản đồ vùng với ảnh gốc trước khi duyệt.",
    "Review/fix source line separation; do not accept based on preview similarity":
        "Sửa nét ở ảnh gốc cho các mảng tách hẳn nhau. Đừng duyệt chỉ vì ảnh preview trông "
        "giống bản gốc.",
    "Designer confirm background is intended; mark exception if yes":
        "Xác nhận xem mảng nền lớn này có phải chủ ý không. Nếu đúng thì đánh dấu ngoại lệ.",
    "Fix/close line separation or simplify background region":
        "Khép kín nét bị hở, hoặc làm đơn giản lại vùng nền.",
    "Inspect source; decide exception vs line fix":
        "Xem lại ảnh gốc rồi quyết định: cho qua như ngoại lệ, hay sửa nét.",
    "Quick visual check for background/merged region":
        "Nhìn nhanh xem phần nền và mấy vùng bị gộp có ổn không.",
    "Simplify/remove micro details or replace source; generator should not over-merge to pass":
        "Bớt hoặc bỏ các chi tiết li ti, hoặc đổi ảnh gốc. Không nên để máy gộp vùng cho đạt "
        "chuẩn.",
    "Check max-zoom playability; fix source if truly unreadable/tiny":
        "Zoom hết cỡ thử xem có tô được không. Nếu vẫn quá nhỏ thì sửa ảnh gốc.",
    "Compare preview against color source": "So ảnh preview với ảnh màu gốc.",
}


def to_vietnamese(mapping, value):
    """Chưa có bản dịch thì trả nguyên văn — thêm mã mới sẽ hiện tiếng Anh chứ không mất chữ."""
    value = (value or "").strip()
    return mapping.get(value, value)


PRIORITY_ORDER = {"P1": 0, "P2": 1, "P3": 2, "P4": 3}
THUMB_SIZE = 400
REGION_MAP_ZOOM_SIZE = 1600
THUMB_QUALITY = 92
GALLERY_IMAGES = (
    ("preview_colored.png", "preview", "Preview đã tô"),
    ("debug_regions.png", "regions", "Bản đồ vùng"),
)


def sort_rows_for_review(rows):
    """Level cần sửa gấp nhất lên đầu: P1 trước, cùng priority thì điểm thấp trước."""
    return sorted(
        rows,
        key=lambda row: (
            PRIORITY_ORDER.get(text(row, "priority"), len(PRIORITY_ORDER)),
            number(row, "score", 999.0),
            text(row, "level_key"),
        ),
    )


def safe_file_name(name):
    """Giữ nguyên dấu cách của tên category, chỉ thay ký tự filesystem không nhận."""
    for bad in ("/", "\\", ":"):
        name = name.replace(bad, "_")
    return name.strip() or "unknown"


def encode_image_data_uri(source_path, size=THUMB_SIZE):
    """Thu nhỏ ảnh rồi trả về data URI base64 để nhúng thẳng vào HTML.

    Nhúng chứ không ghi ra file rời: designer chỉ nhận đúng 1 file .html qua chat/mail là
    xem được ngay, không phải giải nén và không có đường dẫn nào trỏ về máy khác.
    """
    if not os.path.exists(source_path):
        return None

    buffer = io.BytesIO()
    with Image.open(source_path) as image:
        image = image.convert("RGB")
        image.thumbnail((size, size), Image.LANCZOS)
        # WEBP q92 thay vì PNG: cùng độ nét ở biên vùng (designer cần soi đúng chỗ này)
        # nhưng 77KB thay vì 320KB, nên nhúng base64 vào HTML vẫn gửi được.
        image.save(buffer, "WEBP", quality=THUMB_QUALITY, method=6)

    payload = base64.b64encode(buffer.getvalue()).decode("ascii")
    return f"data:image/webp;base64,{payload}"


def gallery_cell(row, file_name, suffix, caption):
    """Một ô ảnh: thumbnail nhúng sẵn, click phóng to, kèm đường dẫn asset dạng chữ."""
    category = text(row, "category")
    level = text(row, "level")
    source_path = os.path.abspath(os.path.join(text(row, "path"), file_name))
    asset_ref = f"{category}/{level}/{file_name}"

    data_uri = encode_image_data_uri(source_path)
    if data_uri is None:
        return (
            f'<figure class="shot missing"><div class="ph">thiếu {escape(file_name)}</div>'
            f"<figcaption>{escape(caption)}</figcaption>"
            f'<div class="path">{escape(asset_ref)}</div></figure>'
        )

    full_src = ""
    if file_name == "debug_regions.png":
        zoom_uri = encode_image_data_uri(source_path, size=REGION_MAP_ZOOM_SIZE)
        if zoom_uri is not None:
            full_src = f' data-full-src="{escape(zoom_uri, quote=True)}"'

    return (
        f'<figure class="shot"><img src="{data_uri}"{full_src} '
        f'alt="{escape(caption)} {escape(level)}" loading="lazy">'
        f"<figcaption>{escape(caption)}</figcaption>"
        f'<div class="path">{escape(asset_ref)}</div></figure>'
    )


GALLERY_CSS = """
:root { color-scheme: light dark; }
* { box-sizing: border-box; }
body { margin: 0; font: 14px/1.5 -apple-system, "Segoe UI", Roboto, sans-serif;
       background: #f6f6f8; color: #1c1c1e; }
@media (prefers-color-scheme: dark) { body { background: #16161a; color: #ececf1; } }
header { position: sticky; top: 0; z-index: 5; padding: 16px 24px;
         background: inherit; border-bottom: 1px solid #8883; }
h1 { margin: 0 0 4px; font-size: 20px; }
.sub { opacity: .7; font-size: 13px; }
.filters { margin-top: 12px; display: flex; flex-wrap: wrap; gap: 8px; }
.filters button { padding: 6px 14px; border-radius: 999px; cursor: pointer;
                  border: 1px solid #8886; background: transparent; color: inherit; font: inherit; }
.filters button[aria-pressed="true"] { background: #6c5ce7; border-color: #6c5ce7; color: #fff; }
main { padding: 20px 24px 60px; display: flex; flex-direction: column; gap: 18px; }
.level { display: flex; flex-wrap: wrap; gap: 20px; padding: 16px; border-radius: 14px;
         background: #fff; border: 1px solid #8882; }
@media (prefers-color-scheme: dark) { .level { background: #202027; } }
.level[hidden] { display: none; }
.shots { display: flex; gap: 14px; }
.shot { margin: 0; }
.shot img { display: block; width: 260px; height: auto; border-radius: 8px; background: #fff;
            cursor: zoom-in; }
.path { margin-top: 3px; font: 11px/1.4 ui-monospace, Menlo, Consolas, monospace;
        text-align: center; opacity: .5; user-select: all; }
#lightbox { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center;
            background: #000c; cursor: zoom-out; }
#lightbox[hidden] { display: none; }
#lightbox img { max-width: 92vw; max-height: 92vh; border-radius: 8px; }
.shot .ph { width: 260px; height: 260px; display: grid; place-items: center;
            border: 1px dashed #8886; border-radius: 8px; opacity: .6; font-size: 12px; }
figcaption { margin-top: 6px; font-size: 12px; opacity: .65; text-align: center; }
.meta { flex: 1 1 260px; min-width: 240px; }
.key { font-size: 17px; font-weight: 600; margin-bottom: 10px; }
.badges { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.badge { padding: 3px 10px; border-radius: 6px; font-size: 12px; background: #8882; }
.badge.P1 { background: #ff4757; color: #fff; }
.badge.P2 { background: #ffa502; color: #1c1c1e; }
.badge.P3 { background: #70a1ff; color: #1c1c1e; }
.badge.P4 { background: #2ed573; color: #1c1c1e; }
table { border-collapse: collapse; font-size: 13px; }
td { padding: 2px 0; vertical-align: top; }
td:first-child { opacity: .6; padding-right: 14px; white-space: nowrap; }
.tags { margin-top: 12px; display: flex; flex-wrap: wrap; gap: 6px; }
.tag { padding: 3px 10px; border-radius: 6px; font-size: 12px; background: #ffa50226;
       border: 1px solid #ffa50255; }
.action { margin-top: 8px; padding: 10px 12px; border-radius: 8px; background: #8881;
          font-size: 13px; overflow-wrap: anywhere; }
"""

GALLERY_JS = """
// Ảnh nhúng dạng data: URI, mà Chrome chặn mở thẳng data: URL ở tab mới -> phải dựng lại
// thành blob. Popup bị chặn thì rơi về lớp phủ xem ngay trong trang, không im lặng chết.
function openFullSize(img) {
  const source = img.dataset.fullSrc || img.src;
  const [meta, payload] = source.split(',');
  const mime = meta.slice(5).split(';')[0];
  const bytes = Uint8Array.from(atob(payload), char => char.charCodeAt(0));
  const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
  if (!window.open(url, '_blank')) {
    const box = document.getElementById('lightbox');
    box.querySelector('img').src = url;
    box.hidden = false;
  }
}

document.querySelectorAll('.shot img').forEach(img => {
  img.addEventListener('click', () => openFullSize(img));
});

document.getElementById('lightbox').addEventListener('click', event => {
  event.currentTarget.hidden = true;
});

const buttons = document.querySelectorAll('.filters button');
buttons.forEach(button => button.addEventListener('click', () => {
  const wanted = button.dataset.decision;
  buttons.forEach(other => other.setAttribute('aria-pressed', other === button));
  document.querySelectorAll('.level').forEach(level => {
    level.hidden = wanted !== 'all' && level.dataset.decision !== wanted;
  });
}));
"""

METRIC_LABELS = (
    ("final_region_count", "Số vùng", 0),
    ("largest_region_pct", "Vùng lớn nhất %", 1),
    ("top_2_region_pct", "Top 2 vùng %", 1),
    ("region_count_drop_pct", "Giảm số vùng %", 1),
    ("hidden_label_pct", "Số bị ẩn %", 1),
)


def write_category_html(rows, category, output_dir):
    """Trang xem nhanh 1 category: 2 ảnh cạnh nhau + metric, sắp theo priority."""
    decisions = sorted({text(row, "decision") or "unknown" for row in rows})
    filters = ['<button data-decision="all" aria-pressed="true">Tất cả</button>']
    filters += [
        f'<button data-decision="{escape(decision)}">'
        f"{escape(to_vietnamese(DECISION_VI, decision))}</button>"
        for decision in decisions
    ]

    cards = []
    for row in rows:
        priority = text(row, "priority") or "P4"
        shots = "".join(
            gallery_cell(row, file_name, suffix, caption)
            for file_name, suffix, caption in GALLERY_IMAGES
        )
        metrics = "".join(
            f"<tr><td>{escape(label)}</td><td>{escape(format_number(row.get(key), digits))}</td></tr>"
            for key, label, digits in METRIC_LABELS
        )
        tags = "".join(
            f'<span class="tag">{escape(to_vietnamese(ISSUE_TAG_VI, tag))}</span>'
            for tag in text(row, "issue_tags").split(";")
            if tag.strip()
        )
        cards.append(
            f'<article class="level" data-decision="{escape(text(row, "decision") or "unknown")}">'
            f'<div class="shots">{shots}</div>'
            f'<div class="meta"><div class="key">{escape(text(row, "level_key"))}</div>'
            f'<div class="badges"><span class="badge {escape(priority)}">{escape(priority)}</span>'
            f'<span class="badge">{escape(to_vietnamese(DECISION_VI, text(row, "decision")))}</span>'
            f'<span class="badge">hạng {escape(text(row, "grade"))}</span>'
            f'<span class="badge">điểm {escape(format_number(row.get("score"), 0))}</span></div>'
            f"<table>{metrics}</table>"
            f'<div class="tags">{tags}</div>'
            f'<div class="action">{escape(to_vietnamese(ACTION_VI, text(row, "designer_action")))}'
            f"</div></div></article>"
        )

    html = (
        "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">"
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>{escape(category)} — asset review</title>"
        f"<style>{GALLERY_CSS}</style></head><body>"
        f"<header><h1>{escape(category)}</h1>"
        f'<div class="sub">{len(rows)} level · xếp theo mức ưu tiên · bấm vào ảnh để phóng to</div>'
        f'<div class="filters">{"".join(filters)}</div></header>'
        f'<main>{"".join(cards)}</main>'
        f'<div id="lightbox" hidden><img alt="Ảnh phóng to"></div>'
        f"<script>{GALLERY_JS}</script></body></html>"
    )

    output_path = os.path.join(output_dir, f"{safe_file_name(category)}.html")
    with open(output_path, "w", encoding="utf-8") as output_file:
        output_file.write(html)
    return output_path


def write_per_category(rows, output_dir, compact=True):
    """Mỗi category một cặp CSV + HTML, tên file lấy đúng tên folder category."""
    os.makedirs(os.path.abspath(output_dir), exist_ok=True)
    grouped = {}
    for row in rows:
        grouped.setdefault(text(row, "category") or "unknown", []).append(row)

    written = []
    for category in sorted(grouped):
        sorted_rows = sort_rows_for_review(grouped[category])
        csv_path = os.path.join(output_dir, f"{safe_file_name(category)}.csv")
        write_csv(sorted_rows, csv_path, compact=compact)
        html_path = write_category_html(sorted_rows, category, output_dir)
        written.append((category, len(sorted_rows), csv_path, html_path))
    return written


def write_csv(rows, output_path, compact=True):
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    if compact:
        with open(output_path, "w", newline="", encoding="utf-8") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=COMPACT_FIELDS)
            writer.writeheader()
            writer.writerows(compact_row(row) for row in rows)
        return

    preferred_fields = [
        "decision",
        "issue_tags",
        "priority",
        "designer_status",
        "owner",
        "review_bucket",
        "designer_action",
        "level_key",
        "category",
        "level",
        "grade",
        "score",
        "similarity",
        "preview_similarity_score",
        "flat_similarity",
        "flat_similarity_score",
        "detail_dependency_score",
        "raw_region_count",
        "final_region_count",
        "region_count_drop_pct",
        "largest_region_pct",
        "top_2_region_pct",
        "giant_region_legitimacy",
        "giant_region_reference_color_std",
        "hidden_label_pct",
        "tiny_region_pct_lt_100",
        "tiny_region_pct_lt_200",
        "estimated_hidden_label_pct",
        "overmerge_risk",
        "untouchable_region_count",
        "regions",
        "colors",
        "playable_score",
        "fail_reasons",
        "warnings",
        "background_interpretation",
        "tiny_detail_interpretation",
        "why_flagged",
        "exception_candidate",
        "path",
    ]
    fields = preferred_fields + [key for key in rows[0].keys() if key not in preferred_fields]
    with open(output_path, "w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(
        description="Export Color By Number asset quality report for designer review."
    )
    parser.add_argument(
        "assets_path",
        nargs="?",
        default=os.path.join("app", "src", "main", "assets"),
        help="Assets root hoặc một folder level có config.json.",
    )
    parser.add_argument(
        "--data-root",
        default="Data",
        help="Data root dùng để tìm color/ref image gốc.",
    )
    parser.add_argument(
        "--output",
        default=os.path.join("outputs", "color_by_number_asset_review", "color_by_number_designer_review.csv"),
        help="CSV output path.",
    )
    parser.add_argument(
        "--require-reference",
        action="store_true",
        help="Fail report nếu không tìm thấy ảnh màu gốc.",
    )
    parser.add_argument(
        "--wide",
        action="store_true",
        help="Xuất CSV đầy đủ tất cả metric cũ. Mặc định là bản gọn 12 cột để lọc designer.",
    )
    parser.add_argument(
        "--per-category",
        action="store_true",
        help="Mỗi category một CSV (Animal.csv, Manga.csv…) kèm HTML gallery để designer xem "
             "preview_colored và debug_regions cạnh nhau.",
    )
    parser.add_argument(
        "--output-dir",
        default=os.path.join("outputs", "color_by_number_asset_review"),
        help="Thư mục output cho chế độ --per-category.",
    )
    args = parser.parse_args()

    rows = collect_rows(args.assets_path, args.data_root, require_reference=args.require_reference)
    if not rows:
        raise SystemExit("Không tìm thấy level asset nào để export.")

    if args.per_category:
        written = write_per_category(rows, args.output_dir, compact=not args.wide)
        print(f"Đã ghi {len(written)} category vào {args.output_dir}")
        for category, count, csv_path, html_path in written:
            print(f"  - {category}: {count} level")
            print(f"      {os.path.basename(csv_path)} / {os.path.basename(html_path)}")
        return

    write_csv(rows, args.output, compact=not args.wide)

    counts = {}
    for row in rows:
        counts[row["review_bucket"]] = counts.get(row["review_bucket"], 0) + 1
    print(f"Đã ghi designer review CSV: {args.output}")
    print(f"Tổng level: {len(rows)}")
    for bucket, count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
        print(f"  - {bucket}: {count}")


if __name__ == "__main__":
    main()
