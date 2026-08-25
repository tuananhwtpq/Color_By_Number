import csv
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from tools.export_asset_review_report import (
    ACTION_VI,
    COMPACT_FIELDS,
    DECISION_VI,
    ISSUE_TAG_VI,
    classify_compact_issue,
    classify_for_designer,
    safe_file_name,
    sort_rows_for_review,
    to_vietnamese,
    write_per_category,
)


def make_row(category, level, priority="P3", decision="keep", score=80):
    return {
        "category": category,
        "level": level,
        "level_key": f"{category}/{level}",
        "path": "",
        "priority": priority,
        "designer_action": "Keep",
        "decision": decision,
        "issue_tags": "ok",
        "grade": "A",
        "score": score,
        "final_region_count": 200,
        "largest_region_pct": 8.5,
        "top_2_region_pct": 12.25,
        "region_count_drop_pct": 40.0,
        "hidden_label_pct": 10.0,
    }


def every_classifier_output():
    """Chạy 2 hàm phân loại qua đủ tổ hợp metric để lấy TẤT CẢ chuỗi tiếng Anh chúng sinh ra.

    Liệt kê tay thì thêm nhánh mới là sót; quét thế này thì bản dịch thiếu là test đỏ ngay.
    """
    decisions, tags, actions = set(), set(), set()
    for overmerge in ("high", "medium", "low", ""):
        for largest in (5.0, 20.0, 30.0, 52.0, 60.0):
            for legitimacy in ("intentional_flat_background", "needs_review", ""):
                for hidden in (10.0, 60.0, 85.0, 95.0):
                    for tiny100 in (5.0, 50.0, 65.0):
                        for similarity in (80.0, 99.0):
                            for regions in (50.0, 200.0, 1200.0):
                                for grade in ("A", "B", "D"):
                                    row = {
                                        "overmerge_risk": overmerge,
                                        "largest_region_pct": largest,
                                        "giant_region_legitimacy": legitimacy,
                                        "estimated_hidden_label_pct": hidden,
                                        "hidden_label_pct": hidden,
                                        "tiny_region_pct_lt_100": tiny100,
                                        "tiny_region_pct_lt_200": tiny100,
                                        "preview_similarity_score": similarity,
                                        "regions": regions,
                                        "final_region_count": regions,
                                        "top_2_region_pct": largest * 1.6,
                                        "region_count_drop_pct": 90.0,
                                        "untouchable_region_count": 3.0,
                                        "detail_dependency_score": 20.0,
                                        "giant_region_reference_color_std": "[1, 2, 3]",
                                        "grade": grade,
                                        "fail_reasons": "UNTOUCHABLE_REGIONS",
                                        "warnings": "",
                                        "line_display_degraded": "Y",
                                        "segmentation_line_aggressive": "Y",
                                        "ink_recovery_overreach": "Y",
                                        "display_line_fallback_to_source": True,
                                        "display_line_stroke_guard_failed": True,
                                        "source_line_changed_pct": 2.0,
                                        "small_island_color_drift_count": 1,
                                        "merged_protected_detail_count": 1,
                                    }
                                    designer = classify_for_designer(row)
                                    compact = classify_compact_issue(row)
                                    actions.add(designer["designer_action"])
                                    decisions.add(compact["decision"])
                                    tags.update(compact["issue_tags"].split(";"))
    return decisions, tags, actions


class TranslationTest(unittest.TestCase):
    def test_moi_chuoi_phan_loai_deu_co_ban_dich(self):
        decisions, tags, actions = every_classifier_output()

        self.assertFalse(decisions - set(DECISION_VI), "thiếu bản dịch decision")
        self.assertFalse(tags - set(ISSUE_TAG_VI), "thiếu bản dịch issue_tag")
        self.assertFalse(actions - set(ACTION_VI), "thiếu bản dịch designer_action")

    def test_ban_dich_khong_con_sot_tieng_anh(self):
        for mapping in (DECISION_VI, ISSUE_TAG_VI, ACTION_VI):
            for key, value in mapping.items():
                self.assertNotEqual(key, value, f"'{key}' chưa được dịch")

    def test_ma_la_thi_hien_nguyen_van_chu_khong_mat_chu(self):
        self.assertEqual(to_vietnamese(DECISION_VI, "ma_moi_chua_dich"), "ma_moi_chua_dich")
        self.assertEqual(to_vietnamese(DECISION_VI, ""), "")


