import csv
import tempfile
import unittest
from pathlib import Path

from tools.validate_assets import write_csv_report, write_markdown_report


def make_report_row():
    return {
        "category": "Manga",
        "level": "03",
        "name": "Manga/03",
        "path": "/tmp/Manga/03",
        "quality_grade": "D",
        "quality_score": 43,
        "fail_reasons": [{"code": "GIANT_REGION"}],
        "warnings": [{"code": "LINE_TOO_LIGHT"}, {"code": "PREVIEW_MAE_HIGH"}],
        "recommendation": {
            "action": "EXCLUDE_DEMO",
            "reasons": ["DESIGN_FIX_LINE", "DESIGN_FIX_COLOR"],
            "design_focus": ["line", "color_alignment"],
        },
        "metrics": {
            "total_regions": 10,
            "unique_numbers": 9,
            "largest_region_pct": 84.8,
            "preview_mae": 65.5,
            "line_dark_pct": 1.9,
        },
        "selected_profile": "manga:170:2",
    }


class ValidateAssetsReportTest(unittest.TestCase):
    def test_csv_report_contains_quality_columns_and_recommendation(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            report_path = Path(temp_dir) / "report.csv"

            write_csv_report([make_report_row()], report_path)

            with report_path.open(newline="", encoding="utf-8") as input_file:
                rows = list(csv.DictReader(input_file))
            self.assertEqual("Manga", rows[0]["category"])
            self.assertEqual("03", rows[0]["level"])
            self.assertEqual("D", rows[0]["grade"])
            self.assertEqual("EXCLUDE_DEMO", rows[0]["recommendation"])
            self.assertEqual("GIANT_REGION", rows[0]["fail_reasons"])
            self.assertIn("LINE_TOO_LIGHT", rows[0]["warnings"])
            self.assertEqual("manga:170:2", rows[0]["selected_profile"])

    def test_markdown_report_contains_quality_columns_and_recommendation(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            report_path = Path(temp_dir) / "report.md"

            write_markdown_report([make_report_row()], report_path)

            content = report_path.read_text(encoding="utf-8")
            self.assertIn("| Category | Level | Grade | Score |", content)
            self.assertIn("| Manga | 03 | D | 43 |", content)
            self.assertIn("EXCLUDE_DEMO", content)
            self.assertIn("DESIGN_FIX_LINE", content)


if __name__ == "__main__":
    unittest.main()
