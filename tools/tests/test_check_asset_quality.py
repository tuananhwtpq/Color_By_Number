import unittest
from pathlib import Path

from tools.check_asset_quality import scan_asset_quality


PROJECT_ROOT = Path(__file__).resolve().parents[2]
ASSETS_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "assets"


class AssetQualityGateTest(unittest.TestCase):
    def test_no_built_level_fails_the_hard_quality_gate(self):
        report_paths, violations = scan_asset_quality(str(ASSETS_ROOT))

        self.assertGreater(len(report_paths), 0, "Không tìm thấy debug_report.json nào để quét.")
        self.assertEqual(
            [],
            violations,
            "Có level build sẵn không đạt hard gate (quality_grade D/F, "
            "largest_region_pct > 55%, hoặc giant_region_count > 0): "
            f"{violations}",
        )


if __name__ == "__main__":
    unittest.main()