class CompactIssueClassificationTest(unittest.TestCase):
    def test_flat_background_large_region_is_not_a_bad_large_region_tag(self):
        row = {
            "largest_region_pct": 82.0,
            "top_2_region_pct": 90.0,
            "final_region_count": 180,
            "region_count_drop_pct": 20.0,
            "preview_similarity_score": 98.0,
            "hidden_label_pct": 5.0,
            "tiny_region_pct_lt_100": 2.0,
            "overmerge_risk": "low",
            "giant_region_legitimacy": "intentional_flat_background",
        }

        result = classify_compact_issue(row)
        tags = set(result["issue_tags"].split(";"))

        self.assertEqual("keep", result["decision"])
        self.assertIn("flat_background_possible", tags)
        self.assertIn("large_background_ok", tags)
        self.assertNotIn("large_regions", tags)

    def test_line_display_degraded_is_designer_review_ux_issue(self):
        result = classify_compact_issue(
            {
                "largest_region_pct": 8.0,
                "top_2_region_pct": 12.0,
                "final_region_count": 180,
                "preview_similarity_score": 98.0,
                "line_display_degraded": "Y",
            }
        )

        self.assertEqual("designer_review", result["decision"])
        self.assertIn("line_display_degraded", result["issue_tags"])

    def test_protected_detail_hidden_labels_do_not_tag_many_hidden_labels(self):
        result = classify_compact_issue(
            {
                "largest_region_pct": 8.0,
                "top_2_region_pct": 12.0,
                "final_region_count": 180,
                "preview_similarity_score": 98.0,
                "hidden_label_pct": 85.0,
                "estimated_hidden_label_pct": 85.0,
                "actionable_hidden_label_pct": 10.0,
                "tiny_region_pct_lt_100": 2.0,
            }
        )

        self.assertEqual("keep", result["decision"])
        self.assertNotIn("many_hidden_labels", result["issue_tags"])
        self.assertNotIn("tiny_regions", result["issue_tags"])

    def test_segmentation_aggressive_is_algorithm_or_data_review_issue(self):
        result = classify_compact_issue(
            {
                "largest_region_pct": 8.0,
                "top_2_region_pct": 12.0,
                "final_region_count": 180,
                "preview_similarity_score": 98.0,
                "segmentation_line_aggressive": True,
            }
        )

        self.assertEqual("designer_review", result["decision"])
        self.assertIn("segmentation_line_aggressive", result["issue_tags"])

    def test_thin_stroke_loss_is_display_line_review_issue(self):
        result = classify_compact_issue(
            {
                "largest_region_pct": 8.0,
                "top_2_region_pct": 12.0,
                "final_region_count": 180,
                "preview_similarity_score": 98.0,
                "display_line_stroke_guard_failed": True,
            }
        )

        self.assertEqual("designer_review", result["decision"])
        self.assertIn("thin_stroke_loss", result["issue_tags"])

    def test_small_island_drift_is_algorithm_review_issue(self):
        result = classify_compact_issue(
            {
                "largest_region_pct": 8.0,
                "top_2_region_pct": 12.0,
                "final_region_count": 180,
                "preview_similarity_score": 98.0,
                "small_island_color_drift_count": 2,
                "merged_protected_detail_count": 1,
            }
        )

        self.assertEqual("designer_review", result["decision"])
        self.assertIn("small_island_color_drift", result["issue_tags"])
        self.assertIn("protected_detail_merged", result["issue_tags"])

    def test_debug_source_mismatch_is_serious_import_data_bug(self):
        result = classify_compact_issue(
            {
                "largest_region_pct": 8.0,
                "top_2_region_pct": 12.0,
                "final_region_count": 180,
                "preview_similarity_score": 98.0,
                "source_line_changed_pct": 2.5,
            }
        )

        self.assertEqual("replace_or_redraw", result["decision"])
        self.assertIn("debug_source_line_mismatch", result["issue_tags"])

    def test_html_khong_con_ma_tieng_anh_trong_phan_nhan_xet(self):
        rows = [
            {
                "category": "Animal",
                "level": "07",
                "level_key": "Animal/07",
                "path": "",
                "priority": "P2",
                "decision": "replace_or_redraw",
                "issue_tags": "large_region_review;overmerge;too_few_regions",
                "designer_action": "Inspect debug_regions against source before accepting",
                "grade": "B",
                "score": 90,
            }
        ]
        with tempfile.TemporaryDirectory() as tmp:
            write_per_category(rows, tmp)
            html = (Path(tmp) / "Animal.html").read_text(encoding="utf-8")

        self.assertIn("Cần vẽ lại hoặc đổi ảnh", html)
        self.assertIn("Các vùng bị gộp quá nhiều", html)
        self.assertIn("Quá ít vùng, tô vài nốt là xong", html)
        self.assertIn("So bản đồ vùng với ảnh gốc trước khi duyệt.", html)
        for english in ("replace_or_redraw<", "too_few_regions<", "Inspect debug_regions"):
            self.assertNotIn(english, html)


class SafeFileNameTest(unittest.TestCase):
    def test_giu_nguyen_dau_cach_cua_category(self):
        self.assertEqual(safe_file_name("Fairy Tail"), "Fairy Tail")
        self.assertEqual(safe_file_name("Happy Easter Day"), "Happy Easter Day")

    def test_thay_ky_tu_filesystem_khong_nhan(self):
        self.assertEqual(safe_file_name("Art/Old"), "Art_Old")
        self.assertEqual(safe_file_name("A:B"), "A_B")

    def test_ten_rong_khong_lam_hong_duong_dan(self):
        self.assertEqual(safe_file_name("   "), "unknown")


