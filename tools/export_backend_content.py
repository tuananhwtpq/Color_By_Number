"""Xuất toàn bộ nội dung của app thành gói dữ liệu để backend import.

Gói đầu ra gồm hai phần:

    content/   các file JSON mô tả entity (backend import vào database)
    files/     file thật để đẩy lên CDN (ảnh, config.json, animation Lottie)

Mọi đường dẫn trong JSON đều là đường dẫn tương đối so với thư mục ``files/``.
Backend ghép với ``cdnBaseUrl`` của mình để ra URL cuối cùng, còn ``createdAt`` /
``updatedAt`` thì backend tự sinh lúc import.

Nguồn dữ liệu được gom từ ba nơi trong repo:

    app/src/main/assets/            tranh tô màu (category + collection)
    app/src/main/res/raw/           animation Lottie của Color Realm
    app/src/main/java/.../data/     danh mục achievement và realm khai báo trong Kotlin

Cách chạy (dùng bản python có Pillow):

    /usr/bin/python3 tools/export_backend_content.py --webp --zip

Các file KHÔNG được xuất vì runtime không đọc tới: debug_*.png, debug_report.json,
preview_colored.png, line_render.png.
"""

import argparse
import base64
import binascii
import datetime
import hashlib
import io
import json
import os
import re
import shutil
import unicodedata
import zipfile

try:
    from PIL import Image
except ImportError:  # pragma: no cover - phụ thuộc môi trường
    Image = None


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))

DEFAULT_ASSETS = os.path.join("app", "src", "main", "assets")
DEFAULT_RES = os.path.join("app", "src", "main", "res")
DEFAULT_SRC = os.path.join("app", "src", "main", "java", "com", "example", "baseproject")

COLLECTION_ROOT = "Collection"
SKIP_DIRS = {"images", "webkit"}

# role -> (tên file mặc định, khoá trong config["assets"], cho phép nén lossy)
#
# MASK và LINE phải giữ nguyên PNG không mất dữ liệu: mỗi pixel là một mã vùng, nén lossy
# sẽ làm sai logic tô màu.
RUNTIME_ASSETS = (
    ("LINE", "line.png", "line", False),
    ("MASK", "mask.png", "mask", False),
    ("DISPLAY_LINE", "display_line.png", "display_line", True),
    ("DETAIL", "detail.png", "detail", True),
)

# Ảnh nét gốc dùng để sinh thumbnail cho lưới danh sách. Không bao giờ dùng
# preview_colored.png: đó là bản đã tô xong, hiện ra là lộ đáp án.
THUMBNAIL_SOURCES = ("debug_source_line.png", "display_line.png", "line.png")

MIME_BY_EXTENSION = {
    ".png": "image/png",
    ".webp": "image/webp",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".json": "application/json",
}

REMOTE_CONFIG_DEFAULTS = (
    ("paint_drop_reward_per_picture", "integer", 20,
     "Số giọt sơn thưởng khi tô xong một bức."),
    ("hint_reward_per_achievement", "integer", 1,
     "Số hint thưởng khi mở khoá một achievement."),
    ("hint_start_amount", "integer", 5,
     "Số hint tặng người dùng mới."),
    ("hint_cost_paint_drop", "integer", 50,
     "Giá đổi giọt sơn lấy một hint."),
)

ACHIEVEMENT_RULES = {
    "ConsecutiveDaysOpened": "CONSECUTIVE_DAYS_OPENED",
    "ArtworksCompleted": "ARTWORKS_COMPLETED",
    "ArtworkInCategory": "ARTWORK_IN_CATEGORY",
    "CollectionCompleted": "COLLECTION_COMPLETED",
    "RealmsUnlocked": "REALMS_UNLOCKED",
    "HintsUsed": "HINTS_USED",
    "DailyArtworksCompleted": "DAILY_ARTWORKS_COMPLETED",
}

warnings = []


