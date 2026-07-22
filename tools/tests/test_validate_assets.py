import contextlib
import csv
import io
import tempfile
import unittest
from pathlib import Path

from tools.validate_assets import (
    COMPACT_TABLE_COLUMNS,
    FULL_TABLE_COLUMNS,
    format_table,
    print_table,
    write_csv_report,
    write_markdown_report,
)


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
            "tiny_region_pct_lt_100": 30.0,
            "tiny_region_pct_lt_200": 40.0,
            "hidden_label_pct": 20.0,
            "config_hidden_label_pct": 10.0,
            "estimated_hidden_label_pct": 20.0,
            "label_min_screen_radius_px": 25,
            "median_region_area": 285,
            "region_density_by_canvas_size": 49.0,
            "tiny_region_by_number_lt_100": {1: 2, "unknown": 1},
            "preview_mae": 65.5,
            "preview_similarity_score": 74.3,
            "has_detail": True,
            "line_dark_pct": 1.9,
        },
        "selected_profile": "manga:170:2",
    }


class ValidateAssetsReportTest(unittest.TestCase):
    def test_terminal_table_aligns_text_and_numeric_columns(self):
        columns = [
            {"key": "grade", "title": "grade", "align": "left"},
            {"key": "score", "title": "score", "align": "right"},
            {"key": "level", "title": "level", "align": "left"},
            {"key": "detail", "title": "detail", "align": "left"},
        ]
        rows = [
            {"grade": "A", "score": 100, "level": "Animals/01", "detail": True},
            {"grade": "D", "score": 30, "level": "Summer/02", "detail": None},
        ]

        output = format_table(rows, columns)

        lines = output.splitlines()
        self.assertEqual("grade  score  level       detail", lines[0])
        self.assertEqual("-----  -----  ----------  ------", lines[1])
        self.assertEqual("A        100  Animals/01  Y     ", lines[2])
        self.assertEqual("D         30  Summer/02   -     ", lines[3])

    def test_terminal_table_separator_matches_column_count(self):
        columns = [
            {"key": "grade", "title": "grade", "align": "left"},
            {"key": "score", "title": "score", "align": "right"},
            {"key": "fails", "title": "fails", "align": "left"},
        ]
        rows = [{"grade": "B", "score": 82, "fails": None}]

        output = format_table(rows, columns)

        header, separator, row = output.splitlines()
        self.assertEqual(3, len(header.split("  ")))
        self.assertEqual(3, len(separator.split("  ")))
        self.assertEqual("B         82  -    ", row)

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
            self.assertEqual("30.0", rows[0]["tiny_region_pct_lt_100"])
            self.assertEqual("285", rows[0]["median_region_area"])
            self.assertEqual('{"1": 2, "unknown": 1}', rows[0]["tiny_region_by_number_lt_100"])
            self.assertEqual("10.0", rows[0]["config_hidden_label_pct"])
            self.assertEqual("20.0", rows[0]["estimated_hidden_label_pct"])

    def test_markdown_report_contains_quality_columns_and_recommendation(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            report_path = Path(temp_dir) / "report.md"

            write_markdown_report([make_report_row()], report_path)

            content = report_path.read_text(encoding="utf-8")
            self.assertIn("| Category | Level | Grade | Score |", content)
            self.assertIn("Tiny <100%", content)
            self.assertIn("Hidden alias%", content)
            self.assertIn("Estimated hidden%", content)
            self.assertIn("| Manga | 03 | D | 43 |", content)
            self.assertIn("EXCLUDE_DEMO", content)
            self.assertIn("DESIGN_FIX_LINE", content)

    def test_compact_table_keeps_terminal_output_shorter_than_full_table(self):
        compact_buffer = io.StringIO()
        full_buffer = io.StringIO()

        with contextlib.redirect_stdout(compact_buffer):
            print_table([make_report_row()])
        with contextlib.redirect_stdout(full_buffer):
            print_table([make_report_row()], wide=True)

        compact_header = compact_buffer.getvalue().splitlines()[0]
        full_header = full_buffer.getvalue().splitlines()[0]
        compact_columns = compact_header.split()
        self.assertIn("est_hidden%", compact_header)
        self.assertNotIn("hidden%", compact_columns)
        self.assertIn("config_hidden%", full_header)
        self.assertIn("est_hidden%", full_header)
        self.assertNotIn("tiny200%", compact_header)
        self.assertIn("tiny200%", full_header)
        self.assertLess(len(COMPACT_TABLE_COLUMNS), len(FULL_TABLE_COLUMNS))
        self.assertLess(len(compact_header), len(full_header))


if __name__ == "__main__":
    unittest.main()
