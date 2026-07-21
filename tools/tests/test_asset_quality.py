import json
import os
import tempfile
import unittest

from PIL import Image

from tools.asset_quality import (
    build_recommendation,
    evaluate_level_dir,
    measure_luminance_preservation_score,
    measure_preview_similarity_score,
)


def write_json(path, data):
    with open(path, "w", encoding="utf-8") as output_file:
        json.dump(data, output_file)


def make_base_config(width=8, height=8, regions=None):
    if regions is None:
        regions = [
            {
                "id": region_id,
                "mask_color": "#{:06x}".format(region_id),
                "number": 1 if region_id % 2 else 2,
                "area": 8,
            }
            for region_id in range(1, 9)
        ]
    return {
        "schema_version": 2,
        "width": width,
        "height": height,
        "assets": {
            "line": "line.png",
            "mask": "mask.png",
            "preview": "preview_colored.png",
        },
        "palette": [
            {"number": 1, "target_color": "#ff0000"},
            {"number": 2, "target_color": "#00ff00"},
        ],
        "regions": regions,
        "stats": {
            "total_regions": len(regions),
            "unique_numbers": len({region["number"] for region in regions}),
        },
    }


def save_level_images(level_dir, mask_colors, line_dark=True, preview_color=(128, 128, 128)):
    height = len(mask_colors)
    width = len(mask_colors[0])
    mask = Image.new("RGB", (width, height))
    mask_pixels = mask.load()
    for y, row in enumerate(mask_colors):
        for x, color in enumerate(row):
            mask_pixels[x, y] = color
    mask.save(os.path.join(level_dir, "mask.png"))

    line = Image.new("RGB", (width, height), (255, 255, 255))
    if line_dark:
        line_pixels = line.load()
        for y in range(height):
            line_pixels[width // 2, y] = (0, 0, 0)
    line.save(os.path.join(level_dir, "line.png"))

    preview = Image.new("RGB", (width, height), preview_color)
    preview.save(os.path.join(level_dir, "preview_colored.png"))
    reference = Image.new("RGB", (width, height), preview_color)
    reference_path = os.path.join(level_dir, "color.png")
    reference.save(reference_path)
    return reference_path


class AssetQualityTest(unittest.TestCase):
    def test_clean_level_receives_passing_grade(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, x + 1) for x in range(8)]
                for _ in range(8)
            ]
            reference_path = save_level_images(temp_dir, mask_colors)
            write_json(os.path.join(temp_dir, "config.json"), make_base_config())

            report = evaluate_level_dir(temp_dir, reference_path=reference_path)

            self.assertIn(report["quality_grade"], {"A", "B"})
            self.assertEqual([], report["fail_reasons"])
            self.assertEqual(0, report["metrics"]["mask_config_mismatch_count"])

    def test_giant_region_and_low_region_count_fail(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, 1)] * 8
                for _ in range(8)
            ]
            reference_path = save_level_images(
                temp_dir,
                mask_colors,
                line_dark=False,
                preview_color=(255, 255, 255),
            )
            black_reference = Image.new("RGB", (8, 8), (0, 0, 0))
            black_reference.save(reference_path)
            write_json(
                os.path.join(temp_dir, "config.json"),
                make_base_config(
                    regions=[{"id": 1, "mask_color": "#000001", "number": 1, "area": 64}]
                ),
            )

            report = evaluate_level_dir(temp_dir, reference_path=reference_path)

            self.assertEqual("D", report["quality_grade"])
            codes = {reason["code"] for reason in report["fail_reasons"]}
            self.assertIn("GIANT_REGION", codes)
            self.assertIn("LOW_REGION_COUNT", codes)
            self.assertIn("PREVIEW_MAE_TOO_HIGH", codes)

    def test_mask_config_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, 1)] * 4 + [(0, 0, 3)] * 4
                for _ in range(8)
            ]
            reference_path = save_level_images(temp_dir, mask_colors)
            write_json(os.path.join(temp_dir, "config.json"), make_base_config())

            report = evaluate_level_dir(temp_dir, reference_path=reference_path)

            self.assertEqual("D", report["quality_grade"])
            self.assertGreater(report["metrics"]["mask_config_mismatch_count"], 0)
            codes = {reason["code"] for reason in report["fail_reasons"]}
            self.assertIn("MASK_CONFIG_MISMATCH", codes)

    def test_duplicate_mask_color_fails(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, 1) for _ in range(8)]
                for _ in range(8)
            ]
            reference_path = save_level_images(temp_dir, mask_colors)
            write_json(
                os.path.join(temp_dir, "config.json"),
                make_base_config(
                    regions=[
                        {"id": 1, "mask_color": "#000001", "number": 1, "area": 32},
                        {"id": 2, "mask_color": "#000001", "number": 2, "area": 32},
                    ]
                ),
            )

            report = evaluate_level_dir(temp_dir, reference_path=reference_path)

            codes = {reason["code"] for reason in report["fail_reasons"]}
            self.assertIn("DUPLICATE_MASK_COLOR", codes)

    def test_duplicate_region_palette_mask_color_fails(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, x + 1) for x in range(8)]
                for _ in range(8)
            ]
            reference_path = save_level_images(temp_dir, mask_colors)
            config = make_base_config()
            config["region_palette"] = [
                {"number": 1, "mask_color": "#000001", "target_color": "#ff0000"},
                {"number": 2, "mask_color": "#000001", "target_color": "#00ff00"},
            ]
            write_json(os.path.join(temp_dir, "config.json"), config)

            report = evaluate_level_dir(temp_dir, reference_path=reference_path)

            codes = {reason["code"] for reason in report["fail_reasons"]}
            self.assertIn("DUPLICATE_MASK_COLOR_IN_REGION_PALETTE", codes)

    def test_region_area_mismatch_uses_real_mask_pixel_count(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, 1)] * 8
                for _ in range(8)
            ]
            reference_path = save_level_images(temp_dir, mask_colors)
            write_json(
                os.path.join(temp_dir, "config.json"),
                make_base_config(
                    regions=[
                        {"id": 1, "mask_color": "#000001", "number": 1, "area": 8},
                    ]
                ),
            )

            report = evaluate_level_dir(temp_dir, reference_path=reference_path)

            self.assertEqual(100.0, report["metrics"]["largest_region_pct"])
            codes = {reason["code"] for reason in report["fail_reasons"]}
            self.assertIn("REGION_AREA_MISMATCH", codes)

    def test_small_region_area_mismatch_uses_strict_tolerance(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [[(0, 0, 0) for _ in range(8)] for _ in range(8)]
            for index in range(11):
                mask_colors[index // 8][index % 8] = (0, 0, 1)
            reference_path = save_level_images(temp_dir, mask_colors)
            write_json(
                os.path.join(temp_dir, "config.json"),
                make_base_config(
                    regions=[
                        {"id": 1, "mask_color": "#000001", "number": 1, "area": 8},
                    ]
                ),
            )

            report = evaluate_level_dir(temp_dir, reference_path=reference_path)

            mismatch = report["metrics"]["region_area_mismatches"][0]
            self.assertEqual(1, mismatch["tolerance"])
            self.assertEqual(3, mismatch["delta"])
            codes = {reason["code"] for reason in report["fail_reasons"]}
            self.assertIn("REGION_AREA_MISMATCH", codes)

    def test_missing_reference_warns_without_fail_by_default(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, x + 1) for x in range(8)]
                for _ in range(8)
            ]
            save_level_images(temp_dir, mask_colors)
            write_json(os.path.join(temp_dir, "config.json"), make_base_config())

            report = evaluate_level_dir(temp_dir, reference_path=None)

            warning_codes = {warning["code"] for warning in report["warnings"]}
            fail_codes = {reason["code"] for reason in report["fail_reasons"]}
            self.assertIn("REFERENCE_MISSING", warning_codes)
            self.assertNotIn("REFERENCE_MISSING", fail_codes)

    def test_missing_reference_can_be_required(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, x + 1) for x in range(8)]
                for _ in range(8)
            ]
            save_level_images(temp_dir, mask_colors)
            write_json(os.path.join(temp_dir, "config.json"), make_base_config())

            report = evaluate_level_dir(temp_dir, reference_path=None, require_reference=True)

            fail_codes = {reason["code"] for reason in report["fail_reasons"]}
            self.assertIn("REFERENCE_MISSING", fail_codes)

    def test_recommendation_keep_for_grade_a(self):
        recommendation = build_recommendation(
            {
                "quality_grade": "A",
                "warnings": [],
                "fail_reasons": [],
                "metrics": {},
            }
        )

        self.assertEqual("KEEP", recommendation["action"])

    def test_similarity_scores_are_high_for_matching_images(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            reference_path = os.path.join(temp_dir, "reference.png")
            preview_path = os.path.join(temp_dir, "preview.png")
            image = Image.new("RGB", (4, 4), (40, 120, 200))
            image.save(reference_path)
            image.save(preview_path)

            self.assertEqual(100.0, measure_preview_similarity_score(reference_path, preview_path))
            self.assertEqual(
                100.0,
                measure_luminance_preservation_score(reference_path, preview_path),
            )

    def test_evaluate_level_reports_similarity_metrics(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, x + 1) for x in range(8)]
                for _ in range(8)
            ]
            reference_path = save_level_images(temp_dir, mask_colors)
            write_json(os.path.join(temp_dir, "config.json"), make_base_config())

            report = evaluate_level_dir(temp_dir, reference_path=reference_path)

            self.assertIn("preview_similarity_score", report["metrics"])
            self.assertIn("shading_preservation_score", report["metrics"])
            self.assertEqual(100.0, report["metrics"]["preview_similarity_score"])

    def test_recommendation_review_visual_for_grade_b_warning(self):
        recommendation = build_recommendation(
            {
                "quality_grade": "B",
                "warnings": [{"code": "GIANT_REGION_WARNING"}],
                "fail_reasons": [],
                "metrics": {},
            }
        )

        self.assertEqual("REVIEW_VISUAL", recommendation["action"])
        self.assertIn("REGENERATE_AUTO_OR_FIX_LINE", recommendation["reasons"])

    def test_recommendation_design_fix_line_for_light_giant_line(self):
        recommendation = build_recommendation(
            {
                "quality_grade": "D",
                "warnings": [{"code": "LINE_TOO_LIGHT"}],
                "fail_reasons": [{"code": "GIANT_REGION"}],
                "metrics": {},
            }
        )

        self.assertEqual("EXCLUDE_DEMO", recommendation["action"])
        self.assertIn("DESIGN_FIX_LINE", recommendation["reasons"])
        self.assertIn("line", recommendation["design_focus"])

    def test_recommendation_design_fix_color_for_preview_mae_high(self):
        recommendation = build_recommendation(
            {
                "quality_grade": "B",
                "warnings": [{"code": "PREVIEW_MAE_HIGH"}],
                "fail_reasons": [],
                "metrics": {},
            }
        )

        self.assertEqual("REVIEW_VISUAL", recommendation["action"])
        self.assertIn("DESIGN_FIX_COLOR", recommendation["reasons"])
        self.assertIn("color_alignment", recommendation["design_focus"])


if __name__ == "__main__":
    unittest.main()
