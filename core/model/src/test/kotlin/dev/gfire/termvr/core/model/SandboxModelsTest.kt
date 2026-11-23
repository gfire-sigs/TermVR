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
    fun `INV-S1 SandboxProfile validates slug regex schemaVersion and unlimited or bounded resources`() {
        // Property: SandboxProfile defaults to unlimited (0 / 0.0), accepts 0 or 256..12288 MiB, and clamps resources.
        // Seam: SandboxProfile constructor, clampMemoryMiB, clampCpuCores, hasMemoryLimit, hasCpuLimit, and Json codec.
        // Oracle: Design 0001 Section 11.1 contract.
        // Fault detected: uppercase/short/long slug acceptance, memory overflow/underflow, or schemaVersion bypass.
        val unlimitedDefault = SandboxProfile(
            id = "debian-box",
            displayName = "Debian 13",
            imageRef = "docker.io/library/debian:trixie-slim",
        )
        assertEquals(SandboxProfile.UNLIMITED_MEMORY_MIB, unlimitedDefault.memoryMiB)
        assertEquals(SandboxProfile.UNLIMITED_CPU_CORES, unlimitedDefault.cpuCores)
        assertFalse(unlimitedDefault.hasMemoryLimit)
        assertFalse(unlimitedDefault.hasCpuLimit)
        assertEquals(0, SandboxProfile.clampMemoryMiB(0))
        assertEquals(0, SandboxProfile.clampMemoryMiB(-512))
        assertEquals(0.0, SandboxProfile.clampCpuCores(0.0, hostAvailableProcessors = 8))
        assertEquals(0.0, SandboxProfile.clampCpuCores(-2.0, hostAvailableProcessors = 8))

        val valid = SandboxProfile(
            id = "ubuntu-24-dev",
            displayName = "Ubuntu 24.04 Dev",
            imageRef = "docker.io/library/ubuntu:24.04",
            memoryMiB = SandboxProfile.clampMemoryMiB(64),
            cpuCores = SandboxProfile.clampCpuCores(16.0, hostAvailableProcessors = 8),
        )
        assertEquals(256, valid.memoryMiB)
        assertEquals(8.0, valid.cpuCores)
        assertTrue(valid.hasMemoryLimit)
        assertTrue(valid.hasCpuLimit)
        assertEquals(12288, SandboxProfile.clampMemoryMiB(65536))
        assertEquals(valid, json.decodeFromString<SandboxProfile>(json.encodeToString(valid)))

        val badSlugs = listOf("ab", "a".repeat(33), "Ubuntu", "my_box", "box/1", "box..1", "box space")
        for (slug in badSlugs) {
            assertThrows(IllegalArgumentException::class.java, {
                SandboxProfile(id = slug, displayName = "Test", imageRef = "alpine:3.21")
            }, "Expected slug '$slug' to fail")
        }

        for (badMem in listOf(1, 255, 12289, -512)) {
            assertThrows(IllegalArgumentException::class.java, {
                SandboxProfile(id = "alpine-dev", displayName = "Test", imageRef = "alpine:3.21", memoryMiB = badMem)
            }, "Expected memoryMiB=$badMem to fail")
        }

        for (badCpu in listOf(-0.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java, {
                SandboxProfile(id = "alpine-dev", displayName = "Test", imageRef = "alpine:3.21", cpuCores = badCpu)
            }, "Expected cpuCores=$badCpu to fail")
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
            listOf("Debian 13", "Ubuntu 24.04", "Fedora 42", "Alpine 3.21"),
            arm64Entries.map { it.displayName },
        )
        assertEquals(listOf("/bin/bash"), arm64Entries[0].defaultShellArgv)
        assertEquals(listOf("/bin/bash"), arm64Entries[1].defaultShellArgv)
        assertEquals(listOf("/bin/bash"), arm64Entries[2].defaultShellArgv)
        assertEquals(listOf("/bin/ash"), arm64Entries[3].defaultShellArgv)
        assertEquals("debian-trixie", arm64Entries.first().id)
        assertEquals("docker.io/library/debian:trixie-slim", arm64Entries.first().imageRef)
        assertTrue(arm64Entries.all { it.recommendedMemoryMiB == 0 && it.defaultMemoryMiB == 0 })
        assertTrue(arm64Entries.all { SandboxProfile.isValidProfileId(it.id) })
        assertTrue(DistributionCatalogEntry.forAndroidAbi("armeabi-v7a").isEmpty())
        assertNull(GuestLinuxArch.fromAndroidAbi("armeabi-v7a"))

        val debianProfile = arm64Entries.first().toSandboxProfile(profileId = arm64Entries.first().id)
        assertEquals(MuxMode.STDIO_MUX, debianProfile.muxMode)
        assertFalse(debianProfile.hasMemoryLimit)
        assertFalse(debianProfile.hasCpuLimit)
        assertTrue(debianProfile.volumes.any { it.guestMountPath == "/workspace" && it.kind == VolumeKind.WORKSPACE_HOSTFS })

        assertThrows(IllegalArgumentException::class.java) {
            arm64Entries.first().copy(defaultMemoryMiB = 128)
        }

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

    @Test
    fun `SandboxProfile persistentRootfs defaults false decodes legacy json and derives rootfs volume name`() {
        val profile = SandboxProfile(
            id = "deb-persist",
            displayName = "Debian Persist",
            imageRef = "docker.io/library/debian:trixie-slim",
        )
        assertFalse(profile.persistentRootfs)
        assertEquals("rootfs-deb-persist", profile.rootfsVolumeName)

        val enabled = profile.copy(persistentRootfs = true)
        assertTrue(enabled.persistentRootfs)
        assertEquals("rootfs-deb-persist", enabled.rootfsVolumeName)
        val roundTripped = json.decodeFromString<SandboxProfile>(json.encodeToString(enabled))
        assertEquals(enabled, roundTripped)

        // Legacy profile JSON omitting persistentRootfs must decode cleanly as false
        val legacyJson = """{"id":"legacy-deb","displayName":"Legacy","imageRef":"docker.io/library/debian:trixie-slim"}"""
        val decodedLegacy = Json { ignoreUnknownKeys = true }.decodeFromString<SandboxProfile>(legacyJson)
        assertFalse(decodedLegacy.persistentRootfs)
        assertEquals("rootfs-legacy-deb", decodedLegacy.rootfsVolumeName)

        val distro = DistributionCatalogEntry(
            id = "debian",
            distroSlug = "debian",
            displayName = "Debian 13",
            version = "13",
            summary = "Minimal Debian rootfs",
            architecture = GuestLinuxArch.ARM64,
            imageRef = "docker.io/library/debian:trixie-slim",
            approximateDownloadMiB = 30,
        )
        assertFalse(distro.toSandboxProfile().persistentRootfs)
        assertTrue(distro.toSandboxProfile(persistentRootfs = true).persistentRootfs)
    }

    @Test
    fun `INV-S6 VolumeLockMode infers NONE SHARED and EXCLUSIVE from wire mode or legacy InUse flag`() {
        // Property: Explicit wire lock modes map case-insensitively to VolumeLockMode; blank/absent wire mode falls back to InUse.
        // Seam: VolumeLockMode.fromWireOrInUse, isInUse, allowsSharedReaders.
        // Oracle: Phase 1 gvx VolumeInfo LockMode contract ("none" | "shared" | "exclusive" + InUse fallback).
        // Fault detected: Treating LOCK_SH ("shared") as exclusive single-writer or misclassifying legacy InUse=true JSON.
        assertEquals(VolumeLockMode.NONE, VolumeLockMode.fromWireOrInUse("none", inUse = false))
        assertEquals(VolumeLockMode.SHARED, VolumeLockMode.fromWireOrInUse("shared", inUse = true))
        assertEquals(VolumeLockMode.SHARED, VolumeLockMode.fromWireOrInUse("SHARED", inUse = false))
        assertEquals(VolumeLockMode.EXCLUSIVE, VolumeLockMode.fromWireOrInUse("exclusive", inUse = true))
        assertEquals(VolumeLockMode.EXCLUSIVE, VolumeLockMode.fromWireOrInUse(null, inUse = true))
        assertEquals(VolumeLockMode.NONE, VolumeLockMode.fromWireOrInUse(null, inUse = false))
        assertEquals(VolumeLockMode.EXCLUSIVE, VolumeLockMode.fromWireOrInUse("", inUse = true))

        assertFalse(VolumeLockMode.NONE.isInUse)
        assertTrue(VolumeLockMode.SHARED.isInUse)
        assertTrue(VolumeLockMode.EXCLUSIVE.isInUse)

        assertTrue(VolumeLockMode.NONE.allowsSharedReaders)
        assertTrue(VolumeLockMode.SHARED.allowsSharedReaders)
        assertFalse(VolumeLockMode.EXCLUSIVE.allowsSharedReaders)
    }

    @Test
    fun `INV-S7 VolumeMountSpec enforces OVERLAY_EXT4LITE upper-volume self-overlay readOnly and sandbox conflict invariants`() {
        // Property: OVERLAY_EXT4LITE permits null (ephemeral tmpfs) or valid distinct upper volume name with readOnly=false,
        // rejects upper on non-overlay kinds, rejects self-overlay (upper == sourceName), rejects readOnly=true on overlay,
        // rejects reserved guest roots, and allows shared lower base volumes while rejecting RW+RO self-conflicts in one profile.
        // Seam: VolumeMountSpec constructor, forMode, suggestOverlayUpperVolumeName, and SandboxProfile constructor.
        // Oracle: Phase 2 CoW overlay volume specification.
        // Fault detected: Emitting --mount type=overlay with upper==src, readonly overlay, or non-overlay carrying upper volume.
        val ephemeralOverlay = VolumeMountSpec(
            sourceName = "base-sdk",
            guestMountPath = "/opt/sdk",
            kind = VolumeKind.OVERLAY_EXT4LITE,
            readOnly = false,
            overlayUpperVolumeName = null,
        )
        assertEquals(VolumeMountMode.COW_EPHEMERAL, ephemeralOverlay.mountMode)
        assertEquals(ephemeralOverlay, json.decodeFromString<VolumeMountSpec>(json.encodeToString(ephemeralOverlay)))

        val suggestedUpper = VolumeMountSpec.suggestOverlayUpperVolumeName("base-sdk", "Ubuntu 24.04 Dev!")
        assertEquals("base-sdk-wt-ubuntu-24.04-dev", suggestedUpper)
        assertTrue(VolumeMountSpec.isValidVolumeName(suggestedUpper))

        val maxBase = "b".repeat(60)
        val boundedUpper = VolumeMountSpec.suggestOverlayUpperVolumeName(maxBase, "very-long-container-profile-name")
        assertTrue(boundedUpper.length <= 64)
        assertTrue(VolumeMountSpec.isValidVolumeName(boundedUpper))
        assertFalse(boundedUpper == maxBase)

        val persistentOverlay = VolumeMountSpec.forMode(
            baseVolumeName = "base-sdk",
            guestMountPath = "/opt/sdk",
            mode = VolumeMountMode.COW_PERSISTENT,
            overlayUpperVolumeName = suggestedUpper,
        )
        assertEquals(VolumeKind.OVERLAY_EXT4LITE, persistentOverlay.kind)
        assertFalse(persistentOverlay.readOnly)
        assertEquals(suggestedUpper, persistentOverlay.overlayUpperVolumeName)
        assertEquals(VolumeMountMode.COW_PERSISTENT, persistentOverlay.mountMode)
        assertEquals(persistentOverlay, json.decodeFromString<VolumeMountSpec>(json.encodeToString(persistentOverlay)))

        // Non-overlay kinds must never set overlayUpperVolumeName.
        for (nonOverlayKind in listOf(VolumeKind.NAMED_EXT4LITE, VolumeKind.WORKSPACE_HOSTFS)) {
            assertThrows(IllegalArgumentException::class.java) {
                VolumeMountSpec(
                    sourceName = "data-vol",
                    guestMountPath = "/data",
                    kind = nonOverlayKind,
                    overlayUpperVolumeName = "upper-vol",
                )
            }
        }

        // OVERLAY_EXT4LITE must have readOnly == false.
        assertThrows(IllegalArgumentException::class.java) {
            VolumeMountSpec(
                sourceName = "base-sdk",
                guestMountPath = "/opt/sdk",
                kind = VolumeKind.OVERLAY_EXT4LITE,
                readOnly = true,
            )
        }

        // Self-overlay (overlayUpperVolumeName == sourceName) and invalid upper names must fail.
        for (badUpper in listOf("base-sdk", "", ".", "..", "-leading-dash", ".hidden", "Upper", "bad/vol", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java, {
                VolumeMountSpec(
                    sourceName = "base-sdk",
                    guestMountPath = "/opt/sdk",
                    kind = VolumeKind.OVERLAY_EXT4LITE,
                    overlayUpperVolumeName = badUpper,
                )
            }, "Expected overlayUpperVolumeName '$badUpper' to fail")
        }

        // Reserved guest paths /, /proc, /sys, /dev must fail on OVERLAY_EXT4LITE.
        for (reservedDst in listOf("/", "/proc", "/sys", "/dev")) {
            assertThrows(IllegalArgumentException::class.java) {
                VolumeMountSpec(
                    sourceName = "base-sdk",
                    guestMountPath = reservedDst,
                    kind = VolumeKind.OVERLAY_EXT4LITE,
                )
            }
        }

        // Multiple overlays or :ro mounts sharing the same lower base volume in one profile are permitted.
        val multiReaderProfile = SandboxProfile(
            id = "multi-reader",
            displayName = "Multi Reader",
            imageRef = "alpine:3.21",
            volumes = listOf(
                VolumeMountSpec("shared-base", "/mnt/ro", VolumeKind.NAMED_EXT4LITE, readOnly = true),
                VolumeMountSpec("shared-base", "/mnt/cow1", VolumeKind.OVERLAY_EXT4LITE, overlayUpperVolumeName = "upper-1"),
                VolumeMountSpec("shared-base", "/mnt/cow2", VolumeKind.OVERLAY_EXT4LITE, overlayUpperVolumeName = null),
            ),
        )
        assertEquals(3, multiReaderProfile.volumes.size)

        // Mixing RW (or upper) and RO/overlay-base on the same volume inside one profile must fail.
        assertThrows(IllegalArgumentException::class.java) {
            SandboxProfile(
                id = "rw-ro-conflict",
                displayName = "Conflict",
                imageRef = "alpine:3.21",
                volumes = listOf(
                    VolumeMountSpec("shared-base", "/mnt/rw", VolumeKind.NAMED_EXT4LITE, readOnly = false),
                    VolumeMountSpec("shared-base", "/mnt/cow", VolumeKind.OVERLAY_EXT4LITE, overlayUpperVolumeName = "upper-1"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SandboxProfile(
                id = "dup-upper-conflict",
                displayName = "Dup Upper",
                imageRef = "alpine:3.21",
                volumes = listOf(
                    VolumeMountSpec("base-a", "/mnt/a", VolumeKind.OVERLAY_EXT4LITE, overlayUpperVolumeName = "same-upper"),
                    VolumeMountSpec("base-b", "/mnt/b", VolumeKind.OVERLAY_EXT4LITE, overlayUpperVolumeName = "same-upper"),
                ),
            )
        }
    }

    @Test
    fun `INV-S8 GitWorktreeSpec and GitProjectSummary enforce git-check-ref-format and path-traversal rejection`() {
        // Property: projectId matches [a-zA-Z0-9][a-zA-Z0-9_.-]{0,63} without '..'; branchName obeys git-check-ref-format;
        // legacy SandboxProfile JSON without gitWorktree decodes with gitWorktree == null.
        // Seam: GitWorktreeSpec, GitProjectSummary, SandboxProfile.gitWorktree.
        // Oracle: Phase 3 shared Git worktree contract and git-check-ref-format(1).
        // Fault detected: Host path traversal via projectId/branchName, option injection via leading '-', or .lock ref corruption.
        val validBranches = listOf(
            "main",
            "wt/debian-main",
            "feature/auth_v2.1",
            "release/2026-11-rc1",
            "bugfix/123",
        )
        for (branch in validBranches) {
            val spec = GitWorktreeSpec(projectId = "TermVR_Core-1.0", branchName = branch)
            assertEquals(branch, spec.branchName)
            assertEquals(spec, json.decodeFromString<GitWorktreeSpec>(json.encodeToString(spec)))
        }

        val badProjectIds = listOf(
            "",
            ".",
            "..",
            ".hidden",
            "-dash",
            "_under",
            "proj..escape",
            "proj/sub",
            "proj\\sub",
            "proj space",
            "proj\u0000null",
            "a".repeat(65),
        )
        for (badId in badProjectIds) {
            assertThrows(IllegalArgumentException::class.java, {
                GitWorktreeSpec(projectId = badId, branchName = "main")
            }, "Expected projectId '$badId' to fail")
        }

        val badBranches = listOf(
            "",
            " ",
            "@",
            "HEAD",
            "-main",
            "--upload-pack=evil",
            "/main",
            "main/",
            "wt//sub",
            "..",
            "wt/../escape",
            "a..b",
            ".hidden",
            "wt/.hidden",
            "main.",
            "main.lock",
            "MAIN.LOCK",
            "wt/sub.lock/leaf",
            "feat@{0}",
            "feat space",
            "feat\ttab",
            "feat\nline",
            "feat\u0000nul",
            "feat\u007fdel",
            "feat:colon",
            "feat~1",
            "feat^2",
            "feat?mark",
            "feat*glob",
            "feat[0]",
            "feat\\slash",
        )
        for (badBranch in badBranches) {
            assertThrows(IllegalArgumentException::class.java, {
                GitWorktreeSpec(projectId = "valid-proj", branchName = badBranch)
            }, "Expected branchName '$badBranch' to fail")
        }

        val summary = GitProjectSummary(
            id = GitWorktreeSpec.suggestProjectId("My Monorepo 2026!"),
            name = "My Monorepo 2026!",
            defaultBranch = "main",
            createdAtEpochMs = 1763340000000L,
            worktreeCount = 2,
            diskBytes = 65536L,
        )
        assertEquals("my-monorepo-2026", summary.id)
        assertEquals("wt/debian-main", GitWorktreeSpec.defaultBranchForProfile("Debian Main"))
        assertEquals(summary, json.decodeFromString<GitProjectSummary>(json.encodeToString(summary)))

        val profileWithWt = SandboxProfile(
            id = "wt-agent-1",
            displayName = "Agent 1",
            imageRef = "debian:trixie-slim",
            gitWorktree = GitWorktreeSpec(projectId = "my-monorepo-2026", branchName = "wt/agent-1"),
        )
        assertEquals(profileWithWt, json.decodeFromString<SandboxProfile>(json.encodeToString(profileWithWt)))

        val legacyDecoded = Json { ignoreUnknownKeys = true }.decodeFromString<SandboxProfile>(
            """{"id":"legacy-box","displayName":"Legacy","imageRef":"alpine:3.21"}""",
        )
        assertNull(legacyDecoded.gitWorktree)
        assertEquals(SandboxProfile.UNLIMITED_PIDS_LIMIT, legacyDecoded.pidsLimit)
        assertFalse(legacyDecoded.network.autoPublishPorts)
    }

    @Test
    fun `pidsLimit, autoPublishPorts, PAUSED state, and gvx-ctrl v2 models round-trip cleanly`() {
        assertEquals(SandboxProfile.UNLIMITED_PIDS_LIMIT, SandboxProfile.clampPidsLimit(0))
        assertEquals(SandboxProfile.MIN_PIDS_LIMIT, SandboxProfile.clampPidsLimit(2))
        assertEquals(SandboxProfile.MAX_PIDS_LIMIT, SandboxProfile.clampPidsLimit(999_999))
        assertEquals(256, SandboxProfile.clampPidsLimit(256))

        val profile = SandboxProfile(
            id = "ctrl-v2-box",
            displayName = "Ctrl V2",
            imageRef = "debian:trixie-slim",
            pidsLimit = 256,
            network = NetworkModeConfig(autoPublishPorts = true),
        )
        assertTrue(profile.hasPidsLimit)
        assertEquals(profile, json.decodeFromString<SandboxProfile>(json.encodeToString(profile)))

        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(pidsLimit = 4)
        }

        assertTrue(SandboxRunState.RUNNING.isLiveInMemory)
        assertTrue(SandboxRunState.PAUSED.isLiveInMemory)
        assertFalse(SandboxRunState.FROZEN.isLiveInMemory)
        assertFalse(SandboxRunState.STOPPED.isLiveInMemory)
        assertFalse(SandboxRunState.PAUSED.locksWorkspaceWrites)

        val proc = LiveProcessInfo(pid = 7, comm = "sh", cmdline = listOf("/bin/sh", "-c", "echo hi"))
        assertEquals("/bin/sh -c echo hi", proc.displayCommand)
        assertEquals("init", LiveProcessInfo(pid = 1, comm = "init").displayCommand)

        val stats = LiveStatsSnapshot(
            cpuUserNanos = 100L,
            cpuSysNanos = 50L,
            blockIo = listOf(
                LiveBlockIoSample(disk = "vda", readOps = 10L, writeOps = 4L, readSectors = 16L, writeSectors = 8L),
                LiveBlockIoSample(disk = "vdb", readOps = 2L, writeOps = 1L, readSectors = 4L, writeSectors = 2L),
            ),
        )
        assertEquals(150L, stats.totalCpuNanos)
        assertEquals(12L, stats.blockReadOps)
        assertEquals(5L, stats.blockWriteOps)
        assertEquals(20L * 512L, stats.blockReadBytes)
        assertEquals(10L * 512L, stats.blockWriteBytes)

        val inspect = SandboxInspectSnapshot(
            id = "ctrl-v2-box",
            state = "paused",
            resources = LiveResourceLimits(
                memoryBytes = 512L * 1024L * 1024L,
                pidsLimit = 256L,
                isolateLan = true,
                netTxBps = 25_000_000L,
                netRxBps = 50_000_000L,
            ),
            ports = listOf(LivePortInfo(proto = "tcp", hostIp = "127.0.0.1", hostPort = 8080, guestPort = 80, source = "dynamic")),
            mounts = listOf(LiveMountInfo(target = "/data", type = "ext4lite", source = "vol1", readOnly = false, mutable = true)),
        )
        assertEquals(inspect, json.decodeFromString<SandboxInspectSnapshot>(json.encodeToString(inspect)))
        assertFalse(LiveResourceUpdate().hasChanges)
        assertTrue(LiveResourceUpdate(pidsLimit = 128).hasChanges)
        assertTrue(LiveResourceUpdate(netTxBps = 10_000_000L).hasChanges)
        assertTrue(LiveResourceUpdate(netRxBps = 20_000_000L).hasChanges)

        val natWithCaps = NetworkModeConfig(
            backend = NetBackend.NAT,
            autoPublishPorts = true,
            autoPublishAddr = "127.0.0.1",
            netTxBps = 10_000_000L,
            netRxBps = 50_000_000L,
        )
        assertEquals(natWithCaps, json.decodeFromString<NetworkModeConfig>(json.encodeToString(natWithCaps)))
        assertThrows(IllegalArgumentException::class.java) {
            NetworkModeConfig(netTxBps = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkModeConfig(netRxBps = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkModeConfig(autoPublishAddr = "127.0.0.1 bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkModeConfig(autoPublishAddr = "0.0.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkModeConfig(autoPublishAddr = ":::")
        }
        assertEquals("::1", NetworkModeConfig(autoPublishAddr = "::1").autoPublishAddr)
    }
}
