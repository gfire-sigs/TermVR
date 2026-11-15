package dev.gfire.termvr.core.data

import android.content.Context
import android.content.res.AssetManager
import dev.gfire.termvr.core.model.DistributionCatalogEntry
import dev.gfire.termvr.core.model.GuestLinuxArch
import dev.gfire.termvr.core.model.RecipeManifest
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Abstracts asset reading so bundled recipes can be tested on host JVM without Android AssetManager.
 */
fun interface AssetSource {
    fun open(assetPath: String): InputStream
}

class AndroidAssetSource(private val assets: AssetManager) : AssetSource {
    override fun open(assetPath: String): InputStream = assets.open(assetPath)
}

/**
 * Abstracts `rungv` subprocess invocation for repository unit tests and runtime injection.
 */
fun interface RungvCommandExecutor {
    suspend fun execute(
        args: List<String>,
        env: Map<String, String>,
    ): RungvCommandResult
}

data class RungvCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

@Serializable
data class RungvSystemDfEntry(
    @SerialName("Type") val type: String = "",
    @SerialName("TotalCount") val totalCount: Int = 0,
    @SerialName("Active") val active: Int = 0,
    @SerialName("Size") val size: String = "",
    @SerialName("Reclaimable") val reclaimable: String = "",
)

@Serializable
data class RungvVolumeLsEntry(
    @SerialName("Name") val name: String = "",
    @SerialName("Driver") val driver: String = "local",
    @SerialName("Mountpoint") val mountpoint: String = "",
    @SerialName("SizeBytes") val sizeBytes: Long = 0L,
    @SerialName("Labels") val labels: JsonObject? = null,
)

@Serializable
data class RungvVolumeInspectEntry(
    @SerialName("Name") val name: String = "",
    @SerialName("Driver") val driver: String = "local",
    @SerialName("Mountpoint") val mountpoint: String = "",
    @SerialName("CreatedAt") val createdAt: String = "",
    @SerialName("Labels") val labels: JsonObject? = null,
    @SerialName("Options") val options: JsonObject? = null,
)

@Serializable
data class DockerAuthEntry(
    val auth: String,
)

@Serializable
data class DockerConfigJson(
    val auths: Map<String, DockerAuthEntry>,
)

data class RegistryAuthResult(
    val registryHost: String,
    val statusCode: Int,
    val configFile: File,
)

/**
 * Provides host-ISA distribution catalogs, recipe manifests, OCI registry auth, and `rungv` parsers.
 */
