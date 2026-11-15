package dev.gfire.termvr.core.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import dev.gfire.termvr.core.model.FontConfig
import dev.gfire.termvr.core.model.GlobalAppSettings
import dev.gfire.termvr.core.model.KeyBarConfig
import dev.gfire.termvr.core.model.SandboxProfile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Records a persisted Storage Access Framework tree or document grant.
 */
@Serializable
data class SafGrantRecord(
    val uriString: String,
    val displayName: String,
    val profileId: String? = null,
    val canWrite: Boolean = true,
    val grantedAtEpochMillis: Long = 0L,
) {
    init {
        require(uriString.isNotBlank()) { "SafGrantRecord uriString must not be blank" }
        require(displayName.isNotBlank()) { "SafGrantRecord displayName must not be blank" }
        if (profileId != null) {
            require(SandboxProfile.isValidProfileId(profileId)) {
                "SafGrantRecord profileId must match [a-z0-9-]{3,32}"
            }
        }
    }
}

/**
 * Versioned root state persisted by Jetpack DataStore.
 */
@Serializable
data class AppPersistenceState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val profiles: List<SandboxProfile> = emptyList(),
    val keyBarConfig: KeyBarConfig = KeyBarConfig(),
    val fontConfig: FontConfig = FontConfig(),
    val appSettings: GlobalAppSettings = GlobalAppSettings(),
    val safUriGrants: List<SafGrantRecord> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported AppPersistenceState schemaVersion: $schemaVersion"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * JSON serializer for DataStore<AppPersistenceState> enforcing schemaVersion checks.
 */
object AppPersistenceSerializer : Serializer<AppPersistenceState> {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    override val defaultValue: AppPersistenceState = AppPersistenceState()

