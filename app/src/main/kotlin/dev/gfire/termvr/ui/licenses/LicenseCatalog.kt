package dev.gfire.termvr.ui.licenses

import android.content.res.AssetManager
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Metadata for a third-party open-source dependency bundled in TermVR.
 */
@Serializable
data class ThirdPartyLicenseEntry(
    val id: String,
    val name: String,
    val version: String,
    val spdxId: String,
    val repositoryUrl: String,
    val assetFile: String,
)

/**
 * Loads the third-party license catalog index and verbatim license texts from assets.
 */
class LicenseCatalogLoader(
    private val openAsset: (String) -> InputStream,
) {
    constructor(assetManager: AssetManager) : this({ path -> assetManager.open(path) })

    /**
     * Parses `licenses/index.json` into an ordered list of license entries.
     */
    fun loadEntries(): List<ThirdPartyLicenseEntry> {
        val rawJson = openAsset(INDEX_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return json.decodeFromString<List<ThirdPartyLicenseEntry>>(rawJson)
    }

    /**
     * Reads the full verbatim license text for [entry].
     */
    fun loadLicenseText(entry: ThirdPartyLicenseEntry): String {
        val assetPath = "$LICENSES_ASSET_DIR/${entry.assetFile}"
        return openAsset(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val LICENSES_ASSET_DIR: String = "licenses"
        const val INDEX_ASSET_PATH: String = "$LICENSES_ASSET_DIR/index.json"

        private val json: Json = Json {
            ignoreUnknownKeys = true
        }
    }
}