class CatalogRepository(
    private val filesDir: File,
    private val assetSource: AssetSource,
    private val commandExecutor: RungvCommandExecutor = RungvCommandExecutor { _, _ ->
        throw UnsupportedOperationException("No RungvCommandExecutor configured")
    },
    private val hostAbiProvider: () -> String = {
        android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Returns curated distributions filtered strictly to the device host Linux architecture.
     */
    fun getCuratedDistributions(abiOverride: String? = null): List<DistributionCatalogEntry> {
        val abi = abiOverride ?: hostAbiProvider()
        val arch = GuestLinuxArch.fromAndroidAbi(abi) ?: return emptyList()
        return DistributionCatalogEntry.forArchitecture(arch)
    }

    fun loadRecipes(): List<RecipeManifest> =
        BUNDLED_RECIPE_PATHS.map { path ->
            assetSource.open(path).use { stream ->
                val raw = stream.readBytes().toString(StandardCharsets.UTF_8)
                json.decodeFromString<RecipeManifest>(raw)
            }
        }

    fun loadRecipe(recipeId: String): RecipeManifest? =
        loadRecipes().firstOrNull { it.id == recipeId }

    fun loadRecipeAssetText(assetPath: String): String {
        require(!assetPath.contains("..") && assetPath.startsWith("recipes/")) {
            "Recipe asset path must remain inside recipes/: $assetPath"
        }
        return assetSource.open(assetPath).use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    /**
     * Rewrites cluster server URLs in exported kubeconfig YAML to `https://127.0.0.1:6443`.
     */
    fun rewriteKubeconfigEndpoint(
        rawKubeconfigYaml: String,
        targetServerUrl: String = DEFAULT_K3S_LOOPBACK_SERVER,
    ): String {
        require(targetServerUrl.startsWith("https://")) {
            "Kubeconfig server URL must use https://"
        }
        val serverLineRegex = Regex("""^(\s*server:\s*)https?://\S+(.*)$""", RegexOption.MULTILINE)
        return serverLineRegex.replace(rawKubeconfigYaml) { match ->
            "${match.groupValues[1]}$targetServerUrl${match.groupValues[2]}"
        }
    }

    /**
     * Authenticates against an OCI v2 registry over TLS 1.3 and writes `$HOME/.docker/config.json` (0600).
     */
    suspend fun authenticateOciRegistry(
        registryHost: String,
        username: String,
        password: CharArray,
        loopbackProxyPort: Int? = null,
        loopbackProxyToken: String? = null,
        scheme: String = "https",
        allowInsecureTestScheme: Boolean = false,
    ): RegistryAuthResult = withContext(Dispatchers.IO) {
        val normalizedHost = normalizeRegistryHost(registryHost)
        require(username.isNotBlank() && !username.contains(':')) {
            "Registry username must be non-blank without ':'"
        }
        require(scheme == "https" || (allowInsecureTestScheme && scheme == "http")) {
            "OCI registry authentication requires HTTPS"
        }

        val proxy = if (loopbackProxyPort != null) {
            require(loopbackProxyPort in 1..65535) {
                "Loopback proxy port must be within 1..65535"
            }
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", loopbackProxyPort))
        } else {
            Proxy.NO_PROXY
        }

        val credentials = "$username:${String(password)}"
        val encodedAuth = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))

        val url = URL("$scheme://$normalizedHost/v2/")
        val conn = (url.openConnection(proxy) as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Basic $encodedAuth")
            setRequestProperty("Accept", "application/json")
            if (loopbackProxyToken != null) {
                val proxyCreds = Base64.getEncoder().encodeToString(
                    "termvr:$loopbackProxyToken".toByteArray(StandardCharsets.UTF_8),
                )
                setRequestProperty("Proxy-Authorization", "Basic $proxyCreds")
            }
            if (this is HttpsURLConnection) {
                sslSocketFactory = Tls13SocketFactory()
            }
        }

        val statusCode = try {
            conn.connect()
            conn.responseCode
        } finally {
            conn.disconnect()
        }

        require(statusCode in 200..299) {
            "OCI registry authentication failed for $normalizedHost with HTTP $statusCode"
        }

        val configFile = writeDockerConfigAuth(normalizedHost, encodedAuth)
        RegistryAuthResult(
            registryHost = normalizedHost,
            statusCode = statusCode,
            configFile = configFile,
        )
    }

    fun writeDockerConfigAuth(
        registryHost: String,
        base64Auth: String,
    ): File {
        val normalizedHost = normalizeRegistryHost(registryHost)
        val dockerDir = File(filesDir, "gvx-home/.docker").apply {
            if (!exists()) mkdirs()
            setOwnerOnlyDirectoryPermissions(this)
        }
        val configFile = File(dockerDir, "config.json")
        val existingAuths = if (configFile.exists()) {
            runCatching {
                json.decodeFromString<DockerConfigJson>(configFile.readText(StandardCharsets.UTF_8)).auths
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val merged = DockerConfigJson(
            auths = existingAuths + (normalizedHost to DockerAuthEntry(auth = base64Auth)),
        )
        val tmpFile = File(dockerDir, "config.json.tmp")
        tmpFile.writeText(json.encodeToString(merged) + "\n", StandardCharsets.UTF_8)
        setOwnerOnlyFilePermissions(tmpFile)
        if (!tmpFile.renameTo(configFile)) {
            tmpFile.copyTo(configFile, overwrite = true)
            tmpFile.delete()
            setOwnerOnlyFilePermissions(configFile)
        }
        return configFile
    }

    suspend fun pullImage(
        imageRef: String,
        dnsPort: Int,
        env: Map<String, String> = emptyMap(),
    ): RungvCommandResult = commandExecutor.execute(buildPullArgs(imageRef, dnsPort), env)

    suspend fun loadImageArchive(
        tarPath: String,
        env: Map<String, String> = emptyMap(),
    ): RungvCommandResult = commandExecutor.execute(buildImageLoadArgs(tarPath), env)

    suspend fun saveImageArchive(
        imageRef: String,
        outputTarPath: String,
        dnsPort: Int,
        isOnline: Boolean,
        env: Map<String, String> = emptyMap(),
    ): RungvCommandResult = commandExecutor.execute(
        buildImageSaveArgs(imageRef, outputTarPath, dnsPort, isOnline),
        env,
    )

    suspend fun systemDf(env: Map<String, String> = emptyMap()): List<RungvSystemDfEntry> {
        val result = commandExecutor.execute(buildSystemDfArgs(), env)
        check(result.exitCode == 0) { "rungv system df failed: ${result.stderr}" }
        return parseSystemDfNdjson(result.stdout)
    }

    suspend fun listVolumes(env: Map<String, String> = emptyMap()): List<RungvVolumeLsEntry> {
        val result = commandExecutor.execute(buildVolumeLsArgs(), env)
        check(result.exitCode == 0) { "rungv volume ls failed: ${result.stderr}" }
        return parseVolumeLsNdjson(result.stdout)
    }

    suspend fun inspectVolume(
        volumeName: String,
        env: Map<String, String> = emptyMap(),
    ): List<RungvVolumeInspectEntry> {
        val result = commandExecutor.execute(buildVolumeInspectArgs(volumeName), env)
        check(result.exitCode == 0) { "rungv volume inspect failed: ${result.stderr}" }
        return parseVolumeInspectJsonArray(result.stdout)
    }

    suspend fun createVolume(
        volumeName: String,
        env: Map<String, String> = emptyMap(),
    ): RungvCommandResult {
        val result = commandExecutor.execute(buildVolumeCreateArgs(volumeName), env)
        check(result.exitCode == 0) { "rungv volume create failed: ${result.stderr}" }
        return result
    }

    suspend fun removeVolume(
        volumeName: String,
        force: Boolean = false,
        env: Map<String, String> = emptyMap(),
    ): RungvCommandResult {
        val result = commandExecutor.execute(buildVolumeRmArgs(volumeName, force), env)
        check(result.exitCode == 0) { "rungv volume rm failed: ${result.stderr}" }
        return result
    }

    suspend fun systemPrune(env: Map<String, String> = emptyMap()): RungvCommandResult =
        commandExecutor.execute(buildSystemPruneArgs(), env)

    suspend fun volumePrune(env: Map<String, String> = emptyMap()): RungvCommandResult =
        commandExecutor.execute(buildVolumePruneArgs(), env)

    fun parseSystemDfNdjson(ndjson: String): List<RungvSystemDfEntry> =
        parseNdjsonLines(ndjson) { line -> json.decodeFromString<RungvSystemDfEntry>(line) }

    fun parseVolumeLsNdjson(ndjson: String): List<RungvVolumeLsEntry> =
        parseNdjsonLines(ndjson) { line -> json.decodeFromString<RungvVolumeLsEntry>(line) }

    fun parseVolumeInspectJsonArray(jsonArrayText: String): List<RungvVolumeInspectEntry> {
        val trimmed = jsonArrayText.trim()
        if (trimmed.isEmpty()) return emptyList()
        return json.decodeFromString<List<RungvVolumeInspectEntry>>(trimmed)
    }

    private fun <T> parseNdjsonLines(
        ndjson: String,
        decodeLine: (String) -> T,
    ): List<T> = ndjson.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(decodeLine)
        .toList()

    companion object {
        const val DEFAULT_K3S_LOOPBACK_SERVER = "https://127.0.0.1:6443"

        val BUNDLED_RECIPE_PATHS: List<String> = listOf(
            "recipes/docker-dind.json",
            "recipes/k3s-single-node.json",
        )

        fun create(
            context: Context,
            commandExecutor: RungvCommandExecutor = RungvCommandExecutor { _, _ ->
                throw UnsupportedOperationException("No RungvCommandExecutor configured")
            },
        ): CatalogRepository = CatalogRepository(
            filesDir = context.filesDir,
            assetSource = AndroidAssetSource(context.assets),
            commandExecutor = commandExecutor,
        )

        fun buildPullArgs(imageRef: String, dnsPort: Int): List<String> {
            require(imageRef.isNotBlank() && !imageRef.any(Char::isWhitespace)) {
                "imageRef must be a non-blank OCI reference"
            }
            require(dnsPort in 1..65535) { "dnsPort must be within 1..65535" }
            return listOf("pull", "--host-dns", "127.0.0.1:$dnsPort", imageRef)
        }

        fun buildImageLoadArgs(tarPath: String): List<String> {
            require(tarPath.isNotBlank()) { "tarPath must not be blank" }
            return listOf("image", "load", tarPath)
        }

        fun buildImageSaveArgs(
            imageRef: String,
            outputTarPath: String,
            dnsPort: Int,
            isOnline: Boolean,
        ): List<String> {
            check(isOnline) {
                "Export Image (.tar) requires an active network connection because rungv image save fetches manifests"
            }
            require(imageRef.isNotBlank() && !imageRef.any(Char::isWhitespace)) {
                "imageRef must be a non-blank OCI reference"
            }
            require(outputTarPath.isNotBlank()) { "outputTarPath must not be blank" }
            require(dnsPort in 1..65535) { "dnsPort must be within 1..65535" }
            return listOf("image", "save", "--host-dns", "127.0.0.1:$dnsPort", "-o", outputTarPath, imageRef)
        }

        fun buildSystemDfArgs(): List<String> = listOf("system", "df", "--format", "json")

        fun buildVolumeLsArgs(): List<String> = listOf("volume", "ls", "--format", "json")

        fun buildVolumeCreateArgs(volumeName: String): List<String> {
            validateVolumeName(volumeName)
            return listOf("volume", "create", volumeName)
        }

        fun buildVolumeRmArgs(volumeName: String, force: Boolean = false): List<String> {
            validateVolumeName(volumeName)
            return listOf("volume", "rm") + (if (force) listOf("-f") else emptyList()) + volumeName
        }

        fun buildVolumeInspectArgs(volumeName: String): List<String> {
            require(volumeName.isNotBlank() && !volumeName.any(Char::isWhitespace)) {
                "volumeName must not be blank"
            }
            return listOf("volume", "inspect", volumeName)
        }

        private val VOLUME_NAME_REGEX = Regex("^[a-z0-9._-]{1,64}$")

        internal fun validateVolumeName(volumeName: String) {
            require(VOLUME_NAME_REGEX.matches(volumeName) && volumeName != "." && volumeName != "..") {
                "volumeName must match [a-z0-9._-]{1,64} without '.' or '..'"
            }
        }

        fun buildSystemPruneArgs(): List<String> = listOf("system", "prune", "-f")

        fun buildVolumePruneArgs(): List<String> = listOf("volume", "prune", "-f")

        internal fun normalizeRegistryHost(rawHost: String): String {
            val trimmed = rawHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            require(trimmed.isNotBlank() && !trimmed.any(Char::isWhitespace) && !trimmed.contains('/')) {
                "Invalid OCI registry host: '$rawHost'"
            }
            return trimmed
        }
    }
}

private class Tls13SocketFactory : SSLSocketFactory() {
    private val delegate: SSLSocketFactory = SSLContext.getInstance("TLSv1.3").apply {
        init(null, null, null)
    }.socketFactory

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        enforceTls13(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String?, port: Int): Socket =
        enforceTls13(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
        enforceTls13(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: java.net.InetAddress?, port: Int): Socket =
        enforceTls13(delegate.createSocket(host, port))

    override fun createSocket(
        address: java.net.InetAddress?,
        port: Int,
        localAddress: java.net.InetAddress?,
        localPort: Int,
    ): Socket = enforceTls13(delegate.createSocket(address, port, localAddress, localPort))

    private fun enforceTls13(socket: Socket): Socket {
        if (socket is SSLSocket) {
            socket.enabledProtocols = arrayOf("TLSv1.3")
        }
        return socket
    }
}
