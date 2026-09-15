package com.pixlory.color.by.number.data.remote

import com.pixlory.color.by.number.data.LevelAssets
import com.pixlory.color.by.number.data.LevelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteLevelMapperTest {
    @Test
    fun mapsGeneratedFillCoverageFromRemoteAssetRoles() {
        val config = levelConfig()
        val detail = levelDetail(
            assets = listOf(
                RemoteLevelAssetDto(
                    role = "FILL_COVERAGE",
                    path = "/levels/owl/fill_coverage.png",
                    mimeType = "image/png",
                )
            )
        )

        val result = RemoteLevelMapper.enrichConfig(
            config = config,
            detail = detail,
            assetLoader = RemoteAssetLoader(baseUrl = "https://example.test/"),
            enableFillCoverage = true,
        )

        assertEquals(
            "https://example.test/levels/owl/fill_coverage.png",
            result.assets?.fillCoverage,
        )
    }

    @Test
    fun ignoresConfiguredFillCoverageFilenameWhenFlagIsDisabled() {
        val config = levelConfig(
            assets = LevelAssets(fillCoverage = "fill_coverage.png")
        )

        val result = RemoteLevelMapper.enrichConfig(
            config = config,
            detail = levelDetail(),
            assetLoader = RemoteAssetLoader(baseUrl = "https://example.test/"),
            enableFillCoverage = false,
        )

        assertNull(result.assets?.fillCoverage)
    }

    @Test
    fun ignoresRemoteFillCoverageRoleWhenFlagIsDisabled() {
        val result = RemoteLevelMapper.enrichConfig(
            config = levelConfig(assets = LevelAssets(fillCoverage = "fill_coverage.png")),
            detail = levelDetail(
                assets = listOf(
                    RemoteLevelAssetDto(
                        role = "FILL_COVERAGE",
                        path = "/levels/owl/fill_coverage.png",
                        mimeType = "image/png",
                    )
                )
            ),
            assetLoader = RemoteAssetLoader(baseUrl = "https://example.test/"),
            enableFillCoverage = false,
        )

        assertNull(result.assets?.fillCoverage)
    }

    @Test
    fun treatsMissingRemoteCoverageRoleAsOptionalWhenFlagIsEnabled() {
        val result = RemoteLevelMapper.enrichConfig(
            config = levelConfig(assets = LevelAssets(fillCoverage = "fill_coverage.png")),
            detail = levelDetail(),
            assetLoader = RemoteAssetLoader(baseUrl = "https://example.test/"),
            enableFillCoverage = true,
        )

        assertNull(result.assets?.fillCoverage)
    }

    private fun levelConfig(assets: LevelAssets? = null) = LevelConfig(
        id = "owl",
        name = "Owl",
        category = "animal",
        width = 4,
        height = 4,
        palette = emptyList(),
        assets = assets,
    )

    private fun levelDetail(
        assets: List<RemoteLevelAssetDto> = emptyList()
    ) = RemoteLevelDetailDto(
        id = "owl",
        groupType = "CATEGORY",
        groupId = "animal",
        assets = assets,
    )
}
