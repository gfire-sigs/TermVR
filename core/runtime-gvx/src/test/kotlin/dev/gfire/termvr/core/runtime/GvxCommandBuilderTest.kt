package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.GuestLinuxArch
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.NetBackend
import dev.gfire.termvr.core.model.NetworkModeConfig
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.PortForwardRule
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
                outboundProxy = OutboundProxyConfig(transparentTcp = true, noProxyEntries = listOf("localhost", ".svc")),
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
        val p1 = GvxCommandBuilder.parsePublishingLine("rungv: publishing 127.0.0.1:41234->8080/tcp")
        assertNotNull(p1)
        assertEquals("127.0.0.1", p1!!.listenHost)
        assertEquals(41234, p1.hostPort)
        assertEquals(8080, p1.guestPort)
        assertEquals(ForwardProtocol.TCP, p1.protocol)

        val p2 = GvxCommandBuilder.parsePublishingLine("publishing [::1]:55123:9000/udp")
        assertNotNull(p2)
        assertEquals("[::1]", p2!!.listenHost)
        assertEquals(55123, p2.hostPort)
        assertEquals(9000, p2.guestPort)
        assertEquals(ForwardProtocol.UDP, p2.protocol)

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
}