    override suspend fun readFrom(input: InputStream): AppPersistenceState {
        val raw = input.readBytes().toString(Charsets.UTF_8)
        if (raw.isBlank()) return defaultValue
        return try {
            json.decodeFromString<AppPersistenceState>(raw)
        } catch (e: SerializationException) {
            throw CorruptionException("Failed to decode TermVR persistence state", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Invalid TermVR persistence state", e)
        }
    }

    override suspend fun writeTo(t: AppPersistenceState, output: OutputStream) {
        val encoded = json.encodeToString(t)
        output.write(encoded.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Manages persisted profiles, terminal settings, SAF grants, WireGuard configs, fonts, and proxy secrets.
 */
class ProfileRepository(
    private val dataStore: DataStore<AppPersistenceState>,
    private val filesDir: File,
    private val secretStore: SecretStore,
) {
    val state: Flow<AppPersistenceState> = dataStore.data

    fun observeProfiles(): Flow<List<SandboxProfile>> = state.map { it.profiles }

    fun observeKeyBarConfig(): Flow<KeyBarConfig> = state.map { it.keyBarConfig }

    fun observeFontConfig(): Flow<FontConfig> = state.map { it.fontConfig }

    fun observeAppSettings(): Flow<GlobalAppSettings> = state.map { it.appSettings }

    fun observeSafGrants(): Flow<List<SafGrantRecord>> = state.map { it.safUriGrants }

    suspend fun getProfile(profileId: String): SandboxProfile? =
        state.first().profiles.firstOrNull { it.id == profileId }

    suspend fun upsertProfile(profile: SandboxProfile) {
        dataStore.updateData { current ->
            val updated = current.profiles
                .filterNot { it.id == profile.id } + profile
            current.copy(profiles = updated.sortedBy { it.id })
        }
    }

    suspend fun deleteProfile(profileId: String) {
        dataStore.updateData { current ->
            current.copy(
                profiles = current.profiles.filterNot { it.id == profileId },
                safUriGrants = current.safUriGrants.filterNot { it.profileId == profileId },
            )
        }
    }

    suspend fun updateKeyBarConfig(config: KeyBarConfig) {
        dataStore.updateData { it.copy(keyBarConfig = config) }
    }

    suspend fun updateFontConfig(config: FontConfig) {
        dataStore.updateData { it.copy(fontConfig = config) }
    }

    suspend fun updateAppSettings(settings: GlobalAppSettings) {
        dataStore.updateData { it.copy(appSettings = settings) }
    }

    suspend fun recordSafGrant(grant: SafGrantRecord) {
        dataStore.updateData { current ->
            val updated = current.safUriGrants.filterNot { it.uriString == grant.uriString } + grant
            current.copy(safUriGrants = updated)
        }
    }

    suspend fun removeSafGrant(uriString: String) {
        dataStore.updateData { current ->
            current.copy(safUriGrants = current.safUriGrants.filterNot { it.uriString == uriString })
        }
    }

    fun wireGuardDir(): File = File(filesDir, "wg").apply {
        if (!exists()) mkdirs()
        setOwnerOnlyDirectoryPermissions(this)
    }

    fun wireGuardConfigFile(name: String): File {
        validateWireGuardName(name)
        return File(wireGuardDir(), "$name.conf")
    }

    suspend fun saveWireGuardConfig(name: String, configText: String): File = withContext(Dispatchers.IO) {
        validateWireGuardName(name)
        require(configText.contains("[Interface]") && configText.contains("[Peer]")) {
            "WireGuard configuration must contain [Interface] and [Peer] sections"
        }
        val target = wireGuardConfigFile(name)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(configText, Charsets.UTF_8)
        setOwnerOnlyFilePermissions(tmp)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            setOwnerOnlyFilePermissions(target)
        }
        target
    }

    suspend fun readWireGuardConfig(name: String): String? = withContext(Dispatchers.IO) {
        val file = wireGuardConfigFile(name)
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    suspend fun listWireGuardConfigNames(): List<String> = withContext(Dispatchers.IO) {
        wireGuardDir()
            .listFiles { file -> file.isFile && file.extension == "conf" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    suspend fun deleteWireGuardConfig(name: String): Boolean = withContext(Dispatchers.IO) {
        wireGuardConfigFile(name).delete()
    }

    /**
     * Imports a user-selected TTF or OTF font into `<filesDir>/fonts/<sha256>.ttf` and verifies its SHA-256 digest.
     */
    suspend fun importCustomFont(
        inputStream: InputStream,
        expectedSha256: String? = null,
    ): FontConfig = withContext(Dispatchers.IO) {
        val fontsDir = File(filesDir, "fonts").apply {
            if (!exists()) mkdirs()
        }
        val tempFile = File.createTempFile("font-import-", ".tmp", fontsDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0L
            val header = ByteArray(4)
            var headerBytes = 0

            tempFile.outputStream().use { out ->
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break
                    totalBytes += read
                    require(totalBytes <= MAX_CUSTOM_FONT_BYTES) {
                        "Custom font exceeds maximum allowed size of $MAX_CUSTOM_FONT_BYTES bytes"
                    }
                    if (headerBytes < 4) {
                        val copyLen = minOf(4 - headerBytes, read)
                        System.arraycopy(buffer, 0, header, headerBytes, copyLen)
                        headerBytes += copyLen
                    }
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
            require(headerBytes == 4 && isSupportedFontMagic(header)) {
                "Unsupported font file header; expected TrueType (.ttf) or OpenType (.otf)"
            }

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null) {
                require(actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                    "Custom font SHA-256 mismatch: expected $expectedSha256, computed $actualSha256"
                }
            }

            val targetFile = File(fontsDir, "$actualSha256.ttf")
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            var updatedConfig: FontConfig? = null
            dataStore.updateData { current ->
                val nextFont = current.fontConfig.copy(
                    familyId = "custom-$actualSha256",
                    customFontPath = targetFile.absolutePath,
                    customFontSha256 = actualSha256,
                )
                updatedConfig = nextFont
                current.copy(fontConfig = nextFont)
            }
            updatedConfig!!
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    fun saveProxySecret(alias: String, secret: CharArray) {
        secretStore.putSecret(alias, secret)
    }

    fun loadProxySecret(alias: String): CharArray? = secretStore.getSecret(alias)

    fun deleteProxySecret(alias: String) {
        secretStore.deleteSecret(alias)
    }

    companion object {
        private const val STATE_FILE_NAME = "termvr_state.json"
        private const val MAX_CUSTOM_FONT_BYTES = 64L * 1024L * 1024L
        private val WG_NAME_REGEX = Regex("^[a-zA-Z0-9._-]{1,64}$")

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): ProfileRepository {
            val filesDir = context.filesDir
            val dataStore = createDataStore(filesDir, scope)
            val secretStore = AndroidKeystoreSecretStore(File(filesDir, "secrets"))
            return ProfileRepository(dataStore, filesDir, secretStore)
        }

        fun createDataStore(
            filesDir: File,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): DataStore<AppPersistenceState> = DataStoreFactory.create(
            serializer = AppPersistenceSerializer,
            scope = scope,
            produceFile = { File(filesDir, "datastore/$STATE_FILE_NAME") },
        )

        internal fun validateWireGuardName(name: String) {
            require(WG_NAME_REGEX.matches(name) && name != "." && name != "..") {
                "WireGuard config name must match [a-zA-Z0-9._-]{1,64} without '.' or '..'"
            }
        }

        private fun isSupportedFontMagic(header: ByteArray): Boolean {
            val sfntVersion =
                ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
            return sfntVersion == 0x00010000 || // TrueType
                sfntVersion == 0x4F54544F || // 'OTTO' OpenType CFF
                sfntVersion == 0x74727565 || // 'true' Apple TrueType
                sfntVersion == 0x74797031 // 'typ1'
        }
    }
}
