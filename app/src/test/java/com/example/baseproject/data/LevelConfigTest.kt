package com.example.baseproject.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelConfigTest {
    private val gson = Gson()

    @Test
    fun schemaV2ConfigBuildsLegacyRegionPaletteEntries() {
        val json = """
            {
              "schema_version": 2,
              "id": "001",
              "name": "Fixture",
              "category": "Test",
              "width": 16,
              "height": 16,
              "assets": {
                "line": "line.png",
                "mask": "mask.png",
                "preview": "preview_colored.png"
              },
              "palette": [
                {
                  "number": 1,
                  "target_color": "#ff0000",
                  "region_count": 1,
                  "total_area": 49
                },
                {
                  "number": 2,
                  "target_color": "#0000ff",
                  "region_count": 1,
                  "total_area": 42
                }
              ],
              "regions": [
                {
                  "id": 1,
                  "mask_color": "#000001",
                  "number": 1,
                  "target_color": "#ff0000",
                  "area": 49,
                  "bbox": { "left": 1, "top": 1, "right": 7, "bottom": 7 },
                  "centroid": { "x": 4.0, "y": 4.0 },
                  "label_anchor": { "x": 4.0, "y": 4.0 },
                  "hide_number": false,
                  "quality": {
                    "is_tiny": false,
                    "touchable": true
                  }
                },
                {
                  "id": 2,
                  "mask_color": "#000002",
                  "number": 2,
                  "target_color": "#0000ff",
                  "area": 42,
                  "bbox": { "left": 9, "top": 1, "right": 14, "bottom": 7 },
                  "centroid": { "x": 11.5, "y": 4.0 },
                  "label_anchor": { "x": 11.5, "y": 4.0 },
                  "hide_number": false,
                  "quality": {
                    "is_tiny": false,
                    "touchable": true
                  }
                }
              ],
              "stats": {
                "total_regions": 2,
                "unique_numbers": 2,
                "estimated_difficulty": 1,
                "small_regions_count": 0,
                "giant_regions_count": 0
              }
            }
        """.trimIndent()

        val config = gson.fromJson(json, LevelConfig::class.java)

        assertEquals(2, config.schemaVersion)
        assertEquals("line.png", config.assets?.line)
        assertEquals(2, config.stats?.totalRegions)

        val regionPalette = config.toRegionPaletteItems()
        assertEquals(2, regionPalette.size)
        assertEquals("#000001", regionPalette[0].maskColorHex)
        assertEquals("#ff0000", regionPalette[0].targetColorHex)
        assertEquals(1, regionPalette[0].number)
    }

    @Test
    fun legacyConfigKeepsPaletteAsRegionEntries() {
        val json = """
            {
              "id": "old",
              "name": "Old Fixture",
              "category": "Legacy",
              "width": 8,
              "height": 8,
              "palette": [
                {
                  "number": 1,
                  "mask_color": "#000001",
                  "target_color": "#ff0000"
                }
              ]
            }
        """.trimIndent()

        val config = gson.fromJson(json, LevelConfig::class.java)
        val regionPalette = config.toRegionPaletteItems()

        assertEquals(null, config.schemaVersion)
        assertEquals(1, regionPalette.size)
        assertEquals("#000001", regionPalette.first().maskColorHex)
    }

    @Test
    fun maskRegionHitTesterMapsCoordinatesToMaskColor() {
        val pixels = intArrayOf(
            0x000001, 0x000001, 0x000002,
            0x000001, 0x000002, 0x000002
        )

        assertEquals(0x000001, MaskRegionHitTester.maskColorAt(pixels, 3, 2, 1, 0))
        assertEquals(0x000002, MaskRegionHitTester.maskColorAt(pixels, 3, 2, 1, 1))
        assertEquals(0x000002, MaskRegionHitTester.maskColorAt(pixels, 3, 2, 2, 0))
        assertEquals(null, MaskRegionHitTester.maskColorAt(pixels, 3, 2, -1, 0))

        assertTrue(MaskRegionHitTester.isTouchableMaskColor(0x000001, setOf(0x000001), emptySet()))
        assertFalse(MaskRegionHitTester.isTouchableMaskColor(0x000001, setOf(0x000002), emptySet()))
        assertFalse(
            MaskRegionHitTester.isTouchableMaskColor(
                0x000001,
                setOf(0x000001),
                setOf(0x000001)
            )
        )
    }
}
