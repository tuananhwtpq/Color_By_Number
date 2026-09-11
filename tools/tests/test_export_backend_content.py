import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from PIL import Image

from tools.export_backend_content import build_package


def write_png(path, color):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (8, 8), color).save(path)


def write_svg(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        '<svg width="8" height="8" viewBox="0 0 8 8" xmlns="http://www.w3.org/2000/svg">'
        '<path d="M1 1L7 7" stroke="#111" fill="none"/>'
        "</svg>\n",
        encoding="utf-8",
    )


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
        (self.src / "data" / "Realm.kt").write_text("", encoding="utf-8")

    def make_level(self, category="Travel", level="06", with_detail=True):
        level_dir = self.assets / category / level
        level_dir.mkdir(parents=True, exist_ok=True)
        assets = {
            "display_line": "display_line.png",
            "line_render": "line_render.png",
            "line": "line.png",
            "mask": "mask.png",
            "fill_coverage": "fill_coverage.png",
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
        write_png(level_dir / "fill_coverage.png", (0, 0, 1))
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
        self.assertNotIn("difficulty", level)
        self.assertNotIn("totalRegions", level)
        self.assertNotIn("paletteSize", level)
        self.assertEqual(level["thumbnailPath"], "levels/travel-06/thumbnail.png")
        self.assertEqual(level["configPath"], "levels/travel-06/config.json")
        self.assertEqual(level["minAppVersion"], "1.0.0")
        self.assertFalse(level["isPremium"])
        self.assertTrue(level["contentVersion"].startswith("sha256:"))
        self.assertNotIn("configSizeBytes", level)
        self.assertNotIn("configSha256", level)
        self.assertNotIn("bundleSizeBytes", level)
        self.assertNotIn("assets", level)

        exported_config_path = self.out / "files" / "levels" / "travel-06" / "config.json"
        self.assertTrue(exported_config_path.exists())
        exported_config = json.loads(exported_config_path.read_text(encoding="utf-8"))
        self.assertEqual(
            exported_config["assets"],
            {
                "line": "line.png",
                "mask": "mask.png",
                "display_line": "display_line.png",
                "fill_coverage": "fill_coverage.png",
                "detail": "detail.png",
            },
        )
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "display_line.png").exists())
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "mask.png").exists())
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "fill_coverage.png").exists())
        self.assertFalse((self.out / "files" / "levels" / "travel-06" / "debug_regions.png").exists())

    def test_detail_is_required(self):
        self.make_level(with_detail=False)

        with self.assertRaises(FileNotFoundError):
            build_package(
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

    def test_fill_coverage_is_required(self):
        level_dir = self.make_level()
        (level_dir / "fill_coverage.png").unlink()

        with self.assertRaises(FileNotFoundError):
            build_package(
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

    def test_ignores_hi_res_display_lines_for_backend_package(self):
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
        self.assertNotIn("assets", level)
        self.assertFalse((self.out / "files" / "levels" / "travel-06" / "display_line_2x.png").exists())
        self.assertFalse((self.out / "files" / "levels" / "travel-06" / "display_line_4x.png").exists())

    def test_exports_realms_from_named_kotlin_catalog(self):
        self.make_level()
        (self.res / "raw").mkdir(parents=True, exist_ok=True)
        (self.res / "raw" / "sakura_heaven.json").write_text('{"assets":[]}', encoding="utf-8")
        (self.res / "raw" / "crystal_creek.json").write_text('{"assets":[]}', encoding="utf-8")
        (self.src / "data" / "Realm.kt").write_text(
            """
            object RealmCatalog {
                val realms: List<Realm> = listOf(
                    Realm(
                        id = "crystal_creek",
                        name = "Crystal Creek",
                        animationRes = R.raw.crystal_creek,
                        thumbnailRes = R.drawable.crystal_creek_thumbnail,
                        unlockCost = 10,
                        sortOrder = 2,
                    ),
                    Realm(
                        id = "sakura_haven",
                        name = "Sakura Haven",
                        animationRes = R.raw.sakura_heaven,
                        thumbnailRes = R.drawable.sakura_haven_thumbnail,
                        unlockCost = 0,
                        sortOrder = 1,
                    ),
                ).sortedBy { it.sortOrder }
            }
            """,
            encoding="utf-8",
        )

        manifest, _ = build_package(
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

        realms = json.loads((self.out / "content" / "realms.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["counts"]["realms"], 2)
        self.assertEqual(
            realms,
            [
                {
                    "id": "sakura_haven",
                    "name": {"en": "Sakura Haven"},
                    "animationPath": "realms/sakura_haven/animation.json",
                    "animationMimeType": "application/json",
                    "previewImagePath": None,
                    "unlockCost": 0,
                    "unlockType": "FREE",
                    "unlockValue": None,
                    "sortOrder": 1,
                    "isActive": True,
                },
                {
                    "id": "crystal_creek",
                    "name": {"en": "Crystal Creek"},
                    "animationPath": "realms/crystal_creek/animation.json",
                    "animationMimeType": "application/json",
                    "previewImagePath": None,
                    "unlockCost": 10,
                    "unlockType": "COMPLETED_LEVELS",
                    "unlockValue": 10,
                    "sortOrder": 2,
                    "isActive": True,
                },
            ],
        )
        self.assertTrue((self.out / "files" / "realms" / "sakura_haven" / "animation.json").exists())

    def test_webp_export_rewrites_config_asset_names(self):
        self.make_level()

        _, levels = build_package(
            assets_path=str(self.assets),
            res_path=str(self.res),
            src_path=str(self.src),
            output_dir=str(self.out),
            use_webp=True,
            webp_quality=90,
            thumbnail_size=512,
            min_app_version=None,
            min_supported_app_version=None,
        )

        level = levels[0]
        self.assertEqual(level["thumbnailPath"], "levels/travel-06/thumbnail.webp")
        exported_config = json.loads(
            (self.out / "files" / "levels" / "travel-06" / "config.json").read_text(encoding="utf-8")
        )
        self.assertEqual(
            exported_config["assets"],
            {
                "line": "line.png",
                "mask": "mask.png",
                "display_line": "display_line.webp",
                "fill_coverage": "fill_coverage.png",
                "detail": "detail.webp",
            },
        )
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "line.png").exists())
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "mask.png").exists())
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "fill_coverage.png").exists())
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "display_line.webp").exists())
        self.assertTrue((self.out / "files" / "levels" / "travel-06" / "detail.webp").exists())

    def test_webp_export_keeps_svg_display_line_in_level_zip(self):
        level_dir = self.make_level()
        config_path = level_dir / "config.json"
        config = json.loads(config_path.read_text(encoding="utf-8"))
        config["assets"]["display_line"] = "display_line.svg"
        config_path.write_text(json.dumps(config), encoding="utf-8")
        (level_dir / "display_line.png").unlink()
        write_svg(level_dir / "display_line.svg")

        _, levels = build_package(
            assets_path=str(self.assets),
            res_path=str(self.res),
            src_path=str(self.src),
            output_dir=str(self.out),
            use_webp=True,
            webp_quality=90,
            thumbnail_size=512,
            min_app_version=None,
            min_supported_app_version=None,
            create_level_zips=True,
        )

        level = levels[0]
        self.assertEqual(level["bundleZipPath"], "level_zips/travel-06.zip")
        exported_level_dir = self.out / "files" / "levels" / "travel-06"
        exported_config = json.loads(
            (exported_level_dir / "config.json").read_text(encoding="utf-8")
        )
        self.assertEqual("display_line.svg", exported_config["assets"]["display_line"])
        self.assertTrue((exported_level_dir / "display_line.svg").exists())
        self.assertFalse((exported_level_dir / "display_line.webp").exists())

        zip_path = self.out / "files" / "level_zips" / "travel-06.zip"
        with zipfile.ZipFile(zip_path) as archive:
            names = sorted(archive.namelist())
        self.assertIn("display_line.svg", names)
        self.assertNotIn("display_line.webp", names)

    def test_can_create_zip_per_level_outside_level_folder(self):
        self.make_level()

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
            create_level_zips=True,
        )

        level = levels[0]
        self.assertEqual(level["bundleZipPath"], "level_zips/travel-06.zip")
        self.assertFalse((self.out / "files" / "levels" / "travel-06" / "travel-06.zip").exists())

        zip_path = self.out / "files" / "level_zips" / "travel-06.zip"
        self.assertTrue(zip_path.exists())
        with zipfile.ZipFile(zip_path) as archive:
            self.assertEqual(
                sorted(archive.namelist()),
                [
                    "config.json",
                    "detail.png",
                    "display_line.png",
                    "fill_coverage.png",
                    "line.png",
                    "mask.png",
                    "thumbnail.png",
                ],
            )

        levels_json = json.loads((self.out / "content" / "levels.json").read_text())
        self.assertEqual(levels_json[0]["bundleZipPath"], "level_zips/travel-06.zip")


if __name__ == "__main__":
    unittest.main()
