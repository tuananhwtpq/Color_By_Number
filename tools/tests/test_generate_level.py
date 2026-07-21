import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

from tools.asset_quality import evaluate_level_dir


PROJECT_ROOT = Path(__file__).resolve().parents[2]
GENERATOR = PROJECT_ROOT / "tools" / "generate_level.py"


class GenerateLevelCliTest(unittest.TestCase):
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
