import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image, ImageDraw

from tools.asset_quality import evaluate_level_dir, measure_preview_mae, screen_size_scale_factor
from tools.generate_level import (
    LevelQualityGateError,
    absorb_small_region_colors,
    create_parser,
    evaluate_quality_gate,
    find_label_anchor,
    generate_level_assets,
    get_region_bbox,
    get_region_centroid,
    get_representative_color,
    merge_small_attached_regions,
    merge_tiny_regions_into_neighbors,
    reclaim_non_ink_pixels_into_regions,
    resolve_generation_profile,
    resolve_generation_profile_settings,
    resolve_target_unique_colors,
    score_preprocessing_candidate,
    select_preprocessing_candidate,
    split_remaining_giant_regions,
    run_batch_source_category,
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
        medium = resolve_generation_profile_settings("medium")
        standard = resolve_generation_profile_settings("standard")
        easy = resolve_generation_profile_settings("easy")
        casual = resolve_generation_profile_settings("casual")
        hard = resolve_generation_profile_settings("hard")
        mandala = resolve_generation_profile_settings("mandala")

        self.assertEqual("medium", resolve_generation_profile("standard"))
        self.assertEqual("casual", resolve_generation_profile("easy"))
        self.assertEqual(64, medium["target_unique_colors"])
        self.assertEqual(medium, standard)
        self.assertEqual(casual, easy)
        self.assertGreater(casual["tiny_merge_min_area"], medium["tiny_merge_min_area"])
        self.assertEqual(80, hard["target_unique_colors"])
        self.assertLess(hard["tiny_merge_min_area"], medium["tiny_merge_min_area"])
        self.assertLess(mandala["hide_small_label_threshold"], medium["hide_small_label_threshold"])
        self.assertEqual("mandala", mandala["tiny_merge_policy"])

        overridden = resolve_generation_profile_settings(
            "casual",
            explicit_target=12,
            overrides={"min_region_area": 99},
        )
        self.assertEqual(12, overridden["target_unique_colors"])
        self.assertEqual(99, overridden["min_region_area"])

    def test_tiny_region_thresholds_scale_with_canvas_resolution(self):
        # Ảnh nguồn ở đúng REFERENCE_CANVAS_SIZE (1024) phải giữ nguyên hành vi cũ tuyệt
        # đối (không đổi ngưỡng) — đây là cam kết không phá vỡ ảnh 1024px hiện có.
        self.assertEqual(1.0, screen_size_scale_factor(1024, 1024))
        baseline = resolve_generation_profile_settings("medium")
        at_reference_size = resolve_generation_profile_settings(
            "medium", screen_scale=screen_size_scale_factor(1024, 1024)
        )
        self.assertEqual(baseline, at_reference_size)

        # Ảnh 1536px (có thật trong Data/) phải có ngưỡng lớn hơn theo đúng tỉ lệ bình
        # phương/tuyến tính, không phải giữ nguyên số pixel thô của ảnh 1024px.
        scale_1536 = screen_size_scale_factor(1536, 1536)
        self.assertAlmostEqual(1.5, scale_1536)
        scaled = resolve_generation_profile_settings("medium", screen_scale=scale_1536)
        self.assertEqual(
            round(baseline["tiny_merge_min_side"] * 1.5), scaled["tiny_merge_min_side"]
        )
        self.assertEqual(
            round(baseline["tiny_merge_min_area"] * 1.5 * 1.5), scaled["tiny_merge_min_area"]
        )

        # Override CLI tường minh luôn thắng, không bị auto-scale theo resolution đè lên.
        explicit_override = resolve_generation_profile_settings(
            "medium", screen_scale=scale_1536, overrides={"tiny_merge_min_side": 6}
        )
        self.assertEqual(6, explicit_override["tiny_merge_min_side"])

        # Ảnh nhỏ hơn mốc tham chiếu không được phép làm ngưỡng NHỎ hơn profile default.
        smaller = resolve_generation_profile_settings(
            "medium", screen_scale=screen_size_scale_factor(512, 512)
        )
        self.assertEqual(baseline, smaller)

    def test_profile_vocabulary_accepts_existing_and_playability_names(self):
        parser = create_parser()

        for profile in ["easy", "standard", "mandala", "casual", "medium", "hard"]:
            args = parser.parse_args(["--category-profile", profile, "single", "line.png", "color.png"])
            self.assertEqual(profile, args.category_profile)

        self.assertEqual(("casual", 48), resolve_target_unique_colors("Animals", "casual", None))
        self.assertEqual(("medium", 64), resolve_target_unique_colors("Animals", "medium", None))
        self.assertEqual(("medium", 64), resolve_target_unique_colors("Animals", "standard", None))
        self.assertEqual(("casual", 48), resolve_target_unique_colors("Animals", "easy", None))
        self.assertEqual(("hard", 80), resolve_target_unique_colors("Animals", "hard", None))
        self.assertEqual(("mandala", 80), resolve_target_unique_colors("Mandala", None, None))

    def test_tiny_merge_v2_uses_actual_shared_boundary_not_only_bbox_overlap(self):
        tiny = self.make_region_info([(5, 5)], (250, 0, 0), hide_number=True)
        fake_bbox_neighbor = self.make_region_info(
            [(0, 0), (0, 10), (10, 0), (10, 10)],
            (250, 0, 0),
        )
        real_near_neighbor = self.make_region_info(
            [(6, 5), (6, 6), (7, 5), (7, 6)],
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

    def test_tiny_merge_v2_does_not_merge_across_line_gap_even_when_nearby(self):
        tiny = self.make_region_info([(5, 5)], (250, 0, 0), hide_number=True)
        separated_by_boundary = self.make_region_info(
            [(7, 5), (7, 6), (8, 5), (8, 6)],
            (250, 0, 0),
        )

        merged, merged_count, forced_count, remaining = merge_tiny_regions_into_neighbors(
            region_infos=[tiny, separated_by_boundary],
            min_region_area=1,
            tiny_area_threshold=10,
            tiny_side_threshold=3,
            tiny_merge_min_area=2,
            tiny_merge_min_side=2,
            attach_distance=12,
            color_threshold=80,
            tiny_merge_policy="relaxed",
        )

        self.assertEqual(0, merged_count)
        self.assertEqual(0, forced_count)
        self.assertEqual(1, remaining)
        self.assertEqual(2, len(merged))

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

    def test_tiny_merge_v2_fallback_still_requires_adjacency(self):
        tiny = self.make_region_info([(5, 5)], (250, 0, 0), hide_number=True)
        non_adjacent = self.make_region_info(
            [(7, 5), (7, 6), (8, 5), (8, 6)],
            (0, 0, 250),
        )

        merged, merged_count, forced_count, remaining = merge_tiny_regions_into_neighbors(
            region_infos=[tiny, non_adjacent],
            min_region_area=1,
            tiny_area_threshold=10,
            tiny_side_threshold=3,
            tiny_merge_min_area=2,
            tiny_merge_min_side=2,
            attach_distance=12,
            color_threshold=1,
            tiny_merge_policy="mandala",
        )

        self.assertEqual(0, merged_count)
        self.assertEqual(0, forced_count)
        self.assertEqual(1, remaining)
        self.assertEqual(2, len(merged))

    def test_tiny_merge_v2_prefers_closest_color_among_adjacent_neighbors(self):
        tiny = self.make_region_info([(5, 5)], (248, 0, 0), hide_number=True)
        color_match_neighbor = self.make_region_info([(4, 5), (4, 6)], (250, 0, 0))
        color_far_neighbor = self.make_region_info([(6, 5), (6, 6), (7, 5), (7, 6)], (0, 0, 250))

        merged, merged_count, forced_count, remaining = merge_tiny_regions_into_neighbors(
            region_infos=[tiny, color_far_neighbor, color_match_neighbor],
            min_region_area=1,
            tiny_area_threshold=10,
            tiny_side_threshold=3,
            tiny_merge_min_area=2,
            tiny_merge_min_side=2,
            attach_distance=1,
            color_threshold=10,
            tiny_merge_policy="strict",
        )

        self.assertEqual(1, merged_count)
        self.assertEqual(0, forced_count)
        self.assertEqual(0, remaining)
        self.assertTrue(any(info["area"] == 3 and info["target_color"] == (250, 0, 0) for info in merged))

    def test_tiny_merge_v2_does_not_miss_long_thin_adjacent_neighbor(self):
        tiny = self.make_region_info([(0, 1)], (250, 0, 0), hide_number=True)
        long_points = []
        for x in range(2, 802):
            long_points.append((x, 1))
            long_points.append((x, 2))
        long_points.append((1, 1))
        long_thin_neighbor = self.make_region_info(
            long_points,
            (250, 0, 0),
        )

        merged, merged_count, forced_count, remaining = merge_tiny_regions_into_neighbors(
            region_infos=[tiny, long_thin_neighbor],
            min_region_area=1,
            tiny_area_threshold=10,
            tiny_side_threshold=3,
            tiny_merge_min_area=2,
            tiny_merge_min_side=2,
            attach_distance=1,
            color_threshold=10,
            tiny_merge_policy="strict",
        )

        self.assertEqual(1, merged_count)
        self.assertEqual(0, forced_count)
        self.assertEqual(0, remaining)
        self.assertEqual(len(long_points) + 1, max(info["area"] for info in merged))

    def test_tiny_merge_v2_does_not_merge_into_giant_target_over_profile_limit(self):
        tiny = self.make_region_info([(10, 0)], (250, 0, 0), hide_number=True)
        giant_points = [(x, y) for y in range(90) for x in range(10)]
        giant_points.append((9, 0))
        giant_neighbor = self.make_region_info(giant_points, (250, 0, 0))
        safe_neighbor = self.make_region_info([(11, 0), (11, 1), (12, 0), (12, 1)], (240, 0, 0))

        merged, merged_count, forced_count, remaining, stats = merge_tiny_regions_into_neighbors(
            region_infos=[tiny, giant_neighbor, safe_neighbor],
            min_region_area=1,
            tiny_area_threshold=10,
            tiny_side_threshold=3,
            tiny_merge_min_area=2,
            tiny_merge_min_side=2,
            attach_distance=1,
            color_threshold=20,
            tiny_merge_policy="strict",
            profile="casual",
            total_pixels=1000,
            return_stats=True,
        )

        self.assertEqual(1, merged_count)
        self.assertEqual(0, forced_count)
        self.assertEqual(0, remaining)
        self.assertGreater(stats["tiny_merge_rejected_giant_target"], 0)
        self.assertTrue(any(info["area"] == 5 and info["target_color"] == (240, 0, 0) for info in merged))

    def test_production_merge_sequence_does_not_merge_small_regions_across_boundary_gap(self):
        small = self.make_region_info(
            [(2, 2), (2, 3), (3, 2), (3, 3)],
            (250, 0, 0),
            hide_number=True,
        )
        separated_by_line_gap = self.make_region_info(
            [(6, 2), (6, 3), (7, 2), (7, 3), (8, 2), (8, 3)],
            (250, 0, 0),
        )

        after_tiny_merge, tiny_count, forced_count, remaining = merge_tiny_regions_into_neighbors(
            region_infos=[small, separated_by_line_gap],
            min_region_area=10,
            tiny_area_threshold=1,
            tiny_side_threshold=1,
            tiny_merge_min_area=1,
            tiny_merge_min_side=1,
            attach_distance=12,
            color_threshold=80,
            tiny_merge_policy="relaxed",
        )
        after_small_merge, small_count = merge_small_attached_regions(
            region_infos=after_tiny_merge,
            min_region_area=10,
            attach_distance=12,
            color_threshold=80,
            tiny_area_threshold=1,
            tiny_side_threshold=1,
            tiny_merge_min_area=1,
            tiny_merge_min_side=1,
        )

        self.assertEqual(0, tiny_count)
        self.assertEqual(0, forced_count)
        self.assertEqual(0, remaining)
        self.assertEqual(0, small_count)
        self.assertEqual(2, len(after_small_merge))

    def test_absorb_small_region_colors_does_not_copy_color_across_boundary_gap(self):
        small = self.make_region_info([(2, 2), (2, 3)], (250, 0, 0), hide_number=True)
        separated_by_line_gap = self.make_region_info(
            [(5, 2), (5, 3), (6, 2), (6, 3)],
            (240, 0, 0),
        )

        absorbed_count = absorb_small_region_colors(
            region_infos=[small, separated_by_line_gap],
            attach_distance=12,
            color_threshold=80,
            tiny_area_threshold=10,
            tiny_side_threshold=3,
        )

        self.assertEqual(0, absorbed_count)
        self.assertEqual((250, 0, 0), small["target_color"])

    def test_absorb_small_region_colors_can_copy_color_from_adjacent_neighbor(self):
        small = self.make_region_info([(2, 2), (2, 3)], (250, 0, 0), hide_number=True)
        adjacent_neighbor = self.make_region_info(
            [(3, 2), (3, 3), (4, 2), (4, 3)],
            (240, 0, 0),
        )

        absorbed_count = absorb_small_region_colors(
            region_infos=[small, adjacent_neighbor],
            attach_distance=0,
            color_threshold=80,
            tiny_area_threshold=10,
            tiny_side_threshold=3,
        )

        self.assertEqual(1, absorbed_count)
        self.assertEqual((240, 0, 0), small["target_color"])

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
        binary = Image.new("L", (240, 240), 0)
        draw = ImageDraw.Draw(binary)
        for index in range(45):
            x = (index % 15) * 8
            y = (index // 15) * 12
            draw.rectangle((x, y, x + 2, y + 2), fill=255)
        for index in range(55):
            x = (index % 11) * 20
            y = 70 + (index // 11) * 28
            draw.rectangle((x, y, x + 14, y + 14), fill=255)

        casual_report = score_preprocessing_candidate(binary, playability_profile="casual")
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
                    "--allow-low-quality",
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
            self.assertEqual(
                "shared_boundary_adjacency",
                config["generation_params"]["small_region_merge_mode"],
            )
            self.assertEqual(
                "shared_boundary_adjacency",
                config["generation_params"]["small_color_absorb_mode"],
            )
            self.assertEqual(
                "shared_boundary_adjacency",
                config["generation_params"]["tiny_region_merge_mode"],
            )
            self.assertNotIn("small_region_attach_distance", config["generation_params"])
            self.assertNotIn("tiny_merge_attach_distance", config["generation_params"])
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
                    "--allow-low-quality",
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
            self.assertEqual("pre_generation_proxy_post_merge", top_candidate["evaluation_mode"])
            self.assertIn("candidate_proxy_score", top_candidate)
            self.assertIn("candidate_proxy_grade", top_candidate)
            self.assertIsNone(top_candidate["preview_mae"])
            self.assertEqual("not_available_before_full_generation", top_candidate["preview_mae_reason"])

    def test_source_line_preprocessing_treats_gray_antialias_strokes_as_boundaries(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            line_path = tmp_dir / "line.png"
            ref_path = tmp_dir / "ref.png"
            output_dir = tmp_dir / "assets" / "Test" / "gray-line"

            line = Image.new("RGB", (16, 10), "white")
            draw = ImageDraw.Draw(line)
            draw.rectangle((0, 0, 15, 9), outline="black")
            draw.line((8, 0, 8, 9), fill=(210, 210, 210))
            line.save(line_path)

            ref = Image.new("RGB", (16, 10), "white")
            ref_draw = ImageDraw.Draw(ref)
            ref_draw.rectangle((1, 1, 7, 8), fill=(240, 40, 40))
            ref_draw.rectangle((9, 1, 14, 8), fill=(40, 90, 240))
            ref.save(ref_path)

            subprocess.run(
                [
                    sys.executable,
                    str(GENERATOR),
                    "--target-unique-colors",
                    "4",
                    "--min-region-area",
                    "1",
                    "--hide-small-label-threshold",
                    "1",
                    "--tiny-merge-min-area",
                    "1",
                    "--tiny-merge-min-side",
                    "1",
                    "--allow-low-quality",
                    "single",
                    str(line_path),
                    str(ref_path),
                    "--category",
                    "Test",
                    "--name",
                    "Gray line",
                    "--id",
                    "gray-line",
                    "--output-directory",
                    str(output_dir),
                ],
                check=True,
                cwd=PROJECT_ROOT,
            )

            config = json.loads((output_dir / "config.json").read_text())
            report = json.loads((output_dir / "debug_report.json").read_text())
            line_pixels = Image.open(output_dir / "line.png").convert("RGB").load()
            preview_pixels = Image.open(output_dir / "preview_colored.png").convert("RGB").load()

            self.assertGreaterEqual(config["stats"]["total_regions"], 2)
            self.assertEqual(
                "source-line",
                report["generation_params"]["selected_preprocessing"]["profile"],
            )
            self.assertEqual((0, 0, 0), line_pixels[8, 5])
            self.assertLess(preview_pixels[8, 5][0], 230)

    def test_candidate_scoring_sees_post_merge_consolidation_not_just_raw_fragments(self):
        # extract_regions() is monkeypatched to return a host blob plus 20 single-pixel
        # fragments that are pixel-adjacent to the host and near-identical in color, the
        # same shape merge_tiny_regions_into_neighbors expects when real thin/dashed line
        # art produces slivers that get absorbed into a neighbor. Raw (pre-merge) scoring
        # should see 21 separate small regions; post-merge scoring (ref_img + merge
        # settings) should see them consolidate back into the true dominant region, the
        # exact effect the old pre-merge-only scoring was blind to.
        width, height = 100, 100
        binary = Image.new("L", (width, height), 0)
        ref = Image.new("RGB", (width, height), (255, 255, 255))
        ref_pixels = ref.load()

        host_points = [(x, y) for y in range(40) for x in range(40)]
        for x, y in host_points:
            ref_pixels[x, y] = (200, 0, 0)

        fragment_points_list = []
        for index in range(20):
            y = index * 2
            points = [(40, y), (41, y)]
            fragment_points_list.append(points)
            for point in points:
                ref_pixels[point] = (198, 2, 2)

        all_regions = [host_points] + fragment_points_list
        merge_settings = {
            "min_region_area": 50,
            "tiny_area_threshold": 50,
            "tiny_side_threshold": 5,
            "tiny_merge_min_area": 10,
            "tiny_merge_min_side": 3,
            "tiny_merge_color_threshold": 30,
            "small_merge_color_threshold": 30,
            "tiny_merge_policy": "relaxed",
            "profile": "medium",
        }

        with mock.patch("tools.generate_level.extract_regions", return_value=all_regions):
            raw_report = score_preprocessing_candidate(binary, playability_profile="medium")
            merged_report = score_preprocessing_candidate(
                binary,
                playability_profile="medium",
                ref_img=ref,
                merge_settings=merge_settings,
            )

        self.assertEqual("pre_generation_proxy", raw_report["evaluation_mode"])
        self.assertEqual(21, raw_report["total_regions"])
        self.assertEqual(20, raw_report["tiny_region_count_lt_50"])

        self.assertEqual("pre_generation_proxy_post_merge", merged_report["evaluation_mode"])
        self.assertEqual(1, merged_report["total_regions"])
        self.assertEqual(0, merged_report["tiny_region_count_lt_50"])
        self.assertGreater(merged_report["largest_region_pct"], raw_report["largest_region_pct"])

    def test_evaluate_quality_gate_flags_grade_and_largest_pct(self):
        ok_report = {
            "quality_grade": "B",
            "metrics": {"largest_region_pct": 40, "giant_region_count": 0},
        }
        self.assertEqual([], evaluate_quality_gate(ok_report))

        bad_report = {
            "quality_grade": "D",
            "metrics": {"largest_region_pct": 80, "giant_region_count": 2},
        }
        reasons = evaluate_quality_gate(bad_report)
        self.assertEqual(2, len(reasons))

    def test_evaluate_quality_gate_does_not_block_on_giant_region_count_alone(self):
        # giant_region_count dùng ngưỡng cứng 50% (asset_quality.py), không đồng bộ với
        # ngưỡng 55% của gate — 1 vùng chiếm 51-55% là hợp lệ (B grade), không được chặn.
        report = {
            "quality_grade": "B",
            "metrics": {"largest_region_pct": 54.4, "giant_region_count": 1},
        }
        self.assertEqual([], evaluate_quality_gate(report))

    def test_hard_gate_raises_and_does_not_write_low_quality_asset(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            line_path = tmp_dir / "line.png"
            ref_path = tmp_dir / "ref.png"
            output_dir = tmp_dir / "assets" / "Test" / "badlevel"

            line = Image.new("RGB", (16, 16), "white")
            draw = ImageDraw.Draw(line)
            draw.rectangle((0, 0, 15, 15), outline="black")
            line.save(line_path)

            ref = Image.new("RGB", (16, 16), (200, 30, 30))
            ref.save(ref_path)

            with self.assertRaises(LevelQualityGateError):
                generate_level_assets(
                    str(line_path),
                    str(ref_path),
                    str(output_dir),
                    category_name="Test",
                    data_name="BadLevel",
                    generated_id="badlevel",
                    target_unique_colors=4,
                )

            self.assertFalse(output_dir.exists())
            self.assertEqual([], list(tmp_dir.glob("assets/Test/.level-staging-*")))

            generate_level_assets(
                str(line_path),
                str(ref_path),
                str(output_dir),
                category_name="Test",
                data_name="BadLevel",
                generated_id="badlevel",
                target_unique_colors=4,
                allow_low_quality=True,
            )

            self.assertTrue((output_dir / "config.json").exists())
            report = json.loads((output_dir / "debug_report.json").read_text())
            self.assertEqual("D", report["quality_grade"])

    def test_batch_source_category_can_continue_after_level_quality_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            input_category = tmp_dir / "Data" / "Batch"
            output_root = tmp_dir / "assets"
            for level_id in ["01", "02"]:
                level_dir = input_category / level_id
                level_dir.mkdir(parents=True)
                Image.new("RGB", (4, 4), "white").save(level_dir / "line.png")
                Image.new("RGB", (4, 4), "white").save(level_dir / "color.png")

            args = create_parser().parse_args(
                [
                    "--output-root",
                    str(output_root),
                    "batch-source-category",
                    str(input_category),
                    "--continue-on-error",
                ]
            )

            with mock.patch(
                "tools.generate_level.generate_level_assets",
                side_effect=[LevelQualityGateError("bad quality"), None],
            ) as generate_mock:
                run_batch_source_category(args)

            self.assertEqual(2, generate_mock.call_count)
            self.assertEqual("01", generate_mock.call_args_list[0].kwargs["generated_id"])
            self.assertEqual("02", generate_mock.call_args_list[1].kwargs["generated_id"])

    def test_split_remaining_giant_regions_recovers_color_patches_after_merge(self):
        width, height = 20, 10
        ref = Image.new("RGB", (width, height))
        ref_pixels = ref.load()
        for y in range(height):
            for x in range(width):
                ref_pixels[x, y] = (220, 30, 30) if x < 10 else (30, 30, 220)

        points = [(x, y) for y in range(height) for x in range(width)]
        giant_info = self.make_region_info(points, (125, 30, 125))
        giant_info["merged_region_count"] = 5

        updated_infos, stats = split_remaining_giant_regions(
            region_infos=[giant_info],
            ref_img=ref,
            total_pixels=width * height,
            giant_pct_threshold=50.0,
            region_color_method="median",
            min_region_area=5,
            tiny_area_threshold=5,
            tiny_side_threshold=2,
            tiny_merge_min_area=2,
            tiny_merge_min_side=2,
        )

        self.assertEqual(1, stats["giant_region_split_count"])
        self.assertGreaterEqual(len(updated_infos), 2)
        self.assertGreater(stats["giant_region_sub_segments"], 1)
        colors = {info["target_color"] for info in updated_infos}
        self.assertTrue(any(color[0] > color[2] for color in colors))
        self.assertTrue(any(color[2] > color[0] for color in colors))

    def test_reclaim_gives_back_paper_pixels_but_never_real_ink(self):
        # Bố cục 1 hàng: [vùng A][giấy trắng bị close filter ăn][nét mực thật][giấy][vùng B].
        # Chỉ pixel giấy (sáng hơn ngưỡng) mới được trả về vùng liền kề; nét mực phải giữ
        # nguyên làm ranh giới, nếu không 2 vùng sẽ bị nối liền qua nét vẽ.
        width, height = 7, 1
        threshold = 170
        brightness = [30, 250, 250, 40, 250, 250, 30]
        source_gray = Image.new("L", (width, height))
        source_gray.putdata(brightness)

        region_a = [(0, 0)]
        region_b = [(6, 0)]
        regions, reclaimed = reclaim_non_ink_pixels_into_regions(
            regions=[region_a, region_b],
            width=width,
            height=height,
            source_gray=source_gray,
            brightness_threshold=threshold,
        )

        self.assertEqual(4, reclaimed)
        self.assertEqual({(0, 0), (1, 0), (2, 0)}, set(regions[0]))
        self.assertEqual({(6, 0), (5, 0), (4, 0)}, set(regions[1]))
        # Pixel mực ở giữa (x=3) không được gán cho vùng nào.
        self.assertNotIn((3, 0), set(regions[0]) | set(regions[1]))

    def test_reclaim_also_recovers_paper_pockets_fully_enclosed_by_ink(self):
        # [vùng][mực][giấy bị bao kín][mực] — túi giấy ở x=2 không có hàng xóm nào thuộc
        # vùng nào, lượt lan qua-giấy không với tới; phải được lượt 2 (xuyên nét mực) thu hồi,
        # nếu không nó sẽ là chấm trắng nằm lọt giữa nét vẽ trên preview.
        width, height = 4, 1
        source_gray = Image.new("L", (width, height))
        source_gray.putdata([250, 40, 250, 40])

        regions, reclaimed = reclaim_non_ink_pixels_into_regions(
            regions=[[(0, 0)]],
            width=width,
            height=height,
            source_gray=source_gray,
            brightness_threshold=170,
        )

        self.assertEqual(1, reclaimed)
        self.assertEqual({(0, 0), (2, 0)}, set(regions[0]))
        # Nét mực vẫn không bao giờ bị nuốt vào vùng.
        self.assertNotIn((1, 0), set(regions[0]))
        self.assertNotIn((3, 0), set(regions[0]))

    def test_reclaim_is_noop_when_every_pixel_already_belongs_to_a_region(self):
        source_gray = Image.new("L", (3, 1))
        source_gray.putdata([250, 250, 250])
        regions, reclaimed = reclaim_non_ink_pixels_into_regions(
            regions=[[(0, 0), (1, 0), (2, 0)]],
            width=3,
            height=1,
            source_gray=source_gray,
            brightness_threshold=170,
        )
        self.assertEqual(0, reclaimed)
        self.assertEqual([[(0, 0), (1, 0), (2, 0)]], regions)

    def test_find_label_anchor_radius_reflects_real_space_for_solid_rectangle(self):
        points = [(x, y) for y in range(10) for x in range(20)]
        bbox = get_region_bbox(points)
        centroid = get_region_centroid(points)

        anchor = find_label_anchor(points, bbox, centroid)

        self.assertIn("radius", anchor)
        # Hình chữ nhật đặc 20x10: có chỗ trống thật ở giữa, radius phải phản ánh đúng —
        # xấp xỉ nửa cạnh ngắn (5), không phải một con số vụn vặt gần 0.
        self.assertGreaterEqual(anchor["radius"], 3)
        self.assertLessEqual(anchor["radius"], 5)

    def test_find_label_anchor_radius_stays_small_for_thin_l_shape_despite_large_bbox(self):
        # Hình chữ L: 1 dải dọc rộng 3px (x=0..2, cao suốt) + 1 dải ngang rộng 3px
        # (y=27..29, dài suốt) bên trong 1 bbox 30x30. Cách tính CŨ ở phía Kotlin (nửa cạnh
        # ngắn của bbox) sẽ cho ra 15 — sai hoàn toàn, vì "thịt" thật của vùng chỉ rộng 3px.
        vertical_strip = [(x, y) for y in range(30) for x in range(3)]
        horizontal_strip = [(x, y) for y in range(27, 30) for x in range(30)]
        points = list(set(vertical_strip) | set(horizontal_strip))
        bbox = get_region_bbox(points)
        centroid = get_region_centroid(points)

        self.assertEqual(14.5, min(bbox["right"] - bbox["left"], bbox["bottom"] - bbox["top"]) / 2)

        anchor = find_label_anchor(points, bbox, centroid)

        # radius thật phải nhỏ (dải chỉ rộng 3px => bán kính nội tiếp tối đa ~1), khác hẳn
        # con số 15 mà công thức bbox cũ sẽ tính nhầm.
        self.assertLessEqual(anchor["radius"], 2)

    def test_find_label_anchor_fallback_path_still_returns_a_radius(self):
        # Vùng 1 pixel duy nhất: vòng lặp chính không tìm được điểm hợp lệ nào (min_distance
        # tới bbox luôn < 1), phải rơi vào nhánh centroid_fallback — vẫn phải trả về radius
        # (0, vì không có chỗ trống nào) thay vì thiếu key hoặc lỗi.
        points = [(5, 5)]
        bbox = get_region_bbox(points)
        centroid = get_region_centroid(points)

        anchor = find_label_anchor(points, bbox, centroid)

        self.assertEqual(5.0, anchor["x"])
        self.assertEqual(5.0, anchor["y"])
        self.assertEqual(0.0, anchor["radius"])

    def test_split_remaining_giant_regions_leaves_region_not_formed_by_merge_untouched(self):
        width, height = 20, 10
        ref = Image.new("RGB", (width, height), (128, 60, 190))
        points = [(x, y) for y in range(height) for x in range(width)]
        giant_info = self.make_region_info(points, (128, 60, 190))
        giant_info["merged_region_count"] = 1

        updated_infos, stats = split_remaining_giant_regions(
            region_infos=[giant_info],
            ref_img=ref,
            total_pixels=width * height,
            giant_pct_threshold=50.0,
            region_color_method="median",
            min_region_area=5,
            tiny_area_threshold=5,
            tiny_side_threshold=2,
            tiny_merge_min_area=2,
            tiny_merge_min_side=2,
        )

        self.assertEqual(0, stats["giant_region_split_count"])
        self.assertEqual(1, len(updated_infos))


if __name__ == "__main__":
    unittest.main()
