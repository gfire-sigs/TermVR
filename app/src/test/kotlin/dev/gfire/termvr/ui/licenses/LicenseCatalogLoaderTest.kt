package dev.gfire.termvr.ui.licenses

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LicenseCatalogLoaderTest {

    @Test
    fun `index json entries reference existing non-empty verbatim license files`() {
        val assetsRoot = resolveAssetsRoot()
        val loader = LicenseCatalogLoader { relativePath ->
            val target = File(assetsRoot, relativePath)
            require(target.isFile) { "Missing license asset file: ${target.absolutePath}" }
            target.inputStream()
        }

        val entries = loader.loadEntries()
        assertEquals(8, entries.size)
        assertEquals(entries.map { it.id }.toSet().size, entries.size, "Duplicate license entry IDs")

        val expectedIds = setOf(
            "termvr-mono-nf",
            "gvx",
            "ghostty-vt",
            "freetype",
            "harfbuzz",
            "go-stdlib",
            "androidx-compose",
            "kotlin-kotlinx",
        )
        assertEquals(expectedIds, entries.map { it.id }.toSet())

        for (entry in entries) {
            assertFalse(entry.id.isBlank(), "Blank id for $entry")
            assertFalse(entry.name.isBlank(), "Blank name for ${entry.id}")
            assertFalse(entry.version.isBlank(), "Blank version for ${entry.id}")
            assertFalse(entry.spdxId.isBlank(), "Blank spdxId for ${entry.id}")
            assertFalse(entry.repositoryUrl.isBlank(), "Blank repositoryUrl for ${entry.id}")
            assertTrue(
                entry.repositoryUrl.startsWith("https://"),
                "Expected https:// repositoryUrl for ${entry.id}: ${entry.repositoryUrl}",
            )
            assertFalse(entry.assetFile.isBlank(), "Blank assetFile for ${entry.id}")

            val licenseAsset = File(File(assetsRoot, LicenseCatalogLoader.LICENSES_ASSET_DIR), entry.assetFile)
            assertTrue(licenseAsset.isFile, "Missing license file: ${licenseAsset.absolutePath}")

            val text = loader.loadLicenseText(entry)
            assertTrue(
                text.length >= 500,
                "Expected >= 500 chars for ${entry.id} (${entry.assetFile}), got ${text.length}",
            )
        }
    }

    private fun resolveAssetsRoot(): File {
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
        )
        return candidates.firstOrNull { File(it, LicenseCatalogLoader.INDEX_ASSET_PATH).isFile }
            ?: error("Could not locate src/main/assets/${LicenseCatalogLoader.INDEX_ASSET_PATH} from ${File(".").absolutePath}")
    }
}
