package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.GuestLinuxArch
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.NetBackend
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.VolumeKind
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds host-proxy endpoint settings injected into host `rungv` image commands.
 */
data class HostProxyBridgeEnv(
    val port: Int,
    val token: String,
    val noProxyEntries: List<String>,
) {
    init {
        require(port in 1..65535) { "HostProxyBridgeEnv port must be within 1..65535" }
        require(token.isNotBlank() && !token.any(Char::isWhitespace)) {
            "HostProxyBridgeEnv token must not be blank"
        }
    }

    val proxyUrl: String
        get() = "http://termvr:$token@127.0.0.1:$port"

    val noProxyCsv: String
        get() = noProxyEntries.joinToString(",")
}

/**
 * Represents one published host-to-guest port mapping parsed from `rungv: publishing` stderr lines.
 */
data class PublishedPortMapping(
    val listenHost: String,
    val hostPort: Int,
    val guestPort: Int,
    val protocol: ForwardProtocol,
)

/**
 * Exports Android root CA certificates in PEM format for guest or host `SSL_CERT_FILE` use.
 */
fun interface CaCertificateExporter {
    fun exportRootsPem(pemFile: File, emptyCertDir: File)
}

/**
 * Default exporter reading X.509 certificates from `AndroidCAStore`.
 */
object AndroidCaStoreExporter : CaCertificateExporter {
    override fun exportRootsPem(pemFile: File, emptyCertDir: File) {
        pemFile.parentFile?.mkdirs()
        if (!emptyCertDir.exists()) {
            emptyCertDir.mkdirs()
        }

        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
        val pemBuilder = StringBuilder()
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val cert = keyStore.getCertificate(alias) as? X509Certificate ?: continue
            pemBuilder.append("-----BEGIN CERTIFICATE-----\n")
            pemBuilder.append(encoder.encodeToString(cert.encoded))
            pemBuilder.append("\n-----END CERTIFICATE-----\n")
        }

        val tmpFile = File(pemFile.parentFile, "${pemFile.name}.tmp")
        tmpFile.writeText(pemBuilder.toString(), StandardCharsets.UTF_8)
        setOwnerOnlyFileMode(tmpFile)
        if (!tmpFile.renameTo(pemFile)) {
            tmpFile.copyTo(pemFile, overwrite = true)
            tmpFile.delete()
            setOwnerOnlyFileMode(pemFile)
        }
    }
}

/**
 * Holds native PTY master file descriptor and slave device path returned by `pty_jni.c`.
 */
data class NativePtyOpenResult(
    val masterFd: Int,
    val slavePath: String,
)

/**
 * Abstracts host PTY allocation for `MuxMode.RAW_PTY` and host JVM unit testing.
 */
interface PtyBackend {
    fun openPty(rows: Int, cols: Int, xpixel: Int = 0, ypixel: Int = 0): HostPtyHandle
}

/**
 * Represents an open host PTY master handle with resize and I/O operations.
 */
interface HostPtyHandle : Closeable {
    val slavePath: String
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size)
    fun resize(rows: Int, cols: Int, xpixel: Int = 0, ypixel: Int = 0, childPid: Long = -1L)
}

/**
 * Loads `libtermvr_pty.so` for `MuxMode.RAW_PTY` `posix_openpt` and `TIOCSWINSZ` ioctl calls.
 */
object NativePtyBridge : PtyBackend {
    private val libraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("termvr_pty")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    val isAvailable: Boolean
        get() = libraryLoaded

    override fun openPty(rows: Int, cols: Int, xpixel: Int, ypixel: Int): HostPtyHandle {
        check(libraryLoaded) { "libtermvr_pty.so is not loaded in this runtime" }
        val result = nativeOpenPty(rows, cols, xpixel, ypixel)
        return JniHostPtyHandle(result.masterFd, result.slavePath)
    }

    external fun nativeOpenPty(rows: Int, cols: Int, xpixel: Int, ypixel: Int): NativePtyOpenResult