class SortRowsForReviewTest(unittest.TestCase):
    def test_priority_cao_len_dau(self):
        rows = [
            make_row("Animal", "01", priority="P4"),
            make_row("Animal", "02", priority="P1"),
            make_row("Animal", "03", priority="P2"),
        ]
        order = [row["level"] for row in sort_rows_for_review(rows)]
        self.assertEqual(order, ["02", "03", "01"])

    def test_cung_priority_thi_diem_thap_truoc(self):
        rows = [
            make_row("Animal", "01", priority="P2", score=90),
            make_row("Animal", "02", priority="P2", score=40),
        ]
        order = [row["level"] for row in sort_rows_for_review(rows)]
        self.assertEqual(order, ["02", "01"])

    def test_priority_la_thi_xep_cuoi_chu_khong_vo(self):
        rows = [make_row("Animal", "01", priority=""), make_row("Animal", "02", priority="P3")]
        order = [row["level"] for row in sort_rows_for_review(rows)]
        self.assertEqual(order, ["02", "01"])


class WritePerCategoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name) / "out"

    def make_level_dir(self, category, level):
        level_dir = Path(self.tmp.name) / "assets" / category / level
        level_dir.mkdir(parents=True, exist_ok=True)
        for file_name in ("preview_colored.png", "debug_regions.png"):
            Image.new("RGB", (64, 64), (10, 20, 30)).save(level_dir / file_name)
        return str(level_dir)

    def test_moi_category_mot_cap_csv_va_html(self):
        rows = [make_row("Animal", "01"), make_row("Animal", "02"), make_row("Manga", "01")]
        written = write_per_category(rows, str(self.out))

        self.assertEqual([item[0] for item in written], ["Animal", "Manga"])
        self.assertEqual([item[1] for item in written], [2, 1])
        self.assertTrue((self.out / "Animal.csv").exists())
        self.assertTrue((self.out / "Animal.html").exists())
        self.assertTrue((self.out / "Manga.csv").exists())
        self.assertTrue((self.out / "Manga.html").exists())

    def test_ten_file_giu_dau_cach_cua_category(self):
        write_per_category([make_row("Fairy Tail", "01")], str(self.out))
        self.assertTrue((self.out / "Fairy Tail.csv").exists())
        self.assertTrue((self.out / "Fairy Tail.html").exists())

    def test_csv_co_cot_priority_va_sap_theo_uu_tien(self):
        rows = [
            make_row("Animal", "01", priority="P3"),
            make_row("Animal", "02", priority="P1"),
        ]
        write_per_category(rows, str(self.out))

        with open(self.out / "Animal.csv", newline="", encoding="utf-8") as csv_file:
            written_rows = list(csv.DictReader(csv_file))

        self.assertEqual(list(written_rows[0].keys()), COMPACT_FIELDS)
        self.assertEqual([row["level_key"] for row in written_rows], ["Animal/02", "Animal/01"])
        self.assertEqual(written_rows[0]["priority"], "P1")

    def test_html_tu_chua_anh_khong_sinh_file_roi(self):
        row = make_row("Animal", "01")
        row["path"] = self.make_level_dir("Animal", "01")
        write_per_category([row], str(self.out))

        html = (self.out / "Animal.html").read_text(encoding="utf-8")
        self.assertEqual(html.count('<img src="data:image/webp;base64,'), 2)
        self.assertFalse((self.out / "thumbs").exists())
        self.assertEqual(sorted(item.name for item in self.out.iterdir()),
                         ["Animal.csv", "Animal.html"])

    def test_ban_do_vung_co_anh_zoom_rieng_de_click_soi_ro_hon(self):
        row = make_row("Animal", "01")
        row["path"] = self.make_level_dir("Animal", "01")
        write_per_category([row], str(self.out))

        html = (self.out / "Animal.html").read_text(encoding="utf-8")
        self.assertIn('data-full-src="data:image/webp;base64,', html)
        self.assertIn("img.dataset.fullSrc || img.src", html)

    def test_khong_con_duong_dan_tuyet_doi_cua_may_sinh_file(self):
        row = make_row("Animal", "01")
        row["path"] = self.make_level_dir("Animal", "01")
        write_per_category([row], str(self.out))

        html = (self.out / "Animal.html").read_text(encoding="utf-8")
        self.assertNotIn(self.tmp.name, html)
        self.assertNotIn('href="/', html)
        self.assertIn("Animal/01/preview_colored.png", html)
        self.assertIn("Animal/01/debug_regions.png", html)

    def test_thieu_anh_thi_hien_placeholder_chu_khong_vo_trang(self):
        row = make_row("Animal", "01")
        row["path"] = str(Path(self.tmp.name) / "khong-ton-tai")
        write_per_category([row], str(self.out))

        html = (self.out / "Animal.html").read_text(encoding="utf-8")
        self.assertIn("thiếu preview_colored.png", html)
        self.assertIn("thiếu debug_regions.png", html)
        self.assertNotIn('<img src="data:', html)


if __name__ == "__main__":
    unittest.main()
