import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw


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

            report = json.loads((output_dir / "debug_report.json").read_text())
            self.assertEqual(report["schema_version"], 1)
            self.assertIn("warnings", report)


if __name__ == "__main__":
    unittest.main()