def warn(message):
    warnings.append(message)
    print("  ! " + message)


# ---------------------------------------------------------------- tiện ích chung


def slugify(value):
    normalized = unicodedata.normalize("NFKD", value)
    ascii_value = normalized.encode("ascii", "ignore").decode("ascii")
    slug = re.sub(r"[^a-z0-9]+", "-", ascii_value.lower()).strip("-")
    return slug or "item"


def load_json(path):
    with open(path, encoding="utf-8") as input_file:
        return json.load(input_file)


def write_json(path, payload):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as output_file:
        json.dump(payload, output_file, ensure_ascii=False, indent=2)
        output_file.write("\n")


def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as input_file:
        for chunk in iter(lambda: input_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bundle_version(entries):
    """Băm tổng của một bộ file: đổi bất kỳ file nào là đổi chuỗi này."""
    digest = hashlib.sha256()
    for entry in sorted(entries, key=lambda item: item["path"]):
        digest.update(entry["path"].encode("utf-8"))
        digest.update(b"\0")
        digest.update(entry["sha256"].encode("utf-8"))
        digest.update(b"\0")
    return "sha256:" + digest.hexdigest()


def public_asset(entry):
    """Chỉ giữ metadata app thật sự cần để tải asset từ CDN."""
    return {
        "role": entry["role"],
        "path": entry["path"],
        "mimeType": entry["mimeType"],
    }


def image_size(path):
    if Image is None:
        return None, None
    try:
        with Image.open(path) as image:
            return image.width, image.height
    except Exception:
        return None, None


def sort_order_from_name(name, fallback):
    match = re.search(r"\d+", name)
    return int(match.group()) if match else fallback


# ---------------------------------------------------------------- ghi file ra gói


class FileWriter:
    """Copy (hoặc convert) file vào thư mục files/ và trả về mô tả của nó."""

    def __init__(self, output_dir, use_webp, webp_quality):
        self.files_dir = os.path.join(output_dir, "files")
        self.use_webp = use_webp
        self.webp_quality = webp_quality

    def _absolute(self, relative_path):
        return os.path.join(self.files_dir, relative_path)

    def describe(self, relative_path):
        absolute_path = self._absolute(relative_path)
        extension = os.path.splitext(relative_path)[1].lower()
        width, height = image_size(absolute_path) if extension != ".json" else (None, None)
        entry = {
            "path": relative_path.replace(os.sep, "/"),
            "mimeType": MIME_BY_EXTENSION.get(extension, "application/octet-stream"),
            "sizeBytes": os.path.getsize(absolute_path),
            "sha256": file_sha256(absolute_path),
        }
        if width:
            entry["width"] = width
            entry["height"] = height
        return entry

    def copy(self, source_path, relative_path):
        destination = self._absolute(relative_path)
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        shutil.copy2(source_path, destination)
        return self.describe(relative_path)

    def copy_image(self, source_path, relative_path, allow_lossy):
        """Copy ảnh, chuyển sang WebP khi được phép và người dùng bật --webp."""
        if not (self.use_webp and allow_lossy and Image is not None):
            return self.copy(source_path, relative_path)

        relative_path = os.path.splitext(relative_path)[0] + ".webp"
        destination = self._absolute(relative_path)
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        with Image.open(source_path) as image:
            image.save(destination, "WEBP", quality=self.webp_quality, method=6)
        return self.describe(relative_path)

    def write_thumbnail(self, source_path, relative_path, max_size):
        if Image is None:
            return None
        extension = ".webp" if self.use_webp else ".png"
        relative_path = os.path.splitext(relative_path)[0] + extension
        destination = self._absolute(relative_path)
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        with Image.open(source_path) as image:
            image = image.copy()
            image.thumbnail((max_size, max_size), Image.LANCZOS)
            if extension == ".webp":
                image.save(destination, "WEBP", quality=self.webp_quality, method=6)
            else:
                image.save(destination, "PNG", optimize=True)
        return self.describe(relative_path)

    def write_bytes(self, data, relative_path):
        destination = self._absolute(relative_path)
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        with open(destination, "wb") as output_file:
            output_file.write(data)
        return self.describe(relative_path)

    def write_json(self, payload, relative_path):
        destination = self._absolute(relative_path)
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        with open(destination, "w", encoding="utf-8") as output_file:
            json.dump(payload, output_file, ensure_ascii=False, indent=2)
            output_file.write("\n")
        return self.describe(relative_path)

    def write_image_bytes(self, data, relative_path, max_size=None):
        """Ghi ảnh lấy từ bộ nhớ, có thu nhỏ và chuyển WebP như các ảnh khác."""
        if Image is None:
            return self.write_bytes(data, relative_path)

        if self.use_webp:
            relative_path = os.path.splitext(relative_path)[0] + ".webp"
        destination = self._absolute(relative_path)
        os.makedirs(os.path.dirname(destination), exist_ok=True)

        with Image.open(io.BytesIO(data)) as image:
            image = image.copy()
            if max_size:
                image.thumbnail((max_size, max_size), Image.LANCZOS)
            if self.use_webp:
                image.save(destination, "WEBP", quality=self.webp_quality, method=6)
            else:
                image.save(destination, "PNG", optimize=True)
        return self.describe(relative_path)


# ---------------------------------------------------------------- level


def palette_size_of(config):
    regions = config.get("regions") or []
    if regions:
        return len({region["number"] for region in regions})
    stats = config.get("stats") or {}
    return config.get("unique_numbers") or stats.get("unique_numbers") or 0


def configured_name(config, key, default_name):
    return (config.get("assets") or {}).get(key) or default_name


def build_level(level_dir, level_id, group_type, group_id, sort_order,
                writer, thumbnail_size, min_app_version):
    config_path = os.path.join(level_dir, "config.json")
    config = load_json(config_path)
    base = os.path.join("levels", level_id)

    assets = []
    exported_config_assets = {}
    for role, default_name, config_key, allow_lossy in RUNTIME_ASSETS:
        source_path = os.path.join(level_dir, configured_name(config, config_key, default_name))

        if not os.path.exists(source_path) and role == "DISPLAY_LINE":
            # Asset sinh trước khi có display_line thì vẫn còn line_render.png.
            source_path = os.path.join(level_dir, "line_render.png")

        if not os.path.exists(source_path):
            raise FileNotFoundError("Thiếu file bắt buộc: %s (%s)" % (source_path, role))

        entry = writer.copy_image(
            source_path,
            os.path.join(base, os.path.basename(source_path)),
            allow_lossy,
        )
        entry["role"] = role
        assets.append(entry)
        exported_config_assets[config_key] = os.path.basename(entry["path"])

    exported_config = dict(config)
    exported_config["assets"] = exported_config_assets
    config_entry = writer.write_json(exported_config, os.path.join(base, "config.json"))
    files = [config_entry, *assets]

    thumbnail_source = next(
        (os.path.join(level_dir, name) for name in THUMBNAIL_SOURCES
         if os.path.exists(os.path.join(level_dir, name))),
        None,
    )
    if thumbnail_source is None:
        warn("Không tìm được ảnh nguồn để tạo thumbnail cho %s" % level_id)
        thumbnail = None
    else:
        thumbnail = writer.write_thumbnail(
            thumbnail_source, os.path.join(base, "thumbnail"), thumbnail_size
        )
        if thumbnail is None:
            warn("Thiếu Pillow nên không tạo được thumbnail cho %s" % level_id)
        else:
            files.append(thumbnail)

    payload = {
        "id": level_id,
        "groupType": group_type,
        "groupId": group_id,
        "name": None,
        "sortOrder": sort_order,
        "thumbnailPath": thumbnail["path"] if thumbnail else None,
        "configPath": config_entry["path"],
        "contentVersion": bundle_version(files),
        "isPremium": False,
        "isActive": True,
        "publishedAt": None,
    }
    if min_app_version is not None:
        payload["minAppVersion"] = min_app_version
    return payload


def iter_level_dirs(group_dir):
    for name in sorted(os.listdir(group_dir)):
        level_dir = os.path.join(group_dir, name)
        if os.path.isfile(os.path.join(level_dir, "config.json")):
            yield name, level_dir


# ---------------------------------------------------------------- category / collection


def export_categories_and_levels(assets_path, writer, thumbnail_size, min_app_version):
    categories = []
    levels = []

    for name in sorted(os.listdir(assets_path)):
        group_dir = os.path.join(assets_path, name)
        if not os.path.isdir(group_dir) or name in SKIP_DIRS or name == COLLECTION_ROOT:
            continue
        if name.startswith("."):
            continue

        category_id = slugify(name)
        level_dirs = list(iter_level_dirs(group_dir))
        if not level_dirs:
            continue

        for index, (level_name, level_dir) in enumerate(level_dirs, start=1):
            levels.append(build_level(
                level_dir=level_dir,
                level_id="%s-%s" % (category_id, level_name),
                group_type="CATEGORY",
                group_id=category_id,
                sort_order=sort_order_from_name(level_name, index),
                writer=writer,
                thumbnail_size=thumbnail_size,
                min_app_version=min_app_version,
            ))

        categories.append({
            "id": category_id,
            "name": {"en": name},
            "sortOrder": len(categories) + 1,
            "thumbnailPath": None,
            "levelCount": len(level_dirs),
            "isActive": True,
        })

    return categories, levels


def export_collections_and_levels(assets_path, writer, thumbnail_size, min_app_version):
    collection_root = os.path.join(assets_path, COLLECTION_ROOT)
    collections = []
    levels = []

    if not os.path.isdir(collection_root):
        warn("Không tìm thấy thư mục %s" % collection_root)
        return collections, levels

    for name in sorted(os.listdir(collection_root)):
        group_dir = os.path.join(collection_root, name)
        if not os.path.isdir(group_dir):
            continue

        collection_id = slugify(name)
        level_dirs = list(iter_level_dirs(group_dir))

        for index, (level_name, level_dir) in enumerate(level_dirs, start=1):
            levels.append(build_level(
                level_dir=level_dir,
                level_id="%s-%s" % (collection_id, level_name),
                group_type="COLLECTION",
                group_id=collection_id,
                sort_order=sort_order_from_name(level_name, index),
                writer=writer,
                thumbnail_size=thumbnail_size,
                min_app_version=min_app_version,
            ))

        cover = next(
            (os.path.join(group_dir, "thumbnail" + extension)
             for extension in (".png", ".webp", ".jpg")
             if os.path.exists(os.path.join(group_dir, "thumbnail" + extension))),
            None,
        )
        if cover is None:
            warn("Collection %s không có ảnh bìa thumbnail" % name)
            cover_entry = None
        else:
            cover_entry = writer.copy_image(
                cover, os.path.join("collections", collection_id, "cover.png"), True
            )

        collections.append({
            "id": collection_id,
            "title": {"en": name},
            "description": None,
            "thumbnailPath": cover_entry["path"] if cover_entry else None,
            "levelCount": len(level_dirs),
            "sortOrder": len(collections) + 1,
            "publishedAt": None,
            "isActive": True,
        })

    return collections, levels


# ---------------------------------------------------------------- realm


def extract_lottie_preview(animation_path):
    """Lấy ảnh nền nhúng base64 trong file Lottie ra làm ảnh preview tĩnh."""
    try:
        composition = load_json(animation_path)
    except ValueError:
        return None, None

    for asset in composition.get("assets") or []:
        source = asset.get("p")
        if not isinstance(source, str) or not source.startswith("data:"):
            continue
        header, _, payload = source.partition(",")
        try:
            data = base64.b64decode(payload)
        except (binascii.Error, ValueError):
            continue
        extension = ".webp" if "webp" in header else (".jpg" if "jpeg" in header else ".png")
        return data, extension
    return None, None


def parse_realm_catalog(src_path):
    catalog_path = os.path.join(src_path, "data", "Realm.kt")
    if not os.path.exists(catalog_path):
        warn("Không tìm thấy Realm.kt, bỏ qua phần realm")
        return []

    with open(catalog_path, encoding="utf-8") as input_file:
        source = input_file.read()

    pattern = re.compile(r'Realm\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*R\.raw\.(\w+)\s*\)')
    return [
        {"id": slugify(match.group(1)), "name": match.group(2), "rawName": match.group(3)}
        for match in pattern.finditer(source)
    ]


def export_realms(src_path, res_path, writer):
    realms = []
    for index, realm in enumerate(parse_realm_catalog(src_path), start=1):
        animation_path = os.path.join(res_path, "raw", realm["rawName"] + ".json")
        if not os.path.exists(animation_path):
            warn("Thiếu file animation cho realm %s" % realm["id"])
            continue

        base = os.path.join("realms", realm["id"])
        animation = writer.copy(animation_path, os.path.join(base, "animation.json"))

        preview_data, preview_extension = extract_lottie_preview(animation_path)
        if preview_data is None:
            warn("Realm %s không có ảnh nhúng để làm preview" % realm["id"])
            preview = None
        else:
            preview = writer.write_image_bytes(
                preview_data,
                os.path.join(base, "preview" + preview_extension),
                max_size=1080,
            )

        realms.append({
            "id": realm["id"],
            "name": {"en": realm["name"]},
            "animationPath": animation["path"],
            "animationMimeType": animation["mimeType"],
            "previewImagePath": preview["path"] if preview else None,
            "unlockType": "FREE" if index == 1 else "COMPLETED_LEVELS",
            "unlockValue": None if index == 1 else (index - 1) * 10,
            "sortOrder": index,
            "isActive": True,
        })
    return realms


# ---------------------------------------------------------------- achievement


def parse_android_strings(res_path):
    strings_path = os.path.join(res_path, "values", "strings.xml")
    with open(strings_path, encoding="utf-8") as input_file:
        source = input_file.read()

    pattern = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.S)
    return {
        match.group(1): match.group(2).replace("&amp;", "&").replace("\\'", "'")
        for match in pattern.finditer(source)
    }


