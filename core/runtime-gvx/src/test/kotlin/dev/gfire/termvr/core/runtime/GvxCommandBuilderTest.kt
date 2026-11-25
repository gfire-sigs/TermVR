package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.GitWorktreeSpec
import dev.gfire.termvr.core.model.GuestLinuxArch
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.NetBackend
import dev.gfire.termvr.core.model.NetworkModeConfig
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.ProxyEndpointSpec
import dev.gfire.termvr.core.model.ProxyProtocol
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.core.model.VolumeMountSpec
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GvxCommandBuilderTest {

    @TempDir
    lateinit var tempDir: File

    private fun newBuilder(exportedPemContent: String = "PEM"): GvxCommandBuilder {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val exporter = CaCertificateExporter { pemFile, emptyDir ->
            pemFile.parentFile?.mkdirs()
            pemFile.writeText(exportedPemContent)
            emptyDir.mkdirs()
        }
        return GvxCommandBuilder(filesDir = filesDir, cacheDir = cacheDir, caExporter = exporter)
    }

    @Test
    fun `buildHostEnvironment populates all 5 Section 15_1 contracts`() {
        val builder = newBuilder("-----BEGIN CERTIFICATE-----\nTEST\n-----END CERTIFICATE-----\n")
        val proxyEnv = HostProxyBridgeEnv(
            port = 43210,
            token = "0123456789abcdef0123456789abcdef",
            noProxyEntries = listOf("localhost", "127.0.0.1"),
        )

        val env = builder.buildHostEnvironment(
            trustHostCaCerts = true,
            hostProxyBridge = proxyEnv,
            includeProxyVars = true,
        )

        assertEquals(builder.gvxHomeDir.absolutePath, env["HOME"])
        assertEquals(builder.gvxStoreDir.absolutePath, env["XDG_CACHE_HOME"])
        assertEquals(builder.gvxTmpDir.absolutePath, env["TMPDIR"])
        assertEquals(builder.androidRootsPem.absolutePath, env["SSL_CERT_FILE"])
        assertEquals(builder.caEmptyDir.absolutePath, env["SSL_CERT_DIR"])
        assertTrue(builder.androidRootsPem.isFile)
        assertTrue(builder.caEmptyDir.isDirectory)
        assertEquals("http://termvr:0123456789abcdef0123456789abcdef@127.0.0.1:43210", env["HTTP_PROXY"])
        assertEquals("http://termvr:0123456789abcdef0123456789abcdef@127.0.0.1:43210", env["HTTPS_PROXY"])
        assertEquals("localhost,127.0.0.1", env["NO_PROXY"])
    }

    @Test
    fun `buildRunArgs emits exact STDIO_MUX flags, capabilities, ports, volumes, and sorted env`() {
        val builder = newBuilder()
        val profile = SandboxProfile(
            id = "k3s-dev",
            displayName = "k3s Dev",
            imageRef = "rancher/k3s:v1.32.2-k3s1",
            muxMode = MuxMode.STDIO_MUX,
            initDaemonArgv = listOf("/bin/k3s", "server", "--snapshotter=native"),
            memoryMiB = 2560,
            cpuCores = 16.0,
            guestPrivileged = true,
            makeRootRshared = true,
            trustHostCaCerts = true,
            volumes = listOf(
                VolumeMountSpec("k3s-k3s-dev", "/var/lib/rancher/k3s", VolumeKind.NAMED_EXT4LITE, readOnly = false),
                VolumeMountSpec("shared-ws", "/mnt/shared", VolumeKind.WORKSPACE_HOSTFS, readOnly = true),
            ),
            envVars = mapOf("Z_VAR" to "last", "A_VAR" to "first"),
            network = NetworkModeConfig(
                backend = NetBackend.NAT,
                isolateLanEgress = true,
                outboundProxy = OutboundProxyConfig(
                    mode = OutboundProxyMode.CUSTOM,
                    customEndpoint = ProxyEndpointSpec(ProxyProtocol.SOCKS5, "127.0.0.1", 9050),
                    transparentTcp = true,
                    noProxyEntries = listOf("localhost", ".svc"),
                ),
            ),
            portForwards = listOf(
                PortForwardRule("api", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 6443, 6443, 6443, 6443),
                PortForwardRule("ephem", ForwardProtocol.TCP, ListenHost.LOOPBACK_V6, 0, 0, 8080, 8080),
                PortForwardRule("ephem-range", ForwardProtocol.UDP, ListenHost.LOOPBACK_V4, 0, 0, 9000, 9002),
                PortForwardRule("fixed-range", ForwardProtocol.TCP, ListenHost.LAN_ALL_V4, 7000, 7002, 8000, 8002),
                PortForwardRule(
                    id = "sock",
                    protocol = ForwardProtocol.UNIX_STREAM,
                    hostPortStart = 0,
                    guestPortStart = 0,
                    unixSocketName = "docker.sock",
                    guestUnixPath = "/var/run/docker.sock",
                ),
            ),
        )

        val ctlSock = builder.controlSocketFile(profile.id)
        val args = builder.buildRunArgs(
            profile = profile,
            dnsPort = 5353,
            controlSock = ctlSock,
            guestArch = GuestLinuxArch.ARM64,
            hostAvailableProcessors = 8,
        )

        assertEquals("run", args[0])
        assertFalse(args.contains("--rootless"))
        assertTrue(args.contains("2560m"))
        assertTrue(args.contains("8")) // clamped from 16.0 to 8
        assertTrue(args.contains("--privileged=true"))
        assertFalse(args.contains("--net-raw")) // privileged=true supersedes --net-raw
        assertTrue(args.contains("-h"))
        val hostIdx = args.indexOf("-h")
        assertEquals("k3s-dev", args[hostIdx + 1])
        assertTrue(args.contains("--host-ca-certs"))
        assertTrue(args.contains("-net=nat"))
        assertTrue(args.contains("-isolate-lan"))

        assertTrue(args.contains("127.0.0.1:6443:6443/tcp"))
        assertTrue(args.contains("[::1]:0:8080/tcp"))
        assertTrue(args.contains("127.0.0.1::9000-9002/udp"))
        assertTrue(args.contains("0.0.0.0:7000-7002:8000-8002/tcp"))
        assertTrue(args.contains("unix:${builder.forwardSocketFile("k3s-dev", "docker.sock").absolutePath}:/var/run/docker.sock"))

        assertTrue(args.contains("${builder.workspaceDir("k3s-dev").absolutePath}:/workspace:rw"))
        assertTrue(args.contains("${builder.agentDir(GuestLinuxArch.ARM64).absolutePath}:/run/termvr-agent:ro"))
        assertTrue(args.contains("k3s-k3s-dev:/var/lib/rancher/k3s:rw"))
        assertTrue(args.contains("${builder.workspaceDir("shared-ws").absolutePath}:/mnt/shared:ro"))

        assertTrue(args.contains("HTTP_PROXY=http://127.0.0.1:1080"))
        assertTrue(args.contains("ALL_PROXY=socks5h://127.0.0.1:1080"))
        assertTrue(args.contains("NO_PROXY=localhost,.svc"))
        assertTrue(args.indexOf("A_VAR=first") < args.indexOf("Z_VAR=last"))

        val entryIdx = args.indexOf("--entrypoint")
        assertTrue(entryIdx > 0)
        assertEquals(GvxCommandBuilder.AGENT_ENTRYPOINT_PATH, args[entryIdx + 1])
        assertTrue(args.contains("--mux-stdio"))
        assertTrue(args.contains("--transparent-proxy-port"))
        assertTrue(args.contains("1081"))
        assertTrue(args.contains("--make-root-rshared"))
        assertEquals(listOf("--", "/bin/k3s", "server", "--snapshotter=native"), args.takeLast(4))
    }

    @Test
    fun `buildRunArgs sets --privileged=false --net-raw when non-privileged transparentTcp is enabled`() {
        val builder = newBuilder()
        val profile = SandboxProfile(
            id = "alpine-net",
            displayName = "Alpine",
            imageRef = "docker.io/library/alpine:3.21",
            guestPrivileged = false,
            network = NetworkModeConfig(
                backend = NetBackend.WIREGUARD,
                wireGuardConfigName = "corp",
                outboundProxy = OutboundProxyConfig(transparentTcp = true),
            ),
        )
        val args = builder.buildRunArgs(profile, 5300, builder.controlSocketFile(profile.id))
        assertTrue(args.contains("--privileged=false"))
        assertTrue(args.contains("--net-raw"))
        assertTrue(args.contains("-net=wg"))
        assertTrue(args.contains("-wg-conf"))
        assertTrue(args.contains(builder.wireGuardConfigFile("corp").absolutePath))
    }

    @Test
    fun `buildRestoreArgs omits image, entrypoint, env, and double-dash while preserving restore contract`() {
        val builder = newBuilder()
        val profile = SandboxProfile(
            id = "debian-box",
            displayName = "Debian",
            imageRef = "docker.io/library/debian:trixie-slim",
            muxMode = MuxMode.STDIO_MUX,
            initDaemonArgv = listOf("/bin/bash"),
            envVars = mapOf("FOO" to "bar"),
            network = NetworkModeConfig(backend = NetBackend.NONE),
            portForwards = listOf(
                PortForwardRule("web", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 0, 0, 8080, 8080),
            ),
        )
        val slotDir = File(tempDir, "files/checkpoints/debian-box/slot-a")
        val resolved = GvxCommandBuilder.resolvePortsForRestore(
            rules = profile.portForwards,
            published = listOf(PublishedPortMapping("127.0.0.1", 49152, 8080, ForwardProtocol.TCP)),
        )

        val restoreArgs = builder.buildRestoreArgs(
            profile = profile,
            slotDir = slotDir,
            dnsPort = 5354,
            controlSock = builder.controlSocketFile(profile.id),
            resolvedPorts = resolved,
        )

        assertEquals("restore", restoreArgs[0])
        assertTrue(restoreArgs.contains("-checkpoint-dir"))
        assertTrue(restoreArgs.contains(slotDir.absolutePath))
        assertTrue(restoreArgs.contains("-net=none"))
        assertTrue(restoreArgs.contains("127.0.0.1:49152:8080/tcp"))
        assertFalse(restoreArgs.contains("-image"))
        assertFalse(restoreArgs.contains("--entrypoint"))
        assertFalse(restoreArgs.contains("-e"))
        assertFalse(restoreArgs.contains("--"))
        assertFalse(restoreArgs.contains("-t"))
    }

    @Test
    fun `buildRestoreArgs includes -t for RAW_PTY mode`() {
        val builder = newBuilder()
        val profile = SandboxProfile(
            id = "raw-pty",
            displayName = "Raw",
            imageRef = "busybox:latest",
            muxMode = MuxMode.RAW_PTY,
        )
        val slotDir = File(tempDir, "slot-b")
        val restoreArgs = builder.buildRestoreArgs(profile, slotDir, 5353, builder.controlSocketFile(profile.id))
        assertTrue(restoreArgs.contains("-t"))
    }

    @Test
    fun `parsePublishingLine parses IPv4 and IPv6 ephemeral port lines`() {
        val p1 = GvxCommandBuilder.parsePublishingLine("rungvx: publishing 127.0.0.1:41234 -> 8080/tcp")
        assertNotNull(p1)
        assertEquals("127.0.0.1", p1!!.listenHost)
        assertEquals(41234, p1.hostPort)
        assertEquals(8080, p1.guestPort)
        assertEquals(ForwardProtocol.TCP, p1.protocol)

        val pLegacy = GvxCommandBuilder.parsePublishingLine("rungv: publishing 127.0.0.1:41234->8080/tcp")
        assertNotNull(pLegacy)
        assertEquals("127.0.0.1", pLegacy!!.listenHost)
        assertEquals(41234, pLegacy.hostPort)
        assertEquals(8080, pLegacy.guestPort)
        assertEquals(ForwardProtocol.TCP, pLegacy.protocol)

        val p2 = GvxCommandBuilder.parsePublishingLine("publishing [::1]:55123:9000/udp")
        assertNotNull(p2)
        assertEquals("[::1]", p2!!.listenHost)
        assertEquals(55123, p2.hostPort)
        assertEquals(9000, p2.guestPort)
        assertEquals(ForwardProtocol.UDP, p2.protocol)

        val p3 = GvxCommandBuilder.parsePublishingLine("rungvx: publishing [::1]:55124 -> 9001/udp")
        assertNotNull(p3)
        assertEquals("[::1]", p3!!.listenHost)
        assertEquals(55124, p3.hostPort)
        assertEquals(9001, p3.guestPort)
        assertEquals(ForwardProtocol.UDP, p3.protocol)

        assertNull(GvxCommandBuilder.parsePublishingLine("random stderr line"))
    }

    @Test
    fun `buildRunArgs emits single workspace mount when profile volumes already defines workspace`() {
        val builder = newBuilder()
        val profile = SandboxProfile(
            id = "quick-alpine",
            displayName = "Quick Shell (Alpine)",
            imageRef = "docker.io/library/alpine:3.21",
            volumes = listOf(
                VolumeMountSpec(
                    sourceName = "quick-alpine",
                    guestMountPath = "/workspace",
                    kind = VolumeKind.WORKSPACE_HOSTFS,
                    readOnly = false,
                ),
            ),
        )
        val args = builder.buildRunArgs(profile, 5353, builder.controlSocketFile(profile.id))
        val workspaceFlags = args.filter { it.endsWith(":/workspace:rw") || it.endsWith(":/workspace:ro") }
        assertEquals(1, workspaceFlags.size)
        assertEquals("${builder.workspaceDir("quick-alpine").absolutePath}:/workspace:rw", workspaceFlags.single())
    }

    @Test
    fun `buildRunArgs rejects invalid dnsPort`() {
        val builder = newBuilder()
        val profile = SandboxProfile(id = "bad-dns", displayName = "Bad", imageRef = "alpine:3.21")
        assertThrows(IllegalArgumentException::class.java) {
            builder.buildRunArgs(profile, 0, builder.controlSocketFile(profile.id))
        }
    }

    @Test
    fun `buildRunArgs and buildRestoreArgs omit -m and --cpus when unlimited`() {
        val builder = newBuilder()
        val unlimited = SandboxProfile(
            id = "debian-unlim",
            displayName = "Debian Unlimited",
            imageRef = "docker.io/library/debian:trixie-slim",
        )
        val runArgs = builder.buildRunArgs(unlimited, 5353, builder.controlSocketFile(unlimited.id))
        assertFalse(runArgs.contains("-m"))
        assertFalse(runArgs.contains("--cpus"))

        val restoreArgs = builder.buildRestoreArgs(
            profile = unlimited,
            slotDir = File(tempDir, "slot-unlim"),
            dnsPort = 5353,
            controlSock = builder.controlSocketFile(unlimited.id),
        )
        assertFalse(restoreArgs.contains("-m"))
        assertFalse(restoreArgs.contains("--cpus"))

        val memOnly = unlimited.copy(memoryMiB = 1024, cpuCores = 0.0)
        val memOnlyArgs = builder.buildRunArgs(memOnly, 5353, builder.controlSocketFile(memOnly.id))
        assertTrue(memOnlyArgs.contains("-m"))
        assertTrue(memOnlyArgs.contains("1024m"))
        assertFalse(memOnlyArgs.contains("--cpus"))

        val cpuOnly = unlimited.copy(memoryMiB = 0, cpuCores = 2.0)
        val cpuOnlyArgs = builder.buildRunArgs(cpuOnly, 5353, builder.controlSocketFile(cpuOnly.id))
        assertFalse(cpuOnlyArgs.contains("-m"))
        assertTrue(cpuOnlyArgs.contains("--cpus"))
        assertTrue(cpuOnlyArgs.contains("2"))
    }

    @Test
    fun `buildRunArgs includes -checkpoint-dir before -image when checkpointDir is provided`() {
        val builder = newBuilder()
        val profile = SandboxProfile(
            id = "snap-run",
            displayName = "Snap",
            imageRef = "docker.io/library/debian:trixie-slim",
        )
        val slotDir = File(tempDir, "files/checkpoints/snap-run/slot-a")
        val argsWithDir = builder.buildRunArgs(
            profile = profile,
            dnsPort = 5353,
            controlSock = builder.controlSocketFile(profile.id),
            checkpointDir = slotDir,
        )
        val ckptIdx = argsWithDir.indexOf("-checkpoint-dir")
        val imageIdx = argsWithDir.indexOf("-image")
        assertTrue(ckptIdx > 0)
        assertEquals(slotDir.absolutePath, argsWithDir[ckptIdx + 1])
        assertTrue(ckptIdx < imageIdx)

        val argsWithoutDir = builder.buildRunArgs(
            profile = profile,
            dnsPort = 5353,
            controlSock = builder.controlSocketFile(profile.id),
        )
        assertFalse(argsWithoutDir.contains("-checkpoint-dir"))
    }

    @Test
    fun `buildRunArgs sets DOCKER_DEFAULT_PLATFORM on arm64 when DOCKER_HOST is configured`() {
        val builder = newBuilder()
        val profile = SandboxProfile(
            id = "dind-arm64",
            displayName = "DinD",
            imageRef = "docker:28-dind",
            envVars = mapOf("DOCKER_HOST" to "unix:///var/run/docker.sock"),
        )

        val arm64Args = builder.buildRunArgs(
            profile = profile,
            dnsPort = 5353,
            controlSock = builder.controlSocketFile(profile.id),
            guestArch = GuestLinuxArch.ARM64,
        )
        assertTrue(arm64Args.contains("DOCKER_DEFAULT_PLATFORM=linux/arm64"))

        val explicitOverride = profile.copy(
            envVars = profile.envVars + ("DOCKER_DEFAULT_PLATFORM" to "linux/arm64/v8"),
        )
        val overrideArgs = builder.buildRunArgs(
            profile = explicitOverride,
            dnsPort = 5353,
            controlSock = builder.controlSocketFile(explicitOverride.id),
            guestArch = GuestLinuxArch.ARM64,
        )
        assertTrue(overrideArgs.contains("DOCKER_DEFAULT_PLATFORM=linux/arm64/v8"))
        assertFalse(overrideArgs.contains("DOCKER_DEFAULT_PLATFORM=linux/arm64"))

        val amd64Args = builder.buildRunArgs(
            profile = profile,
            dnsPort = 5353,
            controlSock = builder.controlSocketFile(profile.id),
            guestArch = GuestLinuxArch.AMD64,
        )
        assertFalse(amd64Args.any { it.startsWith("DOCKER_DEFAULT_PLATFORM=") })
    }

    @Test
    fun `appendRunEnvFlags injects TERM xterm-256color COLORTERM and LANG C UTF-8 without TERM_PROGRAM unless overridden`() {
        val builder = newBuilder()
        val stdioProfile = SandboxProfile(
            id = "term-stdio",
            displayName = "Term Stdio",
            imageRef = "docker.io/library/debian:trixie-slim",
            muxMode = MuxMode.STDIO_MUX,
        )
        val stdioArgs = builder.buildRunArgs(stdioProfile, 5353, builder.controlSocketFile(stdioProfile.id))
        assertTrue(stdioArgs.contains("TERM=xterm-256color"))
        assertTrue(stdioArgs.contains("COLORTERM=truecolor"))
        assertTrue(stdioArgs.contains("LANG=C.UTF-8"))
        assertFalse(stdioArgs.any { it.startsWith("TERM_PROGRAM=") })
        assertTrue(stdioArgs.contains("HTTP_PROXY=http://127.0.0.1:1080"))

        val rawProfile = stdioProfile.copy(
            id = "term-raw",
            muxMode = MuxMode.RAW_PTY,
        )
        val rawArgs = builder.buildRunArgs(rawProfile, 5353, builder.controlSocketFile(rawProfile.id))
        assertTrue(rawArgs.contains("TERM=xterm-256color"))
        assertTrue(rawArgs.contains("COLORTERM=truecolor"))
        assertTrue(rawArgs.contains("LANG=C.UTF-8"))
        assertFalse(rawArgs.any { it.startsWith("TERM_PROGRAM=") })

        val overridden = stdioProfile.copy(
            envVars = mapOf(
                "TERM" to "vt100",
                "COLORTERM" to "24bit",
                "TERM_PROGRAM" to "CustomTerm",
                "LANG" to "en_US.UTF-8",
            ),
        )
        val overrideArgs = builder.buildRunArgs(overridden, 5353, builder.controlSocketFile(overridden.id))
        assertTrue(overrideArgs.contains("TERM=vt100"))
        assertFalse(overrideArgs.contains("TERM=xterm-256color"))
        assertTrue(overrideArgs.contains("COLORTERM=24bit"))
        assertTrue(overrideArgs.contains("TERM_PROGRAM=CustomTerm"))
        assertTrue(overrideArgs.contains("LANG=en_US.UTF-8"))
        assertFalse(overrideArgs.contains("LANG=C.UTF-8"))

        val lcAllOnly = stdioProfile.copy(
            envVars = mapOf("LC_ALL" to "C.UTF-8"),
        )
        val lcAllArgs = builder.buildRunArgs(lcAllOnly, 5353, builder.controlSocketFile(lcAllOnly.id))
        assertTrue(lcAllArgs.contains("LC_ALL=C.UTF-8"))
        assertFalse(lcAllArgs.any { it.startsWith("LANG=") })
    }

    @Test
    fun `buildRunArgs omits guest proxy env for daemon recipes under DIRECT mode`() {
        val builder = newBuilder()
        val k3sDirect = SandboxProfile(
            id = "k3s-direct",
            displayName = "k3s Direct",
            imageRef = "rancher/k3s:v1.32.2-k3s1",
            muxMode = MuxMode.STDIO_MUX,
            initDaemonArgv = listOf("/bin/k3s", "server"),
            network = NetworkModeConfig(
                outboundProxy = OutboundProxyConfig(mode = OutboundProxyMode.DIRECT),
            ),
        )
        val directArgs = builder.buildRunArgs(k3sDirect, 5353, builder.controlSocketFile(k3sDirect.id))
        assertFalse(directArgs.any { it.startsWith("HTTP_PROXY=") || it.startsWith("HTTPS_PROXY=") || it.startsWith("ALL_PROXY=") })
        assertFalse(directArgs.any { it.startsWith("http_proxy=") || it.startsWith("https_proxy=") || it.startsWith("all_proxy=") })
    }

    @Test
    fun `persistentRootfs emits -rootfs-volume in run and restore for STDIO_MUX and RAW_PTY`() {
        val builder = newBuilder()
        val agentDir = File(File(tempDir, "files"), "agent/arm64").apply { mkdirs() }
        File(agentDir, "termvr-agent").apply { writeText("agent") }

        val stdioProfile = SandboxProfile(
            id = "deb-persist",
            displayName = "Debian Persist",
            imageRef = "docker.io/library/debian:trixie-slim",
            muxMode = MuxMode.STDIO_MUX,
            persistentRootfs = true,
        )
        val stdioRun = builder.buildRunArgs(stdioProfile, 5353, builder.controlSocketFile(stdioProfile.id))
        val expectedAgentMount = "${agentDir.absolutePath}:/run/termvr-agent:ro"
        assertTrue(stdioRun.contains(expectedAgentMount))
        val stdioRootVolIdx = stdioRun.indexOf("-rootfs-volume")
        assertTrue(stdioRootVolIdx >= 0)
        assertEquals("rootfs-deb-persist", stdioRun[stdioRootVolIdx + 1])
        assertFalse(stdioRun.contains("--persist-root-dir"))
        assertFalse(stdioRun.any { it.contains("rootfs-deb-persist:") })

        val stdioRestore = builder.buildRestoreArgs(
            profile = stdioProfile,
            slotDir = File(tempDir, "cp/deb-persist"),
            dnsPort = 5353,
            controlSock = builder.controlSocketFile(stdioProfile.id),
        )
        assertTrue(stdioRestore.contains(expectedAgentMount))
        val stdioRestoreIdx = stdioRestore.indexOf("-rootfs-volume")
        assertTrue(stdioRestoreIdx >= 0)
        assertEquals("rootfs-deb-persist", stdioRestore[stdioRestoreIdx + 1])

        val rawProfile = SandboxProfile(
            id = "raw-persist",
            displayName = "Raw Persist",
            imageRef = "docker.io/library/alpine:3.21",
            muxMode = MuxMode.RAW_PTY,
            defaultShellArgv = listOf("/bin/sh", "-l"),
            persistentRootfs = true,
        )
        val rawRun = builder.buildRunArgs(rawProfile, 5353, builder.controlSocketFile(rawProfile.id))
        val rawRootVolIdx = rawRun.indexOf("-rootfs-volume")
        assertTrue(rawRootVolIdx >= 0)
        assertEquals("rootfs-raw-persist", rawRun[rawRootVolIdx + 1])
        assertFalse(rawRun.contains(expectedAgentMount))
        assertFalse(rawRun.contains("--entrypoint"))
        assertFalse(rawRun.contains("--persist-root-dir"))
        assertFalse(rawRun.contains("--exec"))
        val rawImageIdx = rawRun.indexOf("-image")
        assertTrue(rawImageIdx >= 0)
        assertEquals("docker.io/library/alpine:3.21", rawRun[rawImageIdx + 1])
        val ddIdx = rawRun.indexOf("--")
        assertTrue(ddIdx > rawImageIdx)
        assertEquals(listOf("/bin/sh", "-l"), rawRun.subList(ddIdx + 1, rawRun.size))

        val rawRestore = builder.buildRestoreArgs(
            profile = rawProfile,
            slotDir = File(tempDir, "cp/raw-persist"),
            dnsPort = 5353,
            controlSock = builder.controlSocketFile(rawProfile.id),
        )
        val rawRestoreIdx = rawRestore.indexOf("-rootfs-volume")
        assertTrue(rawRestoreIdx >= 0)
        assertEquals("rootfs-raw-persist", rawRestore[rawRestoreIdx + 1])
        assertFalse(rawRestore.contains(expectedAgentMount))
    }

    @Test
    fun `buildStoppedVolumeHelperArgs uses -rootfs-volume for persistent rootfs and -v for regular volumes`() {
        val builder = newBuilder()
        val ctl = builder.controlSocketFile("volhelp-1")

        val normalArgs = builder.buildStoppedVolumeHelperArgs(
            volumeName = "proj-vol",
            helperProfileId = "volhelp-1",
            imageRef = "busybox:latest",
            dnsPort = 5353,
            controlSock = ctl,
            readOnly = true,
            isRootfsVolume = false,
        )
        assertTrue(normalArgs.contains("proj-vol:/mnt/volume:ro"))
        assertFalse(normalArgs.contains("-rootfs-volume"))

        val rootfsArgs = builder.buildStoppedVolumeHelperArgs(
            volumeName = "rootfs-deb",
            helperProfileId = "volhelp-2",
            imageRef = "docker.io/library/debian:trixie-slim",
            dnsPort = 5353,
            controlSock = ctl,
            readOnly = true,
            isRootfsVolume = true,
        )
        val rootVolIdx = rootfsArgs.indexOf("-rootfs-volume")
        assertTrue(rootVolIdx >= 0)
        assertEquals("rootfs-deb", rootfsArgs[rootVolIdx + 1])
        assertFalse(rootfsArgs.any { it.contains(":/mnt/volume") })
        val imgIdx = rootfsArgs.indexOf("-image")
        assertEquals("docker.io/library/debian:trixie-slim", rootfsArgs[imgIdx + 1])
    }

    @Test
    fun `OVERLAY_EXT4LITE and shared RO volumes emit exact --mount type=overlay and -v flags across run and restore`() {
        // Property: OVERLAY_EXT4LITE emits --mount type=overlay,src=<base>,dst=<dst>[,upper=<upper>] in both
        // buildRunArgs and buildRestoreArgs with Checkpoint/Restore parity, alongside -v <base>:<dst>:ro.
        // Seam: GvxCommandBuilder.buildRunArgs, buildRestoreArgs.
        // Oracle: Phase 2 gvx --mount type=overlay CLI contract.
        // Fault detected: Passing OVERLAY_EXT4LITE to -v instead of --mount, dropping upper=..., or losing --mount on restore.
        val builder = newBuilder()
        val profile = SandboxProfile(
            id = "cow-worker",
            displayName = "CoW Worker",
            imageRef = "docker.io/library/debian:trixie-slim",
            volumes = listOf(
                VolumeMountSpec(
                    sourceName = "shared-toolchain",
                    guestMountPath = "/opt/ro-sdk",
                    kind = VolumeKind.NAMED_EXT4LITE,
                    readOnly = true,
                ),
                VolumeMountSpec(
                    sourceName = "shared-toolchain",
                    guestMountPath = "/opt/persist-branch",
                    kind = VolumeKind.OVERLAY_EXT4LITE,
                    overlayUpperVolumeName = "shared-toolchain-wt-cow-worker",
                ),
                VolumeMountSpec(
                    sourceName = "shared-toolchain",
                    guestMountPath = "/opt/scratch-branch",
                    kind = VolumeKind.OVERLAY_EXT4LITE,
                    overlayUpperVolumeName = null,
                ),
            ),
        )

        val ctl = builder.controlSocketFile(profile.id)
        val runArgs = builder.buildRunArgs(profile, 5353, ctl)
        val restoreArgs = builder.buildRestoreArgs(profile, File(tempDir, "slot-a"), 5353, ctl)

        val expectedPersistSpec = "type=overlay,src=shared-toolchain,dst=/opt/persist-branch,upper=shared-toolchain-wt-cow-worker"
        val expectedScratchSpec = "type=overlay,src=shared-toolchain,dst=/opt/scratch-branch"

        for (args in listOf(runArgs, restoreArgs)) {
            assertTrue(args.contains("shared-toolchain:/opt/ro-sdk:ro"))
            val persistIdx = args.indexOf(expectedPersistSpec)
            assertTrue(persistIdx > 0)
            assertEquals("--mount", args[persistIdx - 1])

            val scratchIdx = args.indexOf(expectedScratchSpec)
            assertTrue(scratchIdx > 0)
            assertEquals("--mount", args[scratchIdx - 1])
        }
    }

    @Test
    fun `gitWorktree mounts shared repo_git and container worktree with TERMVR_GIT env vars and restore parity`() {
        // Property: When profile.gitWorktree != null, buildRunArgs and buildRestoreArgs mount <repo.git>:/git/<id>.git:rw
        // and <worktrees>/<profileId>:/workspace:rw (even when default self-workspace mount is present in profile.volumes,
        // while honoring explicit custom /workspace overrides), and buildRunArgs exports TERMVR_GIT_* env vars.
        // Seam: GvxCommandBuilder.buildRunArgs, buildRestoreArgs, effectiveWorkspaceDir.
        // Oracle: Phase 3 Git worktree runtime mount and env specification.
        // Fault detected: Shadowing worktree with empty <filesDir>/workspaces/<id>, double-mounting /workspace, or omitting /git/<id>.git on restore.
        val builder = newBuilder()
        val gitSpec = GitWorktreeSpec(projectId = "termvr-core", branchName = "wt/agent-1")

        // Case 1: Profile created from curated catalog (contains default self WORKSPACE_HOSTFS at /workspace)
        val profile = SandboxProfile(
            id = "agent-1",
            displayName = "Agent 1",
            imageRef = "docker.io/library/debian:trixie-slim",
            volumes = listOf(
                VolumeMountSpec(
                    sourceName = "agent-1",
                    guestMountPath = "/workspace",
                    kind = VolumeKind.WORKSPACE_HOSTFS,
                    readOnly = false,
                ),
            ),
            gitWorktree = gitSpec,
        )

        val expectedRepoMount = "${builder.gitProjectRepoDir("termvr-core").absolutePath}:/git/termvr-core.git:rw"
        val expectedWtMount = "${builder.gitWorktreeDir("termvr-core", "agent-1").absolutePath}:/workspace:rw"
        assertEquals(builder.gitWorktreeDir("termvr-core", "agent-1"), builder.effectiveWorkspaceDir(profile))

        val ctl = builder.controlSocketFile(profile.id)
        val runArgs = builder.buildRunArgs(profile, 5353, ctl)
        val restoreArgs = builder.buildRestoreArgs(profile, File(tempDir, "slot-git"), 5353, ctl)

        for (args in listOf(runArgs, restoreArgs)) {
            assertTrue(args.contains(expectedRepoMount))
            val wsMounts = args.filter { it.endsWith(":/workspace:rw") || it.endsWith(":/workspace:ro") }
            assertEquals(listOf(expectedWtMount), wsMounts)
        }

        assertTrue(runArgs.contains("TERMVR_GIT_PROJECT=termvr-core"))
        assertTrue(runArgs.contains("TERMVR_GIT_BRANCH=wt/agent-1"))
        assertTrue(runArgs.contains("TERMVR_GIT_COMMON_DIR=/git/termvr-core.git"))

        // Case 2: Explicit non-default /workspace override is honored without losing /git/<id>.git:rw
        val customWsProfile = profile.copy(
            volumes = listOf(
                VolumeMountSpec(
                    sourceName = "custom-ext4-ws",
                    guestMountPath = "/workspace",
                    kind = VolumeKind.NAMED_EXT4LITE,
                    readOnly = false,
                ),
            ),
        )
        val customArgs = builder.buildRunArgs(customWsProfile, 5353, ctl)
        assertTrue(customArgs.contains(expectedRepoMount))
        val customWsMounts = customArgs.filter { it.endsWith(":/workspace:rw") || it.endsWith(":/workspace:ro") }
        assertEquals(listOf("custom-ext4-ws:/workspace:rw"), customWsMounts)
    }

    @Test
    fun `emits gvx-ctrl v2 dynamic-ports, allow-volume-attach, pids-limit, auto-publish, and port remove specs`() {
        val builder = newBuilder()
        val profile = SandboxProfile(
            id = "ctrl-box",
            displayName = "Ctrl Box",
            imageRef = "docker.io/library/debian:trixie-slim",
            pidsLimit = 256,
            network = NetworkModeConfig(
                backend = NetBackend.NAT,
                autoPublishPorts = true,
                autoPublishAddr = "127.0.0.1",
                netTxBps = 25_000_000L,
                netRxBps = 50_000_000L,
            ),
        )
        val ctl = builder.controlSocketFile(profile.id)
        val runArgs = builder.buildRunArgs(profile, 5353, ctl)
        val restoreArgs = builder.buildRestoreArgs(profile, File(tempDir, "slot-a"), 5353, ctl)

        for (args in listOf(runArgs, restoreArgs)) {
            assertTrue(args.contains("--allow-volume-attach"))
            assertTrue(args.contains("--dynamic-ports"))
            assertTrue(args.contains("--auto-publish"))
            val autoAddrIdx = args.indexOf("--auto-publish-addr")
            assertTrue(autoAddrIdx >= 0)
            assertEquals("127.0.0.1", args[autoAddrIdx + 1])
            val txIdx = args.indexOf("--net-tx-bps")
            assertTrue(txIdx >= 0)
            assertEquals("25000000", args[txIdx + 1])
            val rxIdx = args.indexOf("--net-rx-bps")
            assertTrue(rxIdx >= 0)
            assertEquals("50000000", args[rxIdx + 1])
            val pidsIdx = args.indexOf("--pids-limit")
            assertTrue(pidsIdx >= 0)
            assertEquals("256", args[pidsIdx + 1])
        }

        val unlimitedArgs = builder.buildRunArgs(profile.copy(pidsLimit = 0, network = NetworkModeConfig(backend = NetBackend.NAT, autoPublishPorts = false)), 5353, ctl)
        assertFalse(unlimitedArgs.contains("--pids-limit"))
        assertFalse(unlimitedArgs.contains("--auto-publish"))
        assertFalse(unlimitedArgs.contains("--auto-publish-addr"))
        assertFalse(unlimitedArgs.contains("--net-tx-bps"))
        assertFalse(unlimitedArgs.contains("--net-rx-bps"))
        assertTrue(unlimitedArgs.contains("--dynamic-ports"))

        assertEquals(
            File(builder.gvxStoreDir, "gvx/volumes/work-vol.ext4"),
            builder.volumeImageFile("work-vol"),
        )
        val tcpRule = PortForwardRule("r1", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 8080, 8080, 80, 80)
        assertEquals("127.0.0.1:8080:80/tcp", builder.formatPortRemoveSpec("ctrl-box", tcpRule))
        val ephemRule = PortForwardRule("r2", ForwardProtocol.UDP, ListenHost.LOOPBACK_V6, 0, 0, 5300, 5300)
        assertEquals("[::1]:45123:5300/udp", builder.formatPortRemoveSpec("ctrl-box", ephemRule, effectiveHostPort = 45123))
        assertEquals(listOf("[::1]::5300/udp"), builder.formatPortRemoveSpecs("ctrl-box", ephemRule, effectiveHostPort = null))
        val rangeRule = PortForwardRule("r3", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 7000, 7002, 8000, 8002)
        assertEquals(
            listOf("127.0.0.1:7000:8000/tcp", "127.0.0.1:7001:8001/tcp", "127.0.0.1:7002:8002/tcp"),
            builder.formatPortRemoveSpecs("ctrl-box", rangeRule),
        )
    }
}