    external fun nativeResizePty(
        masterFd: Int,
        rows: Int,
        cols: Int,
        xpixel: Int,
        ypixel: Int,
        childPid: Long,
    )

    external fun nativeReadFd(fd: Int, buf: ByteArray, offset: Int, length: Int): Int

    external fun nativeWriteFd(fd: Int, buf: ByteArray, offset: Int, length: Int)

    external fun nativeCloseFd(fd: Int)

    private class JniHostPtyHandle(
        private val masterFd: Int,
        override val slavePath: String,
    ) : HostPtyHandle {
        private val closed = AtomicBoolean(false)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed.get()) return -1
            return nativeReadFd(masterFd, buffer, offset, length)
        }

        override fun write(data: ByteArray, offset: Int, length: Int) {
            if (closed.get()) throw IOException("PTY handle is closed")
            nativeWriteFd(masterFd, data, offset, length)
        }

        override fun resize(rows: Int, cols: Int, xpixel: Int, ypixel: Int, childPid: Long) {
            if (!closed.get()) {
                nativeResizePty(masterFd, rows, cols, xpixel, ypixel, childPid)
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                nativeCloseFd(masterFd)
            }
        }
    }
}

/**
 * Builds validated `librungv.so` environments, `run` arguments, `restore` arguments, and port specs.
 */
