import csv
import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

from tools.audit_line_degradation import audit_level, compare_lines, write_csv, write_html_report


class AuditLineDegradationTest(unittest.TestCase):
    def make_line_image(self, path, background=255, line_value=40, erase_right_half=False):
        image = Image.new("L", (10, 10), background)
        draw = ImageDraw.Draw(image)
        draw.line((1, 1, 8, 1), fill=line_value, width=1)
        draw.line((1, 1, 1, 8), fill=line_value, width=1)
        if erase_right_half:
            draw.rectangle((5, 0, 9, 9), fill=background)
        image.convert("RGB").save(path)

    def make_level(self, root, data_root, display_erased=False, segmentation_erased=True):
        data_level = data_root / "Art" / "01"
        data_level.mkdir(parents=True)
        self.make_line_image(data_level / "line.png")

        level = root / "Art" / "01"
        level.mkdir(parents=True)
        self.make_line_image(level / "debug_source_line.png")
        self.make_line_image(level / "line_render.png", erase_right_half=display_erased)
        self.make_line_image(level / "line.png", erase_right_half=segmentation_erased)
        Image.new("RGB", (10, 10), (20, 120, 200)).save(level / "debug_regions.png")
        Image.new("RGB", (10, 10), (200, 120, 20)).save(level / "preview_colored.png")
        (level / "debug_report.json").write_text(
            json.dumps(
                {
                    "generation_params": {
                        "recovered_ink_pixel_count": 4,
                        "segmentation_line_report": {
                            "selected_profile": "manga",
                            "brightness_threshold": 170,
                            "line_close_radius": 2,
                            "evaluation_mode": "pre_generation_proxy_post_merge",
                            "quality_score": 55,
                            "playable_score": 100,
                            "lost_color_pct": 9.7,
                        },
                        "display_line_report": {
                            "selected": "source_line",
                            "fallback_to_source": True,
                            "uses_segmentation_score": False,
                            "candidate_mae": 24.25,
                            "candidate_changed_pct": 24.48,
                            "stroke_lightened_pct": 18.5,
                            "stroke_lightened_mae": 22.0,
                            "stroke_pixel_count": 120,
                            "stroke_guard_failed": True,
                            "ink_recovery_overreach": True,
                        },
                    }
                }
            ),
            encoding="utf-8",
        )
        (level / "config.json").write_text(
            json.dumps(
                {
                    "assets": {
                        "source_line": "debug_source_line.png",
                        "display_line": "line_render.png",
                        "segmentation_line": "line.png",
                        "debug_source_line": "debug_source_line.png",
                        "line_render": "line_render.png",
                        "line": "line.png",
                    }
                }
            ),
            encoding="utf-8",
        )
        return level

    def test_compare_lines_reports_zero_for_identical_images(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "line.png"
            self.make_line_image(path)

            metrics = compare_lines(str(path), str(path))

            self.assertEqual(0.0, metrics["mae"])
            self.assertEqual(0.0, metrics["changed_pct"])
            self.assertGreater(metrics["ink_pct_lt_180"], 0)

    def test_audit_level_tags_display_and_segmentation_drift(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "assets"
            data_root = Path(temp_dir) / "Data"
            level = self.make_level(root, data_root, display_erased=True)

            row = audit_level(
                str(root),
                str(level),
                str(data_root),
                display_changed_threshold=1,
                segmentation_changed_threshold=1,
            )

            self.assertEqual("Art/01", row["level_key"])
            self.assertEqual("0.0", str(row["source_line_mae"]))
            self.assertEqual("source_line", row["source_line_asset_key"])
            self.assertEqual("display_line", row["display_line_asset_key"])
            self.assertEqual("segmentation_line", row["segmentation_line_asset_key"])
            self.assertEqual("manga", row["segmentation_selected_profile"])
            self.assertEqual(170, row["segmentation_brightness_threshold"])
            self.assertEqual(2, row["segmentation_line_close_radius"])
            self.assertEqual("source_line", row["display_line_selected"])
            self.assertEqual("Y", row["display_line_fallback_to_source"])
            self.assertEqual("N", row["display_line_uses_segmentation_score"])
            self.assertEqual(24.25, row["display_line_candidate_mae"])
            self.assertEqual(18.5, row["display_line_stroke_lightened_pct"])
            self.assertEqual(22.0, row["display_line_stroke_lightened_mae"])
            self.assertEqual(120, row["display_line_stroke_pixel_count"])
            self.assertEqual("Y", row["display_line_stroke_guard_failed"])
            self.assertEqual("Y", row["ink_recovery_overreach"])
            self.assertIn("thin_stroke_loss", row["line_issue_tags"])
            self.assertIn("ink_recovery_overreach", row["line_issue_tags"])
            self.assertIn("display_line_fallback_source", row["line_issue_tags"])
            self.assertEqual("Y", row["line_display_degraded"])
            self.assertEqual("Y", row["segmentation_line_aggressive"])
            self.assertEqual(4, row["recovered_ink_pixel_count"])
            self.assertEqual(4.0, row["recovered_ink_pixel_pct"])
            self.assertEqual("N", row["missing_source_line"])

    def test_audit_level_keeps_display_ok_when_it_matches_source(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "assets"
            data_root = Path(temp_dir) / "Data"
            level = self.make_level(root, data_root, display_erased=False)

            row = audit_level(str(root), str(level), str(data_root), display_changed_threshold=1)

            self.assertEqual("N", row["line_display_degraded"])
            self.assertEqual(0.0, row["display_line_mae"])

    def test_audit_level_falls_back_to_legacy_asset_keys(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "assets"
            data_root = Path(temp_dir) / "Data"
            level = self.make_level(root, data_root, display_erased=False)
            (level / "config.json").write_text(
                json.dumps(
                    {
                        "assets": {
                            "debug_source_line": "debug_source_line.png",
                            "line_render": "line_render.png",
                            "line": "line.png",
                        }
                    }
                ),
                encoding="utf-8",
            )

            row = audit_level(str(root), str(level), str(data_root))

            self.assertEqual("debug_source_line", row["source_line_asset_key"])
            self.assertEqual("line_render", row["display_line_asset_key"])
            self.assertEqual("line", row["segmentation_line_asset_key"])

    def test_write_csv_uses_stable_columns(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "audit.csv"
            write_csv(
                [
                    {
                        "category": "Art",
                        "level": "01",
                        "level_key": "Art/01",
                        "source_line_asset_key": "source_line",
                        "display_line_asset_key": "display_line",
                        "segmentation_line_asset_key": "segmentation_line",
                        "segmentation_selected_profile": "manga",
                        "segmentation_brightness_threshold": 170,
                        "segmentation_line_close_radius": 2,
                        "segmentation_evaluation_mode": "pre_generation_proxy_post_merge",
                        "segmentation_quality_score": 55,
                        "segmentation_playable_score": 100,
                        "segmentation_lost_color_pct": 9.7,
                        "display_line_selected": "source_line",
                        "display_line_fallback_to_source": "Y",
                        "display_line_uses_segmentation_score": "N",
                        "display_line_candidate_mae": 24.25,
                        "display_line_candidate_changed_pct": 24.48,
                        "display_line_stroke_lightened_pct": 18.5,
                        "display_line_stroke_lightened_mae": 22.0,
                        "display_line_stroke_pixel_count": 120,
                        "display_line_stroke_guard_failed": "Y",
                        "ink_recovery_overreach": "Y",
                        "line_issue_tags": "line_display_degraded;segmentation_line_aggressive;ink_recovery_overreach;thin_stroke_loss",
                        "source_line_path": "Data/Art/01/line.png",
                        "debug_source_line_path": "assets/Art/01/debug_source_line.png",
                        "display_line_path": "assets/Art/01/line_render.png",
                        "segmentation_line_path": "assets/Art/01/line.png",
                        "source_line_mae": 0,
                        "source_line_changed_pct": 0,
                        "display_line_mae": 20,
                        "display_line_changed_pct": 30,
                        "segmentation_line_mae": 40,
                        "segmentation_line_changed_pct": 50,
                        "source_dark_pct_lt_80": 1,
                        "display_dark_pct_lt_80": 1,
                        "segmentation_dark_pct_lt_80": 2,
                        "source_ink_pct_lt_180": 3,
                        "display_ink_pct_lt_180": 2,
                        "segmentation_ink_pct_lt_180": 4,
                        "recovered_ink_pixel_count": 10,
                        "recovered_ink_pixel_pct": 10,
                        "line_display_degraded": "Y",
                        "segmentation_line_aggressive": "Y",
                        "missing_source_line": "N",
                        "missing_display_line": "N",
                        "missing_segmentation_line": "N",
                        "path": "assets/Art/01",
                    }
                ],
                output,
            )

            with output.open(newline="", encoding="utf-8") as input_file:
                rows = list(csv.DictReader(input_file))

            self.assertEqual("Art/01", rows[0]["level_key"])
            self.assertEqual("Y", rows[0]["line_display_degraded"])

    def test_write_html_report_embeds_line_review_images(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "assets"
            data_root = Path(temp_dir) / "Data"
            level = self.make_level(root, data_root, display_erased=True)
            row = audit_level(
                str(root),
                str(level),
                str(data_root),
                display_changed_threshold=1,
                segmentation_changed_threshold=1,
            )
            output = Path(temp_dir) / "report.html"

            write_html_report([row], output)

            html = output.read_text(encoding="utf-8")
            self.assertIn("Line degradation audit", html)
            self.assertIn("Art/01", html)
            self.assertIn("line_display_degraded", html)
            self.assertIn("segmentation_line_aggressive", html)
            self.assertIn("ink_recovery_overreach", html)
            self.assertIn("display_line_fallback_source", html)
            self.assertIn("display uses segmentation score", html)
            self.assertIn("source_line", html)
            self.assertIn("Data line", html)
            self.assertIn("source line (source_line)", html)
            self.assertIn("display line (display_line)", html)
            self.assertIn("segmentation line (segmentation_line)", html)
            self.assertIn("debug_regions", html)
            self.assertIn("preview", html)
            self.assertEqual(6, html.count('<img src="data:image/webp;base64,'))


if __name__ == "__main__":
    unittest.main()