def parse_achievement_catalog(src_path):
    catalog_path = os.path.join(src_path, "data", "AchievementCatalog.kt")
    with open(catalog_path, encoding="utf-8") as input_file:
        source = input_file.read()

    pattern = re.compile(
        r'AchievementDefinition\(\s*'
        r'id = "(?P<id>[^"]+)",\s*'
        r'titleRes = R\.string\.(?P<title>\w+),\s*'
        r'descriptionRes = R\.string\.(?P<description>\w+),\s*'
        r'targetCount = (?P<target>\d+),\s*'
        r'rule = AchievementRule\.(?P<rule>\w+)(?:\("(?P<ruleArg>[^"]*)"\))?'
        r'(?:,\s*iconRes = R\.drawable\.(?P<icon>\w+))?'
        r'(?:,\s*iconCompletedRes = R\.drawable\.(?P<iconDone>\w+))?'
    )
    return [match.groupdict() for match in pattern.finditer(source)]


def find_drawable(res_path, name):
    if not name:
        return None
    for extension in (".webp", ".png", ".jpg"):
        candidate = os.path.join(res_path, "drawable", name + extension)
        if os.path.exists(candidate):
            return candidate
    return None


def export_achievements(src_path, res_path, writer):
    strings = parse_android_strings(res_path)
    achievements = []

    for index, definition in enumerate(parse_achievement_catalog(src_path), start=1):
        rule_name = definition["rule"]
        if rule_name not in ACHIEVEMENT_RULES:
            warn("Rule chưa biết: %s" % rule_name)
            continue

        rule_argument = definition.get("ruleArg")
        icons = {}
        for field, suffix in (("icon", "iconLockedPath"), ("iconDone", "iconUnlockedPath")):
            source_path = find_drawable(res_path, definition.get(field))
            if source_path is None:
                icons[suffix] = None
                continue
            entry = writer.copy_image(
                source_path,
                os.path.join("achievements", os.path.basename(source_path)),
                True,
            )
            icons[suffix] = entry["path"]

        if icons["iconLockedPath"] is None:
            warn("Achievement %s chưa có ảnh huy hiệu" % definition["id"])

        achievements.append({
            "id": definition["id"],
            "title": {"en": strings.get(definition["title"], definition["id"])},
            "description": {"en": strings.get(definition["description"], "")},
            "ruleType": ACHIEVEMENT_RULES[rule_name],
            "ruleRefId": slugify(rule_argument) if rule_argument else None,
            "targetCount": int(definition["target"]),
            "iconLockedPath": icons["iconLockedPath"],
            "iconUnlockedPath": icons["iconUnlockedPath"],
            "rewardType": "HINT",
            "rewardAmount": 1,
            "sortOrder": index,
            "isActive": True,
        })

    return achievements