class GvxCommandBuilder(
    private val filesDir: File,
    private val cacheDir: File,
    private val caExporter: CaCertificateExporter = AndroidCaStoreExporter,
) {
    val gvxHomeDir: File get() = File(filesDir, "gvx-home")
    val gvxStoreDir: File get() = File(filesDir, "gvx-store")
    val gvxTmpDir: File get() = File(cacheDir, "gvx-tmp")
    val caDir: File get() = File(cacheDir, "ca")
    val androidRootsPem: File get() = File(caDir, "android-roots.pem")
    val caEmptyDir: File get() = File(caDir, "empty-dir")

    fun workspaceDir(profileId: String): File = File(filesDir, "workspaces/$profileId")

    fun agentDir(guestArch: GuestLinuxArch): File = File(filesDir, "agent/${guestArch.ociArch}")

    fun controlSocketFile(profileId: String): File = File(filesDir, "ctl/$profileId.sock")

    fun forwardSocketFile(profileId: String, socketName: String): File =
        File(filesDir, "fwd/$profileId/$socketName.sock")

    fun wireGuardConfigFile(configName: String): File = File(filesDir, "wg/$configName.conf")

    fun ensureHostDirectories(profile: SandboxProfile? = null, clearScratchTmp: Boolean = false) {
        gvxHomeDir.mkdirs()
        gvxStoreDir.mkdirs()
        if (clearScratchTmp && gvxTmpDir.exists()) {
            gvxTmpDir.deleteRecursively()
        }
        gvxTmpDir.mkdirs()
        File(filesDir, "ctl").apply {
            if (!exists()) mkdirs()
            setOwnerOnlyDirMode(this)
        }
        if (profile != null) {
            workspaceDir(profile.id).mkdirs()
            if (profile.portForwards.any { it.enabled && it.protocol == ForwardProtocol.UNIX_STREAM }) {
                File(filesDir, "fwd/${profile.id}").apply {
                    if (!exists()) mkdirs()
                    setOwnerOnlyDirMode(this)
                }
            }
        }
    }

    /**
     * Populates the five Section 15.1 host environment contracts for `librungv.so` execution.
     */
    fun buildHostEnvironment(
        trustHostCaCerts: Boolean,
        hostProxyBridge: HostProxyBridgeEnv? = null,
        includeProxyVars: Boolean = false,
        baseEnv: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>(baseEnv)
        env["HOME"] = gvxHomeDir.absolutePath
        env["XDG_CACHE_HOME"] = gvxStoreDir.absolutePath
        env["TMPDIR"] = gvxTmpDir.absolutePath

        if (trustHostCaCerts) {
            caExporter.exportRootsPem(androidRootsPem, caEmptyDir)
            env["SSL_CERT_FILE"] = androidRootsPem.absolutePath
            env["SSL_CERT_DIR"] = caEmptyDir.absolutePath
        }

        if (includeProxyVars && hostProxyBridge != null) {
            val proxyUrl = hostProxyBridge.proxyUrl
            val noProxy = hostProxyBridge.noProxyCsv
            env["HTTP_PROXY"] = proxyUrl
            env["HTTPS_PROXY"] = proxyUrl
            env["NO_PROXY"] = noProxy
            env["http_proxy"] = proxyUrl
            env["https_proxy"] = proxyUrl
            env["no_proxy"] = noProxy
        }
        return env
    }

    /**
     * Builds the complete `rungv run` CLI argument vector matching Design 0001 Section 15.2.
     */
    fun buildRunArgs(
        profile: SandboxProfile,
        dnsPort: Int,
        controlSock: File,
        activePorts: List<PortForwardRule> = profile.portForwards,
        guestArch: GuestLinuxArch = GuestLinuxArch.ARM64,
        hostAvailableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    ): List<String> {
        require(dnsPort in 1..65535) { "dnsPort must be within 1..65535, found $dnsPort" }

        val args = ArrayList<String>(48)
        args.add("run")
        args.add("-m")
        args.add("${profile.memoryMiB}m")
        args.add("--cpus")
        args.add(formatCpuCores(SandboxProfile.clampCpuCores(profile.cpuCores, hostAvailableProcessors)))

        appendCapabilityFlags(args, profile)

        args.add("--host-dns")
        args.add("127.0.0.1:$dnsPort")
        args.add("-control-socket")
        args.add(controlSock.absolutePath)
        if (profile.trustHostCaCerts) {
            args.add("--host-ca-certs")
        }

        appendNetworkFlags(args, profile)
        appendPortForwardFlags(args, profile.id, activePorts)
        appendVolumeAndTmpfsFlags(args, profile, guestArch)
        appendRunEnvFlags(args, profile)

        when (profile.muxMode) {
            MuxMode.STDIO_MUX -> {
                args.add("--entrypoint")
                args.add(AGENT_ENTRYPOINT_PATH)
                args.add("-image")
                args.add(profile.imageRef)
                args.add("--")
                args.add("--mux-stdio")
                args.add("--proxy-listen")
                args.add(GUEST_PROXY_LISTEN_ADDR)
                if (profile.network.outboundProxy.transparentTcp) {
                    args.add("--transparent-proxy-port")
                    args.add(GUEST_TRANSPARENT_PROXY_PORT.toString())
                }
                if (profile.makeRootRshared) {
                    args.add("--make-root-rshared")
                }
                if (profile.initDaemonArgv.isNotEmpty()) {
                    args.add("--")
                    args.addAll(profile.initDaemonArgv)
                }
            }

            MuxMode.RAW_PTY -> {
                args.add("-t")
                args.add("-image")
                args.add(profile.imageRef)
                val rawCommand = if (profile.initDaemonArgv.isNotEmpty()) {
                    profile.initDaemonArgv
                } else {
                    profile.defaultShellArgv
                }
                if (rawCommand.isNotEmpty()) {
                    args.add("--")
                    args.addAll(rawCommand)
                }
            }
        }
        return args
    }

    /**
     * Builds the `rungv restore` CLI argument vector without `-image`, `--entrypoint`, or `--`.
     */
    fun buildRestoreArgs(
        profile: SandboxProfile,
        slotDir: File,
        dnsPort: Int,
        controlSock: File,
        resolvedPorts: List<PortForwardRule> = profile.portForwards,
        guestArch: GuestLinuxArch = GuestLinuxArch.ARM64,
        hostAvailableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    ): List<String> {
        require(dnsPort in 1..65535) { "dnsPort must be within 1..65535, found $dnsPort" }

        val args = ArrayList<String>(40)
        args.add("restore")
        args.add("-checkpoint-dir")
        args.add(slotDir.absolutePath)
        args.add("-control-socket")
        args.add(controlSock.absolutePath)
        args.add("--host-dns")
        args.add("127.0.0.1:$dnsPort")

        appendVolumeAndTmpfsFlags(args, profile, guestArch)
        appendNetworkFlags(args, profile)

        args.add("-m")
        args.add("${profile.memoryMiB}m")
        args.add("--cpus")
        args.add(formatCpuCores(SandboxProfile.clampCpuCores(profile.cpuCores, hostAvailableProcessors)))

        appendCapabilityFlags(args, profile)
        if (profile.trustHostCaCerts) {
            args.add("--host-ca-certs")
        }
        if (profile.muxMode == MuxMode.RAW_PTY) {
            args.add("-t")
        }
        appendPortForwardFlags(args, profile.id, resolvedPorts)
        return args
    }

    /**
     * Builds a short-lived `rungv run` helper command to browse a stopped named `ext4lite` volume.
     */
    fun buildStoppedVolumeHelperArgs(
        volumeName: String,
        helperProfileId: String,
        imageRef: String,
        dnsPort: Int,
        controlSock: File,
        guestArch: GuestLinuxArch = GuestLinuxArch.ARM64,
        readOnly: Boolean = false,
    ): List<String> {
        require(SandboxProfile.isValidProfileId(helperProfileId)) {
            "helperProfileId must match [a-z0-9-]{3,32}"
        }
        require(volumeName.isNotBlank() && !volumeName.contains(':') && !volumeName.contains(',')) {
            "Invalid volumeName: '$volumeName'"
        }
        require(dnsPort in 1..65535) { "dnsPort must be within 1..65535" }
        val mode = if (readOnly) "ro" else "rw"
        return listOf(
            "run",
            "-m",
            "256m",
            "--cpus",
            "1",
            "--privileged=false",
            "--host-dns",
            "127.0.0.1:$dnsPort",
            "-control-socket",
            controlSock.absolutePath,
            "-net=none",
            "-v",
            "${agentDir(guestArch).absolutePath}:/run/termvr-agent:ro",
            "-v",
            "$volumeName:/mnt/volume:$mode",
            "--tmpfs",
            "/tmp",
            "--tmpfs",
            "/run",
            "--entrypoint",
            AGENT_ENTRYPOINT_PATH,
            "-image",
            imageRef,
            "--",
            "--mux-stdio",
            "--proxy-listen",
            GUEST_PROXY_LISTEN_ADDR,
        )
    }

    private fun appendCapabilityFlags(args: MutableList<String>, profile: SandboxProfile) {
        if (profile.guestPrivileged) {
            args.add("--privileged=true")
        } else if (profile.network.outboundProxy.transparentTcp) {
            args.add("--privileged=false")
            args.add("--net-raw")
        } else {
            args.add("--privileged=false")
        }
    }

    private fun appendNetworkFlags(args: MutableList<String>, profile: SandboxProfile) {
        when (profile.network.backend) {
            NetBackend.NAT -> {
                args.add("-net=nat")
                if (profile.network.isolateLanEgress) {
                    args.add("-isolate-lan")
                }
            }

            NetBackend.WIREGUARD -> {
                val wgName = requireNotNull(profile.network.wireGuardConfigName) {
                    "wireGuardConfigName is required when backend is WIREGUARD"
                }
                args.add("-net=wg")
                args.add("-wg-conf")
                args.add(wireGuardConfigFile(wgName).absolutePath)
            }

            NetBackend.NONE -> {
                args.add("-net=none")
            }
        }
    }

    private fun appendPortForwardFlags(
        args: MutableList<String>,
        profileId: String,
        rules: List<PortForwardRule>,
    ) {
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.protocol != ForwardProtocol.UNIX_STREAM) {
                require(
                    !PortForwardRule.isLanDockerPortBlocked(
                        listenHost = rule.listenHost,
                        hostPortStart = rule.hostPortStart,
                        hostPortEnd = rule.hostPortEnd,
                        guestPortStart = rule.guestPortStart,
                        guestPortEnd = rule.guestPortEnd,
                    ),
                ) {
                    "Unauthenticated Docker TCP port 2375 cannot bind on LAN (${rule.listenHost.wireLiteral})"
                }
            }
            args.add("-p")
            args.add(formatPortForwardRule(profileId, rule))
        }
    }

    internal fun formatPortForwardRule(profileId: String, rule: PortForwardRule): String {
        return when (rule.protocol) {
            ForwardProtocol.TCP, ForwardProtocol.UDP -> {
                val proto = if (rule.protocol == ForwardProtocol.TCP) "tcp" else "udp"
                val host = rule.listenHost.wireLiteral
                when {
                    rule.hostPortStart == 0 && rule.guestPortStart == rule.guestPortEnd ->
                        "$host:0:${rule.guestPortStart}/$proto"

                    rule.hostPortStart == 0 && rule.guestPortStart < rule.guestPortEnd ->
                        "$host::${rule.guestPortStart}-${rule.guestPortEnd}/$proto"

                    rule.hostPortStart == rule.hostPortEnd ->
                        "$host:${rule.hostPortStart}:${rule.guestPortStart}/$proto"

                    else ->
                        "$host:${rule.hostPortStart}-${rule.hostPortEnd}:${rule.guestPortStart}-${rule.guestPortEnd}/$proto"
                }
            }

            ForwardProtocol.UNIX_STREAM -> {
                val sockName = requireNotNull(rule.unixSocketName) {
                    "unixSocketName is required for UNIX_STREAM port forward"
                }
                val guestPath = requireNotNull(rule.guestUnixPath) {
                    "guestUnixPath is required for UNIX_STREAM port forward"
                }
                "unix:${forwardSocketFile(profileId, sockName).absolutePath}:$guestPath"
            }
        }
    }

    private fun appendVolumeAndTmpfsFlags(
        args: MutableList<String>,
        profile: SandboxProfile,
        guestArch: GuestLinuxArch,
    ) {
        if (profile.volumes.none { it.guestMountPath.trimEnd('/') == "/workspace" }) {
            args.add("-v")
            args.add("${workspaceDir(profile.id).absolutePath}:/workspace:rw")
        }

        if (profile.muxMode == MuxMode.STDIO_MUX) {
            args.add("-v")
            args.add("${agentDir(guestArch).absolutePath}:/run/termvr-agent:ro")
        }

        for (volume in profile.volumes) {
            val mode = if (volume.readOnly) "ro" else "rw"
            val hostOrVolumeSource = when (volume.kind) {
                VolumeKind.NAMED_EXT4LITE -> volume.sourceName
                VolumeKind.WORKSPACE_HOSTFS -> workspaceDir(volume.sourceName).absolutePath
            }
            args.add("-v")
            args.add("$hostOrVolumeSource:${volume.guestMountPath}:$mode")
        }

        for (tmpfsPath in profile.tmpfsMounts) {
            args.add("--tmpfs")
            args.add(tmpfsPath)
        }
    }

    private fun appendRunEnvFlags(args: MutableList<String>, profile: SandboxProfile) {
        val mergedEnv = linkedMapOf<String, String>()
        mergedEnv.putAll(profile.envVars)

        if (profile.muxMode == MuxMode.STDIO_MUX) {
            val noProxyCsv = profile.network.outboundProxy.noProxyEntries.joinToString(",")
            mergedEnv["HTTP_PROXY"] = GUEST_HTTP_PROXY_URL
            mergedEnv["HTTPS_PROXY"] = GUEST_HTTP_PROXY_URL
            mergedEnv["ALL_PROXY"] = GUEST_ALL_PROXY_URL
            mergedEnv["NO_PROXY"] = noProxyCsv
            mergedEnv["http_proxy"] = GUEST_HTTP_PROXY_URL
            mergedEnv["https_proxy"] = GUEST_HTTP_PROXY_URL
            mergedEnv["all_proxy"] = GUEST_ALL_PROXY_URL
            mergedEnv["no_proxy"] = noProxyCsv
        }

        for ((key, value) in mergedEnv.toSortedMap()) {
            args.add("-e")
            args.add("$key=$value")
        }
    }

    companion object {
        const val AGENT_ENTRYPOINT_PATH: String = "/run/termvr-agent/termvr-agent"
        const val GUEST_PROXY_LISTEN_ADDR: String = "127.0.0.1:1080"
        const val GUEST_HTTP_PROXY_URL: String = "http://127.0.0.1:1080"
        const val GUEST_ALL_PROXY_URL: String = "socks5h://127.0.0.1:1080"
        const val GUEST_TRANSPARENT_PROXY_PORT: Int = 1081

        private val PUBLISHING_LINE_REGEX = Regex(
            """^\s*(?:rungv:\s+)?publishing\s+(\[[0-9a-fA-F:]+]|[0-9.]+):(\d+)(?:\s*->\s*|:)(?:\[[0-9a-fA-F:]+]:|[0-9.]+:)?(\d+)/(tcp|udp)\s*$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Parses `rungv: publishing <host>:<hostPort>-><guestPort>/<proto>` stderr lines.
         */
        fun parsePublishingLine(line: String): PublishedPortMapping? {
            val match = PUBLISHING_LINE_REGEX.matchEntire(line) ?: return null
            val host = match.groupValues[1]
            val hostPort = match.groupValues[2].toIntOrNull() ?: return null
            val guestPort = match.groupValues[3].toIntOrNull() ?: return null
            if (hostPort !in 1..65535 || guestPort !in 1..65535) return null
            val proto = when (match.groupValues[4].lowercase()) {
                "tcp" -> ForwardProtocol.TCP
                "udp" -> ForwardProtocol.UDP
                else -> return null
            }
            return PublishedPortMapping(
                listenHost = host,
                hostPort = hostPort,
                guestPort = guestPort,
                protocol = proto,
            )
        }

        /**
         * Applies discovered ephemeral host ports onto matching `PortForwardRule` entries for restore.
         */
        fun resolvePortsForRestore(
            rules: List<PortForwardRule>,
            published: List<PublishedPortMapping>,
        ): List<PortForwardRule> {
            return rules.map { rule ->
                if (!rule.enabled || rule.protocol == ForwardProtocol.UNIX_STREAM || rule.hostPortStart != 0) {
                    return@map rule
                }
                if (rule.guestPortStart == rule.guestPortEnd) {
                    val found = published.firstOrNull {
                        it.protocol == rule.protocol &&
                            it.guestPort == rule.guestPortStart &&
                            it.listenHost == rule.listenHost.wireLiteral
                    } ?: published.firstOrNull {
                        it.protocol == rule.protocol && it.guestPort == rule.guestPortStart
                    }
                    if (found != null) {
                        return@map rule.copy(hostPortStart = found.hostPort, hostPortEnd = found.hostPort)
                    }
                }
                rule
            }
        }

        internal fun formatCpuCores(cpus: Double): String {
            val roundedInt = cpus.toInt()
            return if (cpus == roundedInt.toDouble()) {
                roundedInt.toString()
            } else {
                cpus.toString()
            }
        }
    }
}

internal fun setOwnerOnlyFileMode(file: File) {
    try {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    } catch (_: UnsupportedOperationException) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }
}

internal fun setOwnerOnlyDirMode(dir: File) {
    try {
        Files.setPosixFilePermissions(
            dir.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    } catch (_: UnsupportedOperationException) {
        dir.setReadable(false, false)
        dir.setReadable(true, true)
        dir.setWritable(false, false)
        dir.setWritable(true, true)
        dir.setExecutable(false, false)
        dir.setExecutable(true, true)
    }
}
