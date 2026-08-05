import json
import os
import tempfile
import unittest

from PIL import Image

from tools.asset_quality import (
    analyze_region_playability,
    build_recommendation,
    calculate_detail_dependency_score,
    classify_overmerge_risk,
    calculate_gameplay_metrics,
    evaluate_level_dir,
    measure_giant_region_legitimacy,
    measure_luminance_preservation_score,
    measure_preview_similarity_score,
    normalize_profile,
    score_quality,
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
    def test_playability_analyzer_reports_tiny_distribution_and_label_metrics(self):
        metrics = analyze_region_playability(
            total_pixels=10000,
            regions=[
                {"area": 10, "number": 1, "hide_number": True},
                {"area": 20, "number": 1, "hide_number": True},
                {"area": 30, "number": 2, "hide_number": True},
                {"area": 40, "number": 2, "hide_number": False},
                {"area": 50, "number": 3, "hide_number": False},
                {"area": 100, "number": 3, "hide_number": False},
                {"area": 200, "number": 4, "hide_number": False},
                {"area": 400, "number": 4, "hide_number": False},
                {"area": 800, "number": 5, "hide_number": False},
                {"area": 1600, "number": 5, "hide_number": False},
            ],
            color_areas=[1500, 1000, 500],
            canvas_width=100,
            canvas_height=100,
            default_view_width=200,
            default_view_height=200,
        )

        self.assertEqual(4, metrics["tiny_region_count_lt_50"])
        self.assertEqual(5, metrics["tiny_region_count_lt_100"])
        self.assertEqual(6, metrics["tiny_region_count_lt_200"])
        self.assertEqual(40.0, metrics["tiny_region_pct_lt_50"])
        self.assertEqual(50.0, metrics["tiny_region_pct_lt_100"])
        self.assertEqual(60.0, metrics["tiny_region_pct_lt_200"])
        self.assertEqual(3, metrics["config_hidden_label_count"])
        self.assertEqual(30.0, metrics["config_hidden_label_pct"])
        self.assertEqual(10, metrics["hidden_label_count"])
        self.assertEqual(100.0, metrics["hidden_label_pct"])
        self.assertEqual(75.0, metrics["median_region_area"])
        self.assertEqual(10, metrics["p10_region_area"])
        self.assertEqual(30, metrics["p25_region_area"])
        self.assertEqual(1000.0, metrics["region_density_by_canvas_size"])
        self.assertEqual(6, metrics["untouchable_region_count"])
        self.assertEqual(6.32, metrics["min_touch_target_at_default_zoom"])
        self.assertEqual({1: 2, 2: 2, 3: 1}, metrics["tiny_region_by_number_lt_100"])

    def test_hidden_label_metrics_separate_config_hide_from_android_radius_estimate(self):
        metrics = analyze_region_playability(
            total_pixels=10000,
            regions=[
                {"area": 400, "number": 1, "hide_number": True, "radius": 30},
                {"area": 900, "number": 2, "hide_number": False, "radius": 20},
                {"area": 1600, "number": 3, "hide_number": False, "radius": 30},
                {"area": 2500, "number": 4, "hide_number": False, "radius": 40},
            ],
            canvas_width=100,
            canvas_height=100,
            default_view_width=100,
            default_view_height=100,
        )

        self.assertEqual(1, metrics["config_hidden_label_count"])
        self.assertEqual(25.0, metrics["config_hidden_label_pct"])
        self.assertEqual(2, metrics["estimated_hidden_label_count"])
        self.assertEqual(50.0, metrics["estimated_hidden_label_pct"])
        self.assertEqual(25, metrics["label_min_screen_radius_px"])
        self.assertEqual(metrics["estimated_hidden_label_count"], metrics["hidden_label_count"])
        self.assertEqual(metrics["estimated_hidden_label_pct"], metrics["hidden_label_pct"])

    def test_hard_level_with_many_regions_but_low_tiny_pct_does_not_fail_tiny_gate(self):
        metrics = analyze_region_playability(
            total_pixels=1000000,
            regions=[{"area": 500, "number": index % 20, "hide_number": False} for index in range(950)]
            + [{"area": 100, "number": index % 20, "hide_number": False} for index in range(50)],
            profile="hard",
        )
        quality = score_quality(
            {
                "total_regions": 1000,
                "unique_numbers": 20,
                "preview_mae": 10,
                "mask_config_mismatch_count": 0,
                **metrics,
            }
        )

        self.assertNotIn(
            "MANY_TINY_REGIONS",
            {issue["code"] for issue in quality["fail_reasons"]},
        )
        self.assertNotEqual("D", quality["quality_grade"])

    def test_casual_level_with_high_tiny_pct_gets_playability_warning(self):
        metrics = analyze_region_playability(
            total_pixels=1000000,
            regions=[{"area": 80, "number": index % 5, "hide_number": True} for index in range(45)]
            + [{"area": 500, "number": index % 5, "hide_number": False} for index in range(55)],
            profile="casual",
        )
        quality = score_quality(
            {
                "total_regions": 100,
                "unique_numbers": 5,
                "preview_mae": 10,
                "mask_config_mismatch_count": 0,
                **metrics,
            }
        )

        warning_codes = {issue["code"] for issue in quality["warnings"]}
        self.assertIn("TINY_REGION_DENSITY_WARNING", warning_codes)

    def test_profile_aliases_and_thresholds_for_tiny_density_gate(self):
        self.assertEqual("medium", normalize_profile("standard"))
        self.assertEqual("casual", normalize_profile("easy"))

        shared_metrics = {
            "total_regions": 700,
            "unique_numbers": 20,
            "largest_region_pct": 20,
            "tiny_region_count_lt_50": 60,
            "tiny_region_pct_lt_100": 42,
            "tiny_region_pct_lt_200": 55,
            "estimated_hidden_label_pct": 78,
            "config_hidden_label_pct": None,
            "median_region_area": 150,
            "preview_mae": 10,
            "mask_config_mismatch_count": 0,
        }

        casual_quality = score_quality(
            {**shared_metrics, "playability_profile": "casual"}
        )
        medium_quality = score_quality(
            {**shared_metrics, "playability_profile": "medium"}
        )
        hard_quality = score_quality(
            {**shared_metrics, "playability_profile": "hard"}
        )

        self.assertIn(
            "TINY_REGION_DENSITY_WARNING",
            {issue["code"] for issue in casual_quality["warnings"]},
        )
        self.assertNotIn(
            "TINY_REGION_DENSITY_WARNING",
            {issue["code"] for issue in medium_quality["warnings"]},
        )
        self.assertNotIn(
            "TINY_REGION_DENSITY_WARNING",
            {issue["code"] for issue in hard_quality["warnings"]},
        )

    def test_gameplay_metrics_report_region_and_color_distribution(self):
        metrics = calculate_gameplay_metrics(
            total_pixels=10000,
            region_areas=[6100, 2200, 900, 300, 200, 100, 80, 60, 40, 20],
            color_areas=[7000, 1800, 800, 400],
        )

        self.assertEqual(61.0, metrics["largest_region_pct"])
        self.assertEqual(92.0, metrics["top_3_region_pct"])
        self.assertEqual(70.0, metrics["max_color_pct"])
        self.assertEqual(96.0, metrics["top_3_color_pct"])
        self.assertEqual(5, metrics["playable_region_count"])
        self.assertEqual(1, metrics["giant_region_count"])
        self.assertEqual("low", metrics["single_tap_completion_risk"])
        self.assertLess(metrics["playable_score"], 85)

    def test_single_tap_completion_risk_is_critical_for_one_huge_region(self):
        quality = score_quality(
            {
                "total_regions": 3,
                "unique_numbers": 3,
                "largest_region_pct": 97.05,
                "top_3_region_pct": 99.0,
                "max_color_pct": 97.05,
                "top_3_color_pct": 99.0,
                "playable_region_count": 3,
                "giant_region_count": 1,
                "single_tap_completion_risk": "critical",
                "playable_score": 12,
                "preview_similarity_score": 91.35,
                "preview_mae": 22.05,
                "shading_preservation_score": 92.44,
                "has_detail": True,
                "mask_config_mismatch_count": 0,
            }
        )

        self.assertEqual("D", quality["quality_grade"])
        fail_codes = {issue["code"] for issue in quality["fail_reasons"]}
        self.assertIn("GIANT_REGION", fail_codes)
        self.assertIn("LOW_REGION_COUNT", fail_codes)
        self.assertIn("SINGLE_TAP_COMPLETION_RISK", fail_codes)

    def test_visual_quality_does_not_keep_bad_gameplay(self):
        quality = score_quality(
            {
                "total_regions": 51,
                "unique_numbers": 46,
                "largest_region_pct": 85.35,
                "top_3_region_pct": 91.0,
                "max_color_pct": 72.0,
                "top_3_color_pct": 88.0,
                "playable_region_count": 45,
                "giant_region_count": 1,
                "single_tap_completion_risk": "high",
                "playable_score": 28,
                "preview_similarity_score": 94.63,
                "preview_mae": 13.7,
                "shading_preservation_score": 95.38,
                "has_detail": True,
                "mask_config_mismatch_count": 0,
            }
        )
        recommendation = build_recommendation({**quality, "metrics": {}})

        self.assertEqual("D", quality["quality_grade"])
        self.assertEqual("EXCLUDE_DEMO", recommendation["action"])
        self.assertIn("DESIGN_FIX_LINE", recommendation["reasons"])

    def test_overmerge_risk_high_fails_even_when_detail_makes_preview_similar(self):
        metrics = {
            "total_regions": 6,
            "raw_region_count": 80,
            "final_region_count": 6,
            "region_count_drop_pct": 92.5,
            "largest_region_pct": 35.0,
            "top_3_region_pct": 70.0,
            "max_color_pct": 40.0,
            "top_3_color_pct": 70.0,
            "playable_region_count": 6,
            "giant_region_count": 0,
            "single_tap_completion_risk": "high",
            "playable_score": 60,
            "flat_similarity_score": 84.0,
            "preview_similarity_score": 96.0,
            "detail_dependency_score": 12.0,
            "preview_mae": 10,
            "mask_config_mismatch_count": 0,
        }
        metrics["overmerge_risk"] = classify_overmerge_risk(metrics)

        quality = score_quality(metrics)

        self.assertEqual("high", metrics["overmerge_risk"])
        self.assertIn("OVERMERGE_RISK_HIGH", {issue["code"] for issue in quality["fail_reasons"]})
        self.assertEqual("D", quality["quality_grade"])

    def test_large_flat_background_is_exception_candidate_not_overmerge_high(self):
        metrics = {
            "total_regions": 35,
            "raw_region_count": 45,
            "final_region_count": 35,
            "region_count_drop_pct": 22.22,
            "largest_region_pct": 82.0,
            "top_3_region_pct": 90.0,
            "max_color_pct": 82.0,
            "top_3_color_pct": 90.0,
            "playable_region_count": 30,
            "giant_region_count": 1,
            "single_tap_completion_risk": "medium",
            "playable_score": 72,
            "preview_similarity_score": 96.0,
            "preview_mae": 10,
            "mask_config_mismatch_count": 0,
            "giant_region_legitimacy": "intentional_flat_background",
        }
        metrics["overmerge_risk"] = classify_overmerge_risk(metrics)

        quality = score_quality(metrics)

        self.assertEqual("low", metrics["overmerge_risk"])
        self.assertNotIn("GIANT_REGION", {issue["code"] for issue in quality["fail_reasons"]})
        self.assertNotIn("OVERMERGE_RISK_HIGH", {issue["code"] for issue in quality["fail_reasons"]})

    def test_simple_icon_with_good_visuals_does_not_fail_gameplay_gate(self):
        quality = score_quality(
            {
                "total_regions": 39,
                "unique_numbers": 38,
                "largest_region_pct": 49.6,
                "top_3_region_pct": 64.0,
                "max_color_pct": 35.0,
                "top_3_color_pct": 58.0,
                "playable_region_count": 31,
                "giant_region_count": 0,
                "single_tap_completion_risk": "none",
                "playable_score": 94,
                "preview_similarity_score": 96.6,
                "preview_mae": 8.7,
                "shading_preservation_score": 96.7,
                "has_detail": True,
                "mask_config_mismatch_count": 0,
            }
        )

        self.assertNotEqual("D", quality["quality_grade"])
        self.assertEqual([], quality["fail_reasons"])

    def test_clean_level_receives_passing_grade(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mask_colors = [
                [(0, 0, x + 1) for x in range(10)]
                for _ in range(10)
            ]
            reference_path = save_level_images(temp_dir, mask_colors)
            write_json(
                os.path.join(temp_dir, "config.json"),
                make_base_config(
                    width=10,
                    height=10,
                    regions=[
                        {
                            "id": region_id,
                            "mask_color": "#{:06x}".format(region_id),
                            "number": 1 if region_id % 2 else 2,
                            "area": 10,
                        }
                        for region_id in range(1, 11)
                    ],
                ),
            )

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
            self.assertIn("flat_similarity_score", report["metrics"])
            self.assertIn("detail_dependency_score", report["metrics"])
            self.assertIn("shading_preservation_score", report["metrics"])
            self.assertEqual(100.0, report["metrics"]["preview_similarity_score"])

    def test_detail_dependency_score_is_preview_minus_flat_similarity(self):
        self.assertEqual(7.5, calculate_detail_dependency_score(88.0, 95.5))
        self.assertEqual(0.0, calculate_detail_dependency_score(97.0, 95.5))

    def test_recommendation_review_visual_for_grade_b_warning(self):
        recommendation = build_recommendation(
            {
                "quality_grade": "B",
                "warnings": [{"code": "GIANT_REGION_WARNING"}],
                "fail_reasons": [],
                "metrics": {},
            }
        )

        self.assertEqual("REGENERATE_AUTO", recommendation["action"])
        self.assertIn("REGENERATE_AUTO", recommendation["reasons"])

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

    def test_giant_region_legitimacy_flat_background_vs_needs_review(self):
        # Canvas 10x10: vùng khổng lồ (id=1, mask trắng) chiếm 80 px (80%) — vượt ngưỡng
        # giant_pct_threshold mặc định 55%. Vùng còn lại (id=2, mask đen) chỉ để có 2 mask
        # color phân biệt, không liên quan phép đo.
        width, height = 10, 10
        config = {
            "width": width,
            "height": height,
            "regions": [
                {"id": 1, "mask_color": "#ffffff", "area": 80},
                {"id": 2, "mask_color": "#000000", "area": 20},
            ],
        }
        mask = Image.new("RGB", (width, height), (0, 0, 0))
        mask_pixels = mask.load()
        for y in range(8):
            for x in range(width):
                mask_pixels[x, y] = (255, 255, 255)

        with tempfile.TemporaryDirectory() as tmp:
            mask_path = os.path.join(tmp, "mask.png")
            mask.save(mask_path)

            # Ca 1: nền phẳng thật — tham chiếu gần như 1 màu duy nhất trong vùng khổng lồ.
            flat_ref_path = os.path.join(tmp, "flat_ref.png")
            flat_ref = Image.new("RGB", (width, height), (250, 30, 30))
            flat_ref.save(flat_ref_path)

            flat_result = measure_giant_region_legitimacy(config, flat_ref_path, mask_path)
            self.assertEqual("intentional_flat_background", flat_result["giant_region_legitimacy"])
            self.assertLess(max(flat_result["giant_region_reference_color_std"]), 10.0)

            # Ca 2: cùng hình dạng vùng khổng lồ, nhưng nửa trái/nửa phải trong đó có màu
            # khác hẳn nhau thật (biến thiên cao) — dấu hiệu có nội dung thật bị dính vào
            # nhau, không phải nền phẳng.
            varied_ref_path = os.path.join(tmp, "varied_ref.png")
            varied_ref = Image.new("RGB", (width, height))
            varied_pixels = varied_ref.load()
            for y in range(height):
                for x in range(width):
                    varied_pixels[x, y] = (240, 20, 20) if x < width // 2 else (10, 20, 240)
            varied_ref.save(varied_ref_path)

            varied_result = measure_giant_region_legitimacy(config, varied_ref_path, mask_path)
            self.assertEqual("needs_review", varied_result["giant_region_legitimacy"])
            self.assertGreater(max(varied_result["giant_region_reference_color_std"]), 10.0)

    def test_giant_region_legitimacy_skips_when_region_not_giant(self):
        # 3 vùng chia đều (40/30/30 theo hàng) — vùng lớn nhất (40%) vẫn dưới ngưỡng giant
        # mặc định (55%), nên hàm phải bỏ qua hoàn toàn, không đo std.
        width, height = 10, 10
        config = {
            "width": width,
            "height": height,
            "regions": [
                {"id": 1, "mask_color": "#ffffff", "area": 40},
                {"id": 2, "mask_color": "#000000", "area": 30},
                {"id": 3, "mask_color": "#ff0000", "area": 30},
            ],
        }
        mask = Image.new("RGB", (width, height), (0, 0, 0))
        mask_pixels = mask.load()
        for y in range(4):
            for x in range(width):
                mask_pixels[x, y] = (255, 255, 255)
        for y in range(7, height):
            for x in range(width):
                mask_pixels[x, y] = (255, 0, 0)

        with tempfile.TemporaryDirectory() as tmp:
            mask_path = os.path.join(tmp, "mask.png")
            mask.save(mask_path)
            ref_path = os.path.join(tmp, "ref.png")
            Image.new("RGB", (width, height), (250, 30, 30)).save(ref_path)

            result = measure_giant_region_legitimacy(config, ref_path, mask_path)
            self.assertIsNone(result["giant_region_legitimacy"])
            self.assertIsNone(result["giant_region_reference_color_std"])

    def test_recommendation_accepts_flat_background_when_it_is_the_sole_issue(self):
        recommendation = build_recommendation(
            {
                "quality_grade": "C",
                "warnings": [{"code": "GIANT_REGION_WARNING"}],
                "fail_reasons": [],
                "metrics": {"giant_region_legitimacy": "intentional_flat_background"},
            }
        )
        self.assertIn("ACCEPT_FLAT_BACKGROUND", recommendation["reasons"])
        self.assertNotIn("REGENERATE_AUTO", recommendation["reasons"])

    def test_recommendation_still_flags_when_other_issues_coexist_with_flat_background(self):
        # Nền phẳng có chủ đích KHÔNG che giấu các vấn đề khác thật sự tồn tại (ở đây:
        # quá nhiều vùng nhỏ) — vẫn phải khuyến nghị xem lại như bình thường.
        recommendation = build_recommendation(
            {
                "quality_grade": "C",
                "warnings": [
                    {"code": "GIANT_REGION_WARNING"},
                    {"code": "TINY_REGION_DENSITY_WARNING"},
                ],
                "fail_reasons": [],
                "metrics": {"giant_region_legitimacy": "intentional_flat_background"},
            }
        )
        self.assertIn("REGENERATE_AUTO", recommendation["reasons"])
        self.assertNotIn("ACCEPT_FLAT_BACKGROUND", recommendation["reasons"])


if __name__ == "__main__":
    unittest.main()
