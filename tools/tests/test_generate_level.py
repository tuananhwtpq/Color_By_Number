import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

from tools.asset_quality import evaluate_level_dir, measure_preview_mae
from tools.generate_level import (
    create_parser,
    get_representative_color,
    merge_tiny_regions_into_neighbors,
    resolve_generation_profile_settings,
    resolve_target_unique_colors,
    score_preprocessing_candidate,
    select_preprocessing_candidate,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
GENERATOR = PROJECT_ROOT / "tools" / "generate_level.py"


class GenerateLevelCliTest(unittest.TestCase):
    def make_region_info(self, points, target_color, hide_number=False):
        xs = [point[0] for point in points]
        ys = [point[1] for point in points]
        return {
            "region": list(points),
            "area": len(points),
            "bbox": {
                "left": min(xs),
                "top": min(ys),
                "right": max(xs),
                "bottom": max(ys),
            },
            "centroid": {
                "x": sum(xs) / len(points),
                "y": sum(ys) / len(points),
            },
            "label_anchor": {
                "x": sum(xs) / len(points),
                "y": sum(ys) / len(points),
            },
            "target_color": target_color,
            "hide_number": hide_number,
            "merged_region_count": 1,
        }

    def test_generation_profile_settings_keep_old_profiles_and_add_difficulty_defaults(self):
        standard = resolve_generation_profile_settings("standard")
        casual = resolve_generation_profile_settings("casual")
        hard = resolve_generation_profile_settings("hard")
        mandala = resolve_generation_profile_settings("mandala")

        self.assertEqual(64, standard["target_unique_colors"])
        self.assertEqual(standard["min_region_area"], casual["min_region_area"])
        self.assertEqual(80, hard["target_unique_colors"])
        self.assertLess(hard["tiny_merge_min_area"], casual["tiny_merge_min_area"])
        self.assertLess(mandala["hide_small_label_threshold"], standard["hide_small_label_threshold"])
        self.assertEqual("mandala", mandala["tiny_merge_policy"])

        overridden = resolve_generation_profile_settings(
            "easy",
            explicit_target=12,
            overrides={"min_region_area": 99},
        )
        self.assertEqual(12, overridden["target_unique_colors"])
        self.assertEqual(99, overridden["min_region_area"])

    def test_profile_vocabulary_accepts_existing_and_playability_names(self):
        parser = create_parser()

        for profile in ["easy", "standard", "mandala", "casual", "hard"]:
            args = parser.parse_args(["--category-profile", profile, "single", "line.png", "color.png"])
            self.assertEqual(profile, args.category_profile)

        self.assertEqual(("casual", 64), resolve_target_unique_colors("Animals", "casual", None))
        self.assertEqual(("hard", 80), resolve_target_unique_colors("Animals", "hard", None))
        self.assertEqual(("easy", 48), resolve_target_unique_colors("Animals", "easy", None))
        self.assertEqual(("standard", 64), resolve_target_unique_colors("Animals", "standard", None))
        self.assertEqual(("mandala", 80), resolve_target_unique_colors("Mandala", None, None))

    def test_tiny_merge_v2_uses_actual_edge_proximity_not_only_bbox_overlap(self):
        tiny = self.make_region_info([(5, 5)], (250, 0, 0), hide_number=True)
        fake_bbox_neighbor = self.make_region_info(
            [(0, 0), (0, 10), (10, 0), (10, 10)],
            (250, 0, 0),
        )
        real_near_neighbor = self.make_region_info(
            [(7, 5), (7, 6), (8, 5), (8, 6)],
            (0, 0, 250),
        )

        merged, merged_count, forced_count, remaining = merge_tiny_regions_into_neighbors(
            region_infos=[tiny, fake_bbox_neighbor, real_near_neighbor],
            min_region_area=1,
            tiny_area_threshold=10,
            tiny_side_threshold=3,
            tiny_merge_min_area=2,
            tiny_merge_min_side=2,
            attach_distance=2,
            color_threshold=1,
            tiny_merge_policy="relaxed",
        )

        self.assertEqual(1, merged_count)
        self.assertEqual(1, forced_count)
        self.assertEqual(0, remaining)
        self.assertEqual(2, len(merged))
        self.assertTrue(any(info["area"] == 5 and info["target_color"] == (0, 0, 250) for info in merged))

    def test_tiny_merge_v2_leaves_bbox_overlap_region_when_no_real_edge_neighbor(self):
        tiny = self.make_region_info([(5, 5)], (250, 0, 0), hide_number=True)
        fake_bbox_neighbor = self.make_region_info(
            [(0, 0), (0, 10), (10, 0), (10, 10)],
            (250, 0, 0),
        )

        merged, merged_count, forced_count, remaining = merge_tiny_regions_into_neighbors(
            region_infos=[tiny, fake_bbox_neighbor],
            min_region_area=1,
            tiny_area_threshold=10,
            tiny_side_threshold=3,
            tiny_merge_min_area=2,
            tiny_merge_min_side=2,
            attach_distance=2,
            color_threshold=80,
            tiny_merge_policy="relaxed",
        )

        self.assertEqual(0, merged_count)
        self.assertEqual(0, forced_count)
        self.assertEqual(1, remaining)
        self.assertEqual(2, len(merged))

    def test_candidate_selection_prefers_playable_score_over_proxy_quality(self):
        candidates = [
            {
                "profile": "standard",
                "score": 88,
                "quality_score": 88,
                "playable_score": 42,
                "largest_region_pct": 64.9,
                "total_regions": 523,
            },
            {
                "profile": "mandala",
                "score": 82,
                "quality_score": 82,
                "playable_score": 91,
                "largest_region_pct": 42.0,
                "total_regions": 540,
            },
        ]

        selected = select_preprocessing_candidate(candidates)

        self.assertEqual("mandala", selected["profile"])

    def test_candidate_scoring_uses_playability_profile_for_tiny_gate(self):
        binary = Image.new("L", (100, 100), 0)
        draw = ImageDraw.Draw(binary)
        for index in range(45):
            x = (index % 15) * 6
            y = (index // 15) * 20
            draw.rectangle((x, y, x + 2, y + 2), fill=255)
        for index in range(55):
            x = (index % 11) * 9
            y = 60 + (index // 11) * 8
            draw.rectangle((x, y, x + 5, y + 5), fill=255)

        casual_report = score_preprocessing_candidate(binary, playability_profile="standard")
        mandala_report = score_preprocessing_candidate(binary, playability_profile="mandala")

        self.assertIn(
            "TINY_REGION_DENSITY_WARNING",
            {issue["code"] for issue in casual_report["warnings"]},
        )
        self.assertNotIn(
            "TINY_REGION_DENSITY_WARNING",
            {issue["code"] for issue in mandala_report["warnings"]},
        )

    def test_representative_color_uses_median_to_ignore_outlier_highlights(self):
        ref = Image.new("RGB", (5, 1))
        pixels = ref.load()
        colors = [
            (100, 100, 100),
            (102, 101, 100),
            (101, 103, 102),
            (99, 100, 101),
            (255, 255, 255),
        ]
        for x, color in enumerate(colors):
            pixels[x, 0] = color

        representative = get_representative_color(
            pixels,
            [(x, 0) for x in range(5)],
            method="median",
        )

        self.assertEqual((101, 101, 101), representative)

    def test_single_generation_writes_schema_v2_and_debug_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            line_path = tmp_dir / "line.png"
            ref_path = tmp_dir / "ref.png"
            output_dir = tmp_dir / "assets" / "Test" / "001"

            line = Image.new("RGB", (16, 16), "white")
            draw = ImageDraw.Draw(line)
            draw.rectangle((0, 0, 15, 15), outline="black")
            draw.line((8, 0, 8, 15), fill="black")
            line.save(line_path)

            ref = Image.new("RGB", (16, 16), "white")
            ref_draw = ImageDraw.Draw(ref)
            ref_draw.rectangle((1, 1, 7, 14), fill=(255, 0, 0))
            ref_draw.rectangle((9, 1, 14, 14), fill=(0, 0, 255))
            ref.save(ref_path)

            subprocess.run(
                [
                    sys.executable,
                    str(GENERATOR),
                    "--target-unique-colors",
                    "4",
                    "single",
                    str(line_path),
                    str(ref_path),
                    "--category",
                    "Test",
                    "--name",
                    "Fixture",
                    "--id",
                    "001",
                    "--output-directory",
                    str(output_dir),
                ],
                check=True,
                cwd=PROJECT_ROOT,
            )

            config = json.loads((output_dir / "config.json").read_text())

            self.assertEqual(config["schema_version"], 2)
            self.assertEqual(config["assets"]["line"], "line.png")
            self.assertEqual(config["assets"]["mask"], "mask.png")
            self.assertEqual(config["assets"]["preview"], "preview_colored.png")
            self.assertEqual(config["assets"]["detail"], "detail.png")
            self.assertEqual(config["stats"]["total_regions"], len(config["regions"]))
            self.assertGreaterEqual(len(config["regions"]), 2)
            self.assertGreaterEqual(len(config["palette"]), 2)

            first_region = config["regions"][0]
            self.assertIn("id", first_region)
            self.assertIn("quality", first_region)
            self.assertIn("touchable", first_region["quality"])

            self.assertTrue((output_dir / "mask.png").exists())
            self.assertTrue((output_dir / "line.png").exists())
            self.assertTrue((output_dir / "preview_colored.png").exists())
            self.assertTrue((output_dir / "detail.png").exists())
            self.assertTrue((output_dir / "debug_regions.png").exists())
            self.assertTrue((output_dir / "debug_report.json").exists())
            self.assertTrue((output_dir / "debug_source_line.png").exists())

            report = json.loads((output_dir / "debug_report.json").read_text())
            self.assertEqual(report["schema_version"], 1)
            self.assertIn("warnings", report)
            self.assertIn("quality_grade", report)
            self.assertIn("quality_score", report)
            self.assertIn("fail_reasons", report)
            self.assertIn("metrics", report)
            self.assertTrue(report["generation_params"]["has_detail"])
            self.assertNotIn("candidate_top", config["generation_params"]["selected_preprocessing"])
            self.assertNotIn("candidate_playable_score", config["generation_params"]["selected_preprocessing"])
            self.assertNotIn("candidate_top", config["generation"]["selected_preprocessing"])
            self.assertIn("candidate_top", report["generation_params"]["selected_preprocessing"])
            self.assertEqual("reference_lerp_rgba", report["generation_params"]["detail_mode"])
            self.assertIn("preview_similarity_score", report["metrics"])

    def test_detail_layer_improves_gradient_preview_without_increasing_regions(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            line_path = tmp_dir / "line.png"
            ref_path = tmp_dir / "ref.png"
            output_dir = tmp_dir / "assets" / "Test" / "gradient"

            line = Image.new("RGB", (18, 10), "white")
            draw = ImageDraw.Draw(line)
            draw.rectangle((0, 0, 17, 9), outline="black")
            line.save(line_path)

            ref = Image.new("RGB", (18, 10), "white")
            ref_pixels = ref.load()
            for y in range(1, 9):
                for x in range(1, 17):
                    value = 40 + x * 10
                    ref_pixels[x, y] = (value, 80, 180)
            ref.save(ref_path)

            subprocess.run(
                [
                    sys.executable,
                    str(GENERATOR),
                    "--target-unique-colors",
                    "2",
                    "single",
                    str(line_path),
                    str(ref_path),
                    "--category",
                    "Test",
                    "--name",
                    "Gradient",
                    "--id",
                    "gradient",
                    "--output-directory",
                    str(output_dir),
                ],
                check=True,
                cwd=PROJECT_ROOT,
            )

            config = json.loads((output_dir / "config.json").read_text())
            report = json.loads((output_dir / "debug_report.json").read_text())
            flat_preview_path = output_dir / "debug_preview_flat.png"
            final_preview_path = output_dir / "preview_colored.png"

            self.assertEqual(1, config["stats"]["total_regions"])
            self.assertTrue((output_dir / "detail.png").exists())
            self.assertTrue(flat_preview_path.exists())
            self.assertLess(
                measure_preview_mae(ref_path, final_preview_path),
                measure_preview_mae(ref_path, flat_preview_path),
            )
            self.assertTrue(report["generation_params"]["has_detail"])

    def test_auto_preprocessing_reports_candidates_and_does_not_degrade_grid_fixture(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            line_path = tmp_dir / "line.png"
            ref_path = tmp_dir / "ref.png"
            standard_dir = tmp_dir / "standard"
            auto_dir = tmp_dir / "auto"

            line = Image.new("RGB", (32, 32), "white")
            draw = ImageDraw.Draw(line)
            for coordinate in range(0, 33, 8):
                draw.line((coordinate, 0, coordinate, 31), fill="black")
                draw.line((0, coordinate, 31, coordinate), fill="black")
            line.save(line_path)

            ref = Image.new("RGB", (32, 32), "white")
            ref_draw = ImageDraw.Draw(ref)
            colors = [
                (220, 40, 40),
                (40, 180, 80),
                (60, 90, 220),
                (220, 180, 40),
            ]
            for row in range(4):
                for col in range(4):
                    color = colors[(row + col) % len(colors)]
                    ref_draw.rectangle(
                        (col * 8 + 1, row * 8 + 1, col * 8 + 7, row * 8 + 7),
                        fill=color,
                    )
            ref.save(ref_path)

            base_command = [
                sys.executable,
                str(GENERATOR),
                "--target-unique-colors",
                "4",
            ]
            subprocess.run(
                [
                    *base_command,
                    "single",
                    str(line_path),
                    str(ref_path),
                    "--category",
                    "Test",
                    "--name",
                    "Grid",
                    "--id",
                    "standard",
                    "--output-directory",
                    str(standard_dir),
                ],
                check=True,
                cwd=PROJECT_ROOT,
            )
            subprocess.run(
                [
                    *base_command,
                    "--preprocess-profile",
                    "auto",
                    "single",
                    str(line_path),
                    str(ref_path),
                    "--category",
                    "Test",
                    "--name",
                    "Grid",
                    "--id",
                    "auto",
                    "--output-directory",
                    str(auto_dir),
                ],
                check=True,
                cwd=PROJECT_ROOT,
            )

            standard_report = evaluate_level_dir(standard_dir, reference_path=ref_path)
            auto_report = evaluate_level_dir(auto_dir, reference_path=ref_path)
            auto_debug_report = json.loads((auto_dir / "debug_report.json").read_text())

            self.assertGreaterEqual(
                auto_report["quality_score"],
                standard_report["quality_score"],
            )
            selected_preprocessing = auto_debug_report["generation_params"]["selected_preprocessing"]
            self.assertIn("candidate_top", selected_preprocessing)
            self.assertGreaterEqual(len(selected_preprocessing["candidate_top"]), 1)
            top_candidate = selected_preprocessing["candidate_top"][0]
            self.assertEqual("pre_generation_proxy", top_candidate["evaluation_mode"])
            self.assertIn("candidate_proxy_score", top_candidate)
            self.assertIn("candidate_proxy_grade", top_candidate)
            self.assertIsNone(top_candidate["preview_mae"])
            self.assertEqual("not_available_before_full_generation", top_candidate["preview_mae_reason"])


if __name__ == "__main__":
    unittest.main()
