import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from tools.export_backend_content import build_package


def write_png(path, color):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (8, 8), color).save(path)


class ExportBackendContentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.assets = self.root / "assets"
        self.res = self.root / "res"
        self.src = self.root / "src"
        self.out = self.root / "backend"
        (self.res / "values").mkdir(parents=True, exist_ok=True)
        (self.res / "values" / "strings.xml").write_text("<resources/>", encoding="utf-8")
        (self.src / "data").mkdir(parents=True, exist_ok=True)
        (self.src / "data" / "AchievementCatalog.kt").write_text("", encoding="utf-8")

    def make_level(self, category="Travel", level="06", with_detail=True):
        level_dir = self.assets / category / level
        level_dir.mkdir(parents=True, exist_ok=True)
        assets = {
            "display_line": "display_line.png",
            "line_render": "line_render.png",
            "line": "line.png",
            "mask": "mask.png",
            "preview": "preview_colored.png",
            "debug_regions": "debug_regions.png",
        }
        if with_detail:
            assets["detail"] = "detail.png"

        config = {
            "id": level,
            "name": level,
            "category": category,
            "width": 1024,
            "height": 1024,
            "assets": assets,
            "palette": [{"number": 1, "target_color": "#112233"}],
            "region_palette": [
                {"number": 1, "mask_color": "#000001", "target_color": "#112233"}
            ],
            "regions": [
                {
                    "id": 1,
                    "mask_color": "#000001",
                    "number": 1,
                    "target_color": "#112233",
                    "area": 64,
                    "bbox": {"left": 0, "top": 0, "right": 7, "bottom": 7},
                    "centroid": {"x": 3.5, "y": 3.5},
                    "label_anchor": {"x": 3.5, "y": 3.5, "radius": 3.0},
                    "hide_number": False,
                }
            ],
            "stats": {"total_regions": 1, "unique_numbers": 1, "estimated_difficulty": 2},
        }
        (level_dir / "config.json").write_text(json.dumps(config), encoding="utf-8")
        write_png(level_dir / "preview_colored.png", (10, 20, 30))
        write_png(level_dir / "line.png", (255, 255, 255))
        write_png(level_dir / "display_line.png", (252, 252, 252))
        write_png(level_dir / "line_render.png", (250, 250, 250))
        write_png(level_dir / "mask.png", (0, 0, 1))
        write_png(level_dir / "debug_regions.png", (255, 0, 0))
        if with_detail:
            write_png(level_dir / "detail.png", (30, 40, 50))
        return level_dir

    def test_exports_manifest_and_runtime_files_for_backend(self):
        self.make_level()

        manifest, levels = build_package(
            assets_path=str(self.assets),
            res_path=str(self.res),
            src_path=str(self.src),
            output_dir=str(self.out),
            use_webp=False,
            webp_quality=85,
            thumbnail_size=512,
            min_app_version="1.0.0",
            min_supported_app_version=None,
        )

        manifest_path = self.out / "content" / "manifest.json"
        self.assertTrue(manifest_path.exists())
        categories = json.loads((self.out / "content" / "categories.json").read_text())
        self.assertEqual(
            categories,
            [
                {
                    "id": "travel",
                    "name": {"en": "Travel"},
                    "sortOrder": 1,
                    "thumbnailPath": "levels/travel-06/thumbnail.png",
                    "levelCount": 1,
                    "isActive": True,
                }
            ],
        )
        self.assertEqual(manifest["counts"]["levels"], 1)

        level = levels[0]
        self.assertEqual(level["id"], "travel-06")
        self.assertEqual(level["groupType"], "CATEGORY")
        self.assertEqual(level["groupId"], "travel")
        self.assertNotIn("categoryId", level)
        self.assertNotIn("collectionId", level)
        self.assertEqual(level["sortOrder"], 6)
        self.assertNotIn("width", level)
        self.assertNotIn("height", level)
        self.assertEqual(level["difficulty"], 2)
        self.assertEqual(level["totalRegions"], 1)
        self.assertEqual(level["paletteSize"], 1)
        self.assertEqual(level["thumbnailPath"], "levels/travel-06/thumbnail.png")
        self.assertEqual(level["configPath"], "levels/travel-06/config.json")
        self.assertEqual(level["minAppVersion"], "1.0.0")
        self.assertTrue(level["contentVersion"].startswith("sha256:"))
        self.assertNotIn("configSizeBytes", level)
        self.assertNotIn("configSha256", level)
        self.assertNotIn("bundleSizeBytes", level)

        asset_by_role = {asset["role"]: asset for asset in level["assets"]}
        self.assertEqual(asset_by_role["LINE"]["path"], "levels/travel-06/line.png")
        self.assertEqual(asset_by_role["DISPLAY_LINE"]["path"], "levels/travel-06/display_line.png")
        self.assertEqual(asset_by_role["MASK"]["path"], "levels/travel-06/mask.png")
        self.assertEqual(asset_by_role["DETAIL"]["path"], "levels/travel-06/detail.png")
        self.assertEqual(asset_by_role["THUMBNAIL"]["path"], "levels/travel-06/thumbnail.png")
        for asset in level["assets"]:
            self.assertEqual(set(asset), {"role", "path", "mimeType"})

        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "config.json").exists())
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "display_line.png").exists())
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "mask.png").exists())
        self.assertFalse((self.out / "files" / "levels" / "travel-06" / "debug_regions.png").exists())

    def test_optional_detail_can_be_missing(self):
        self.make_level(with_detail=False)

        _, levels = build_package(
            assets_path=str(self.assets),
            res_path=str(self.res),
            src_path=str(self.src),
            output_dir=str(self.out),
            use_webp=False,
            webp_quality=85,
            thumbnail_size=512,
            min_app_version=None,
            min_supported_app_version=None,
        )

        roles = {asset["role"] for asset in levels[0]["assets"]}
        self.assertNotIn("DETAIL", roles)

    def test_exports_optional_hi_res_display_lines(self):
        level_dir = self.make_level()
        config_path = level_dir / "config.json"
        config = json.loads(config_path.read_text(encoding="utf-8"))
        config["assets"]["display_line_2x"] = "display_line_2x.png"
        config["assets"]["display_line_4x"] = "display_line_4x.png"
        config_path.write_text(json.dumps(config), encoding="utf-8")
        write_png(level_dir / "display_line_2x.png", (245, 245, 245))
        write_png(level_dir / "display_line_4x.png", (240, 240, 240))

        _, levels = build_package(
            assets_path=str(self.assets),
            res_path=str(self.res),
            src_path=str(self.src),
            output_dir=str(self.out),
            use_webp=False,
            webp_quality=85,
            thumbnail_size=512,
            min_app_version=None,
            min_supported_app_version=None,
        )

        level = levels[0]
        asset_by_role = {asset["role"]: asset for asset in level["assets"]}
        self.assertEqual(asset_by_role["DISPLAY_LINE_2X"]["path"], "levels/travel-06/display_line_2x.png")
        self.assertEqual(asset_by_role["DISPLAY_LINE_4X"]["path"], "levels/travel-06/display_line_4x.png")
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "display_line_2x.png").exists())
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "display_line_4x.png").exists())


if __name__ == "__main__":
    unittest.main()