# ---------------------------------------------------------------- gói hoàn chỉnh


def zip_directory(source_dir, zip_path):
    os.makedirs(os.path.dirname(os.path.abspath(zip_path)), exist_ok=True)
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for root, _, files in os.walk(source_dir):
            for file_name in sorted(files):
                full_path = os.path.join(root, file_name)
                archive.write(full_path, os.path.relpath(full_path, source_dir))


def build_package(assets_path, res_path, src_path, output_dir, use_webp, webp_quality,
                  thumbnail_size, min_app_version, min_supported_app_version):
    writer = FileWriter(output_dir, use_webp, webp_quality)

    print("Đang xuất category và level...")
    categories, levels = export_categories_and_levels(
        assets_path, writer, thumbnail_size, min_app_version
    )

    print("Đang xuất collection...")
    collections, collection_levels = export_collections_and_levels(
        assets_path, writer, thumbnail_size, min_app_version
    )
    levels.extend(collection_levels)

    print("Đang xuất realm...")
    realms = export_realms(src_path, res_path, writer)

    print("Đang xuất achievement...")
    achievements = export_achievements(src_path, res_path, writer)

    # Ảnh bìa category lấy tạm thumbnail của bức đầu tiên trong category đó.
    thumbnail_by_category = {}
    for level in levels:
        if level["groupType"] != "CATEGORY":
            continue
        category_id = level["groupId"]
        if category_id and category_id not in thumbnail_by_category and level["thumbnailPath"]:
            thumbnail_by_category[category_id] = level["thumbnailPath"]
    for category in categories:
        category["thumbnailPath"] = thumbnail_by_category.get(category["id"])

    remote_config = [
        {"key": key, "valueType": value_type, "value": value, "description": description}
        for key, value_type, value, description in REMOTE_CONFIG_DEFAULTS
    ]

    generated_at = datetime.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"
    manifest = {
        "generatedAt": generated_at,
        "version": generated_at,
        "cdnBaseUrl": "",
        "pathsAreRelativeTo": "files/",
        "minSupportedAppVersion": min_supported_app_version,
        "counts": {
            "categories": len(categories),
            "collections": len(collections),
            "levels": len(levels),
            "realms": len(realms),
            "achievements": len(achievements),
        },
        "notes": [
            "Mọi *Path là đường dẫn tương đối trong thư mục files/, ghép với cdnBaseUrl để ra URL.",
            "createdAt và updatedAt do backend tự sinh lúc import.",
            "Chuỗi hiển thị trả về dạng map ngôn ngữ, hiện mới có bản 'en'.",
            "Tiến độ người dùng nằm trên máy, backend không cần bảng nào cho phần đó.",
        ],
    }

    content_dir = os.path.join(output_dir, "content")
    write_json(os.path.join(content_dir, "manifest.json"), manifest)
    write_json(os.path.join(content_dir, "categories.json"), categories)
    write_json(os.path.join(content_dir, "collections.json"), collections)
    write_json(os.path.join(content_dir, "levels.json"), levels)
    write_json(os.path.join(content_dir, "realms.json"), realms)
    write_json(os.path.join(content_dir, "achievements.json"), achievements)
    write_json(os.path.join(content_dir, "remote_config.json"), remote_config)
    # Daily chưa làm, xuất mảng rỗng để backend dựng sẵn bảng.
    write_json(os.path.join(content_dir, "daily_artworks.json"), [])

    return manifest, levels


