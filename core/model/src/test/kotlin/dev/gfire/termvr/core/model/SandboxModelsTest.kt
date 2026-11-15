package dev.gfire.termvr.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SandboxModelsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `INV-S1 SandboxProfile validates slug regex schemaVersion and memory bounds and clamps resources`() {
        // Property: SandboxProfile accepts [a-z0-9-]{3,32} slugs and 256..12288 MiB RAM, rejecting all out-of-range inputs.
        // Seam: SandboxProfile constructor, clampMemoryMiB, clampCpuCores, and Json codec.
        // Oracle: Design 0001 Section 11.1 contract.
        // Fault detected: uppercase/short/long slug acceptance, memory overflow/underflow, or schemaVersion bypass.
        val valid = SandboxProfile(
            id = "ubuntu-24-dev",
            displayName = "Ubuntu 24.04 Dev",
            imageRef = "docker.io/library/ubuntu:24.04",
            memoryMiB = SandboxProfile.clampMemoryMiB(64),
            cpuCores = SandboxProfile.clampCpuCores(16.0, hostAvailableProcessors = 8),
        )
        assertEquals(256, valid.memoryMiB)
        assertEquals(8.0, valid.cpuCores)
        assertEquals(12288, SandboxProfile.clampMemoryMiB(65536))
        assertEquals(valid, json.decodeFromString<SandboxProfile>(json.encodeToString(valid)))

        val badSlugs = listOf("ab", "a".repeat(33), "Ubuntu", "my_box", "box/1", "box..1", "box space")
        for (slug in badSlugs) {
            assertThrows(IllegalArgumentException::class.java, {
                SandboxProfile(id = slug, displayName = "Test", imageRef = "alpine:3.21")
            }, "Expected slug '$slug' to fail")
        }

        for (badMem in listOf(0, 255, 12289, -512)) {
            assertThrows(IllegalArgumentException::class.java, {
                SandboxProfile(id = "alpine-dev", displayName = "Test", imageRef = "alpine:3.21", memoryMiB = badMem)
            }, "Expected memoryMiB=$badMem to fail")
        }

        for (badSchema in listOf(0, 2, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                SandboxProfile(schemaVersion = badSchema, id = "alpine-dev", displayName = "Test", imageRef = "alpine:3.21")
            }
        }
    }

    @Test
    fun `INV-S2 VolumeMountSpec rejects path traversal delimiters and relative guest mounts`() {
        // Property: sourceName matches [a-z0-9._-]+ excluding '.' and '..'; guestMountPath is absolute without '..'.
        // Seam: VolumeMountSpec constructor.
        // Oracle: Design 0001 Section 11.1 and Section 17.2 path confinement invariants.
        // Fault detected: hostfs traversal or rungv -v delimiter injection via ',' or ':'.
        val valid = VolumeMountSpec(
            sourceName = "docker_vol-01.data",
            guestMountPath = "/var/lib/docker",
            kind = VolumeKind.NAMED_EXT4LITE,
        )
        assertEquals(valid, json.decodeFromString<VolumeMountSpec>(json.encodeToString(valid)))

        for (badSource in listOf("", ".", "..", "vol/name", "vol:rw", "vol,ro", "UpperVol")) {
            assertThrows(IllegalArgumentException::class.java, {
                VolumeMountSpec(sourceName = badSource, guestMountPath = "/data", kind = VolumeKind.NAMED_EXT4LITE)
            }, "Expected sourceName '$badSource' to fail")
        }
        for (badMount in listOf("", "var/lib/docker", "/var/../etc", "/data:ro", "/data,rw")) {
            assertThrows(IllegalArgumentException::class.java, {
                VolumeMountSpec(sourceName = "data-vol", guestMountPath = badMount, kind = VolumeKind.WORKSPACE_HOSTFS)
            }, "Expected guestMountPath '$badMount' to fail")
        }
    }

    @Test
    fun `INV-S3 OutboundProxyConfig and ProxyEndpointSpec enforce ports modes and zero raw password storage`() {
        // Property: ProxyEndpointSpec validates port 1..65535, forbids inline credentials, and CUSTOM mode requires endpoint.
        // Seam: OutboundProxyConfig, ProxyEndpointSpec, NetworkModeConfig, and serialized JSON output.
        // Oracle: Design 0001 Section 8.1 and Section 11.1.
        // Fault detected: port 0/65536 acceptance, CUSTOM mode with null endpoint, or credential leak into JSON.
        assertEquals(
            listOf(
                "localhost", "127.0.0.1", "::1",
                "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                ".svc", ".cluster.local",
            ),
            OutboundProxyConfig.DEFAULT_NO_PROXY,
        )

        val endpoint = ProxyEndpointSpec(
            protocol = ProxyProtocol.SOCKS5,
            host = "127.0.0.1",
            port = 9050,
            username = "termvr",
            credentialKeyAlias = "proxy_secret_orbot",
        )
        val proxyConfig = OutboundProxyConfig(
            mode = OutboundProxyMode.CUSTOM,
            customEndpoint = endpoint,
            routeDnsViaProxy = true,
            transparentTcp = true,
        )
        val encoded = json.encodeToString(proxyConfig)
        assertFalse(encoded.contains("password", ignoreCase = true))
        assertEquals(proxyConfig, json.decodeFromString<OutboundProxyConfig>(encoded))

        assertThrows(IllegalArgumentException::class.java) {
            OutboundProxyConfig(mode = OutboundProxyMode.CUSTOM, customEndpoint = null)
        }
        for (badPort in listOf(0, -1, 65536, 100000)) {
            assertThrows(IllegalArgumentException::class.java) {
                ProxyEndpointSpec(protocol = ProxyProtocol.HTTP, host = "127.0.0.1", port = badPort)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyEndpointSpec(protocol = ProxyProtocol.HTTP, host = "user:pass@proxy.example.com", port = 8080)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyEndpointSpec(protocol = ProxyProtocol.HTTP, host = "http://proxy.example.com", port = 8080)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyEndpointSpec(protocol = ProxyProtocol.SOCKS5, host = "127.0.0.1", port = 1080, username = "user:secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkModeConfig(backend = NetBackend.WIREGUARD, wireGuardConfigName = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkModeConfig(backend = NetBackend.WIREGUARD, wireGuardConfigName = "../escape")
        }
    }

    @Test
    fun `INV-S4 ListenHost retains IPv6 square brackets and PortForwardRule hard-blocks port 2375 on LAN`() {
        // Property: IPv6 wire literals keep brackets; port 2375 is blocked on 0.0.0.0 and [::] across single and range rules.
        // Seam: ListenHost.wireLiteral, PortForwardRule constructor, isLanDockerPortBlocked, and downgradeLanToLoopback.
        // Oracle: Design 0001 Section 8.1, Section 11.1, and Section 16.3.
        // Fault detected: stripped IPv6 brackets breaking rungv -p, or unauthenticated Docker TCP exposed to Wi-Fi LAN.
        assertEquals("127.0.0.1", ListenHost.LOOPBACK_V4.wireLiteral)
        assertEquals("[::1]", ListenHost.LOOPBACK_V6.wireLiteral)
        assertEquals("0.0.0.0", ListenHost.LAN_ALL_V4.wireLiteral)
        assertEquals("[::]", ListenHost.LAN_ALL_V6.wireLiteral)
        assertFalse(ListenHost.LOOPBACK_V4.exposesLan)
        assertFalse(ListenHost.LOOPBACK_V6.exposesLan)
        assertTrue(ListenHost.LAN_ALL_V4.exposesLan)
        assertTrue(ListenHost.LAN_ALL_V6.exposesLan)

        // Loopback 2375 is permitted on both IPv4 and IPv6.
        val loopbackDockerV4 = PortForwardRule(
            id = "docker-local-v4",
            protocol = ForwardProtocol.TCP,
            listenHost = ListenHost.LOOPBACK_V4,
            hostPortStart = 2375,
            guestPortStart = 2375,
        )
        val loopbackDockerV6 = loopbackDockerV4.copy(id = "docker-local-v6", listenHost = ListenHost.LOOPBACK_V6)
        assertEquals(2375, loopbackDockerV6.guestPortStart)

        // Single port and range containing 2375 on 0.0.0.0 or [::] must fail.
        for (lanHost in listOf(ListenHost.LAN_ALL_V4, ListenHost.LAN_ALL_V6)) {
            assertTrue(PortForwardRule.isLanDockerPortBlocked(lanHost, 2375, 2375, 2375, 2375))
            assertTrue(PortForwardRule.isLanDockerPortBlocked(lanHost, 8080, 8080, 2375, 2375))
            assertTrue(PortForwardRule.isLanDockerPortBlocked(lanHost, 2370, 2380, 2370, 2380))
            assertThrows(IllegalArgumentException::class.java) {
                PortForwardRule(
                    id = "lan-docker-direct",
                    protocol = ForwardProtocol.TCP,
                    listenHost = lanHost,
                    hostPortStart = 2375,
                    guestPortStart = 2375,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                PortForwardRule(
                    id = "lan-docker-remapped",
                    protocol = ForwardProtocol.TCP,
                    listenHost = lanHost,
                    hostPortStart = 0,
                    hostPortEnd = 0,
                    guestPortStart = 2375,
                    guestPortEnd = 2375,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                PortForwardRule(
                    id = "lan-docker-range",
                    protocol = ForwardProtocol.TCP,
                    listenHost = lanHost,
                    hostPortStart = 2370,
                    hostPortEnd = 2380,
                    guestPortStart = 2370,
                    guestPortEnd = 2380,
                )
            }
        }

        val lanWeb = PortForwardRule(
            id = "web-v6",
            protocol = ForwardProtocol.TCP,
            listenHost = ListenHost.LAN_ALL_V6,
            hostPortStart = 8000,
            guestPortStart = 8000,
        )
        assertTrue(lanWeb.exposesLan)
        assertEquals(ListenHost.LOOPBACK_V6, lanWeb.downgradeLanToLoopback().listenHost)

        val unixRule = PortForwardRule(
            id = "docker-sock",
            protocol = ForwardProtocol.UNIX_STREAM,
            hostPortStart = 0,
            guestPortStart = 0,
            unixSocketName = "docker.sock",
            guestUnixPath = "/var/run/docker.sock",
        )
        assertEquals(unixRule, json.decodeFromString<PortForwardRule>(json.encodeToString(unixRule)))
    }

    @Test
    fun `INV-S5 Curated distributions telemetry host readiness and GlobalAppSettings obey platform contracts`() {
        // Property: Curated catalog has 4 distros per arch, strict ABI filtering, 16KB page detection, and valid settings.
        // Seam: DistributionCatalogEntry, SandboxTelemetrySnapshot, HostReadinessReport, GlobalAppSettings.
        // Oracle: Design 0001 Section 8.2, 14.1, 14.4, 15.5, 16.1, 16.2, and 17.2.
        // Fault detected: cross-ISA image leakage, missed 16KB page flag, or unlocked workspace during FROZEN state.
        val arm64Entries = DistributionCatalogEntry.forAndroidAbi("arm64-v8a")
        val amd64Entries = DistributionCatalogEntry.forAndroidAbi("x86_64")
        assertEquals(4, arm64Entries.size)
        assertEquals(4, amd64Entries.size)
        assertTrue(arm64Entries.all { it.architecture == GuestLinuxArch.ARM64 })
        assertTrue(amd64Entries.all { it.architecture == GuestLinuxArch.AMD64 })
        assertEquals(
            setOf("Alpine 3.21", "Debian 13 (Trixie)", "Ubuntu 24.04 LTS", "Fedora 42"),
            arm64Entries.map { it.displayName }.toSet(),
        )
        assertTrue(DistributionCatalogEntry.forAndroidAbi("armeabi-v7a").isEmpty())
        assertNull(GuestLinuxArch.fromAndroidAbi("armeabi-v7a"))

        val alpineProfile = arm64Entries.first { it.distroSlug == "alpine" }.toSandboxProfile()
        assertEquals(MuxMode.STDIO_MUX, alpineProfile.muxMode)
        assertTrue(alpineProfile.volumes.any { it.guestMountPath == "/workspace" && it.kind == VolumeKind.WORKSPACE_HOSTFS })

        assertTrue(SandboxRunState.FREEZING.locksWorkspaceWrites)
        assertTrue(SandboxRunState.FROZEN.locksWorkspaceWrites)
        assertFalse(SandboxRunState.RUNNING.locksWorkspaceWrites)

        val readiness = HostReadinessReport(
            hostSdkInt = 36,
            hostAbi = "arm64-v8a",
            guestArch = GuestLinuxArch.ARM64,
            kernelPageSizeBytes = 16384,
            totalHostRamMiB = 8192,
            freeDiskMiB = 20480,
            liveChildProcessCount = 14,
            phantomKillerDetected = true,
            remediationKind = PhantomProcessRemediation.ANDROID_14_DEV_TOGGLE,
        )
        assertTrue(readiness.is16KbPageKernel)
        assertTrue(readiness.exceedsRecommendedProcessBudget)
        assertEquals(readiness, json.decodeFromString<HostReadinessReport>(json.encodeToString(readiness)))

        assertThrows(IllegalArgumentException::class.java) {
            readiness.copy(kernelPageSizeBytes = 12288)
        }

        val telemetry = SandboxTelemetrySnapshot(
            profileId = "alpine",
            state = SandboxRunState.RUNNING,
            cpuPercent = 14.5,
            cpuNanos = 500_000_000L,
            pssBytes = 612L * 1024L * 1024L,
            guestRssBytes = 120L * 1024L * 1024L,
            hostChildProcessCount = 4,
            guestProcessCount = 6,
            guestListenPorts = listOf(8000),
            exposesLan = true,
            activeProxyLabel = "SOCKS5",
        )
        assertEquals(612L, telemetry.pssMiB)
        assertEquals(telemetry, json.decodeFromString<SandboxTelemetrySnapshot>(json.encodeToString(telemetry)))

        val settings = GlobalAppSettings(
            pinKeyBarWithHardwareKeyboard = true,
            passAllShortcutsToTerminal = true,
            selectedPaletteId = ColorPalette.HIGH_CONTRAST_DARK.id,
        )
        assertEquals(settings, json.decodeFromString<GlobalAppSettings>(json.encodeToString(settings)))
        assertThrows(IllegalArgumentException::class.java) {
            GlobalAppSettings(schemaVersion = 99)
        }
    }
}
