import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from tools.generate_hi_res_display_lines import generate_for_level


class GenerateHiResDisplayLinesTest(unittest.TestCase):
    def test_generates_scaled_display_lines_and_updates_config(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            level_dir = Path(temp_dir)
            config = {
                "assets": {
                    "display_line": "display_line.png",
                }
            }
            (level_dir / "config.json").write_text(json.dumps(config), encoding="utf-8")
            Image.new("RGB", (8, 6), (255, 255, 255)).save(level_dir / "display_line.png")

            generated = generate_for_level(
                level_dir=level_dir,
                scales=[2, 4],
                resampling=Image.Resampling.NEAREST,
                sharpen_amount=0,
                overwrite=False,
            )

            self.assertEqual(
                [level_dir / "display_line_2x.png", level_dir / "display_line_4x.png"],
                generated,
            )
            with Image.open(level_dir / "display_line_2x.png") as image:
                self.assertEqual((16, 12), image.size)
            with Image.open(level_dir / "display_line_4x.png") as image:
                self.assertEqual((32, 24), image.size)

            updated = json.loads((level_dir / "config.json").read_text(encoding="utf-8"))
            self.assertEqual("display_line_2x.png", updated["assets"]["display_line_2x"])
            self.assertEqual("display_line_4x.png", updated["assets"]["display_line_4x"])


if __name__ == "__main__":
    unittest.main()