def main():
    parser = argparse.ArgumentParser(
        description="Xuất nội dung app thành gói dữ liệu cho backend import."
    )
    parser.add_argument("assets_path", nargs="?", default=DEFAULT_ASSETS,
                        help="Thư mục assets của app.")
    parser.add_argument("--res-path", default=DEFAULT_RES,
                        help="Thư mục res của app (chứa raw/, drawable/, values/).")
    parser.add_argument("--src-path", default=DEFAULT_SRC,
                        help="Thư mục mã nguồn Kotlin (chứa data/AchievementCatalog.kt).")
    parser.add_argument("--output-dir", default=os.path.join("outputs", "backend_content"),
                        help="Thư mục gói đầu ra.")
    parser.add_argument("--webp", action="store_true",
                        help="Chuyển ảnh sang WebP, trừ mask và line phải giữ nguyên PNG.")
    parser.add_argument("--webp-quality", type=int, default=85)
    parser.add_argument("--thumbnail-size", type=int, default=512,
                        help="Cạnh dài nhất của ảnh thumbnail cho lưới danh sách.")
    parser.add_argument("--min-app-version", type=int, default=None)
    parser.add_argument("--min-supported-app-version", type=int, default=None)
    parser.add_argument("--zip", dest="zip_path", nargs="?",
                        const=os.path.join("outputs", "backend_content.zip"),
                        help="Đóng gói thêm thành file zip.")
    args = parser.parse_args()

    if args.webp and Image is None:
        parser.error("Cần Pillow để dùng --webp. Chạy bằng /usr/bin/python3.")

    output_dir = os.path.abspath(args.output_dir)
    if os.path.isdir(output_dir):
        shutil.rmtree(output_dir)

    manifest, levels = build_package(
        assets_path=os.path.abspath(args.assets_path),
        res_path=os.path.abspath(args.res_path),
        src_path=os.path.abspath(args.src_path),
        output_dir=output_dir,
        use_webp=args.webp,
        webp_quality=args.webp_quality,
        thumbnail_size=args.thumbnail_size,
        min_app_version=args.min_app_version,
        min_supported_app_version=args.min_supported_app_version,
    )

    total_bytes = 0
    files_dir = os.path.join(output_dir, "files")
    for root, _, file_names in os.walk(files_dir):
        for file_name in file_names:
            total_bytes += os.path.getsize(os.path.join(root, file_name))
    print("")
    print("Gói đã ghi vào: %s" % output_dir)
    for key, value in manifest["counts"].items():
        print("  %-14s %d" % (key, value))
    print("  %-14s %.0f MB" % ("dung lượng", total_bytes / 1024 / 1024))

    if warnings:
        print("")
        print("Có %d cảnh báo, xem lại trước khi gửi backend." % len(warnings))

    if args.zip_path:
        zip_directory(output_dir, args.zip_path)
        print("Đã đóng gói zip: %s" % os.path.abspath(args.zip_path))


if __name__ == "__main__":
    main()
