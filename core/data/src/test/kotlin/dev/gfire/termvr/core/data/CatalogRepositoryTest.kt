package dev.gfire.termvr.core.data

import dev.gfire.termvr.core.model.GuestLinuxArch
import dev.gfire.termvr.core.model.VolumeLockMode
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogRepositoryTest {
    @TempDir
    lateinit var tempDir: File

    private val localAssets = AssetSource { assetPath ->
        val candidates = listOf(
            File("src/main/assets/$assetPath"),
            File("core/data/src/main/assets/$assetPath"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Missing recipe asset in test workspace: $assetPath")
        file.inputStream()
    }

    @Test
    fun `INV-C1 Bundled Docker DinD k3s and Calico VXLAN assets match Section 16_2 contracts`() {
        // Property: Bundled recipes parse cleanly and contain exact RAM/disk floors, daemon argv, and 5 Calico overrides.
        // Seam: CatalogRepository.loadRecipes, loadRecipe, and loadRecipeAssetText.
        // Oracle: Design 0001 Section 16.2 recipe specifications.
        // Fault detected: missing Calico VXLAN override, wrong RAM/disk floor, or missing DOCKER_TLS_CERTDIR="".
        val repo = CatalogRepository(tempDir, localAssets)
        val recipes = repo.loadRecipes()
        assertEquals(2, recipes.size)

        val docker = repo.loadRecipe("docker-dind")!!
        assertEquals("docker:28-dind", docker.imageRef)
        assertEquals(0, docker.minHostRamMiB)
        assertEquals(0, docker.defaultProfileRamMiB)
        assertEquals(0, docker.minFreeDiskMiB)
        assertEquals("docker", docker.volumeTemplateSuffix)
        assertEquals("/var/lib/docker", docker.volumeGuestMountPath)
        assertEquals("", docker.envVars["DOCKER_TLS_CERTDIR"])
        assertEquals("1", docker.envVars["DOCKER_IPTABLES_LEGACY"])
        assertEquals("unix:///var/run/docker.sock", docker.envVars["DOCKER_HOST"])
        assertFalse(docker.defaultAutoCheckpoint)
        assertEquals(2375, docker.readinessTcpPort)
        assertEquals(
            listOf(
                "dockerd-entrypoint.sh",
                "--tls=false",
                "--userland-proxy=false",
            ),
            docker.initDaemonArgv,
        )

        val k3s = repo.loadRecipe("k3s-single-node")!!
        assertEquals("rancher/k3s:v1.32.2-k3s1", k3s.imageRef)
        assertEquals(0, k3s.minHostRamMiB)
        assertEquals(0, k3s.defaultProfileRamMiB)
        assertEquals(0, k3s.minFreeDiskMiB)
        assertEquals("k3s", k3s.volumeTemplateSuffix)
        assertEquals("/var/lib/rancher", k3s.volumeGuestMountPath)
        assertTrue(k3s.makeRootRshared)
        assertEquals(6443, k3s.readinessTcpPort)
        assertEquals(null, k3s.optionalVariantMinProfileRamMiB)
        assertEquals("legacy", k3s.envVars["IPTABLES_MODE"])
        assertEquals("/workspace/k3s/kubeconfig.yaml", k3s.envVars["KUBECONFIG"])
        assertTrue(k3s.initDaemonArgv.contains("--flannel-backend=host-gw"))
        assertTrue(k3s.initDaemonArgv.contains("--disable=traefik,servicelb"))
        assertTrue(k3s.initDaemonArgv.contains("--write-kubeconfig=/workspace/k3s/kubeconfig.yaml"))
        assertFalse(k3s.initDaemonArgv.contains("--snapshotter=native"))
        assertTrue(k3s.optionalVariantInitDaemonArgv.contains("--flannel-backend=none"))
        assertTrue(k3s.optionalVariantInitDaemonArgv.contains("--disable-network-policy"))
        assertTrue(k3s.optionalVariantInitDaemonArgv.contains("--cluster-cidr=10.42.0.0/16"))
        assertTrue(k3s.optionalVariantInitDaemonArgv.contains("--disable=traefik,servicelb"))
        assertTrue(k3s.optionalVariantInitDaemonArgv.contains("--write-kubeconfig=/workspace/k3s/kubeconfig.yaml"))

        val calicoYaml = repo.loadRecipeAssetText(k3s.optionalAssetManifest!!)
        assertTrue(calicoYaml.contains("CALICO_IPV4POOL_VXLAN"))
        assertTrue(calicoYaml.contains("\"Always\""))
        assertTrue(calicoYaml.contains("FELIX_BPFENABLED"))
        assertTrue(calicoYaml.contains("FELIX_XDPENABLED"))
        assertTrue(calicoYaml.contains("CALICO_DISABLE_FILE_LOGGING"))
        assertTrue(calicoYaml.contains("veth_mtu: \"1450\""))
    }

    @Test
    fun `INV-C2 Curated distribution catalog filters strictly by host ABI`() {
        // Property: arm64-v8a returns only arm64 entries; x86_64 returns only amd64 entries; unsupported ABI returns empty.
        // Seam: CatalogRepository.getCuratedDistributions.
        // Oracle: Design 0001 Section 3.2 NG6 and Section 16.2.
        // Fault detected: amd64 image offered on arm64-v8a device.
        val repo = CatalogRepository(tempDir, localAssets)
        val arm64 = repo.getCuratedDistributions("arm64-v8a")
        val amd64 = repo.getCuratedDistributions("x86_64")

        assertEquals(4, arm64.size)
        assertEquals("debian-trixie", arm64.first().id)
        assertTrue(arm64.all { it.architecture == GuestLinuxArch.ARM64 })
        assertEquals(4, amd64.size)
        assertEquals("debian-trixie", amd64.first().id)
        assertTrue(amd64.all { it.architecture == GuestLinuxArch.AMD64 })
        assertTrue(repo.getCuratedDistributions("x86").isEmpty())
    }

    @Test
    fun `INV-C3 Kubeconfig endpoint rewriter updates cluster server to loopback 6443`() {
        // Property: rewriteKubeconfigEndpoint replaces any http/https cluster server URL with https://127.0.0.1:6443.
        // Seam: CatalogRepository.rewriteKubeconfigEndpoint.
        // Oracle: Design 0001 Section 16.2 k3s kubeconfig export contract.
        // Fault detected: retaining container-internal IP 10.42.0.1 or stripping YAML indentation.
        val repo = CatalogRepository(tempDir, localAssets)
        val raw = """
            apiVersion: v1
            clusters:
            - cluster:
                certificate-authority-data: LS0tLS1CRUdJTi0tLS0t
                server: https://10.42.0.15:6443
              name: default
        """.trimIndent()

        val rewritten = repo.rewriteKubeconfigEndpoint(raw)
        assertTrue(rewritten.contains("    server: https://127.0.0.1:6443"))
        assertFalse(rewritten.contains("10.42.0.15"))
        assertTrue(rewritten.contains("certificate-authority-data: LS0tLS1CRUdJTi0tLS0t"))
    }

    @Test
    fun `INV-C4 OCI registry auth writes 0600 docker config json and rejects unauthorized responses`() = runTest {
        // Property: Successful /v2/ auth writes <filesDir>/gvx-home/.docker/config.json with 0600 permissions; 401 fails.
        // Seam: CatalogRepository.authenticateOciRegistry.
        // Oracle: Design 0001 Section 15.1 and Section 16.2 registry auth contract.
        // Fault detected: world-readable config.json, missing Basic auth header, or silent acceptance of HTTP 401.
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val expectedToken = Base64.getEncoder().encodeToString("alice:secret-tok".toByteArray())
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                client.use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    var authHeader: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            authHeader = line.substringAfter(':').trim()
                        }
                    }
                    val statusLine = if (authHeader == "Basic $expectedToken") {
                        "HTTP/1.1 200 OK"
                    } else {
                        "HTTP/1.1 401 Unauthorized"
                    }
                    val resp = "$statusLine\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().write(resp.toByteArray())
                    sock.getOutputStream().flush()
                }
            }
        }
        try {
            val repo = CatalogRepository(tempDir, localAssets)
            val host = "127.0.0.1:${server.localPort}"
            val result = repo.authenticateOciRegistry(
                registryHost = host,
                username = "alice",
                password = "secret-tok".toCharArray(),
                scheme = "http",
                allowInsecureTestScheme = true,
            )
            assertEquals(200, result.statusCode)
            val expectedFile = File(tempDir, "gvx-home/.docker/config.json")
            assertEquals(expectedFile.canonicalPath, result.configFile.canonicalPath)
            assertTrue(expectedFile.readText().contains(expectedToken))

            val perms = Files.getPosixFilePermissions(expectedFile.toPath())
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                perms,
            )

            var rejected = false
            try {
                repo.authenticateOciRegistry(
                    registryHost = host,
                    username = "alice",
                    password = "wrong".toCharArray(),
                    scheme = "http",
                    allowInsecureTestScheme = true,
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected, "Expected HTTP 401 to throw IllegalArgumentException")
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `INV-C5 Rungv NDJSON and JSON-array parsers plus online-only image save flags match Section 16_2`() = runTest {
        // Property: system df and volume ls parse NDJSON; volume inspect parses JSON array; offline image save fails.
        // Seam: CatalogRepository.systemDf, listVolumes, inspectVolume, buildPullArgs, buildImageSaveArgs.
        // Oracle: Design 0001 Section 16.2 CLI & output format contracts.
        // Fault detected: parsing NDJSON as a single JSON array, omitting -f on prune, or allowing offline image save.
        val recordedCommands = mutableListOf<List<String>>()
        val repo = CatalogRepository(
            filesDir = tempDir,
            assetSource = localAssets,
            commandExecutor = { args, _ ->
                recordedCommands += args
                when {
                    args.take(2) == listOf("system", "df") -> RungvCommandResult(
                        0,
                        """
                        {"Type":"Images","TotalCount":2,"Active":1,"Size":"120MB","Reclaimable":"45MB"}
                        {"Type":"Volumes","TotalCount":3,"Active":2,"Size":"1.2GB","Reclaimable":"0B"}
                        """.trimIndent(),
                        "",
                    )
                    args.take(2) == listOf("volume", "ls") -> RungvCommandResult(
                        0,
                        """
                        {"Name":"docker-dev","Driver":"local","Mountpoint":"/volumes/docker-dev","SizeBytes":536870912}
                        {"Name":"k3s-dev","Driver":"local","Mountpoint":"/volumes/k3s-dev","SizeBytes":1073741824}
                        """.trimIndent(),
                        "",
                    )
                    args.take(2) == listOf("volume", "inspect") -> RungvCommandResult(
                        0,
                        """[{"Name":"docker-dev","Driver":"local","Mountpoint":"/volumes/docker-dev","CreatedAt":"2026-11-15T00:00:00Z"}]""",
                        "",
                    )
                    else -> RungvCommandResult(0, "", "")
                }
            },
        )

        val dfEntries = repo.systemDf()
        assertEquals(2, dfEntries.size)
        assertEquals("Images", dfEntries[0].type)
        assertEquals("Volumes", dfEntries[1].type)

        val volumes = repo.listVolumes()
        assertEquals(2, volumes.size)
        assertEquals("docker-dev", volumes[0].name)
        assertEquals(1073741824L, volumes[1].sizeBytes)

        val inspected = repo.inspectVolume("docker-dev")
        assertEquals(1, inspected.size)
        assertEquals("docker-dev", inspected[0].name)

        repo.pullImage("alpine:3.21", dnsPort = 15353)
        repo.pullImage("alpine:3.21", dnsPort = 15353, keepBlobs = true)
        repo.loadImageArchive("/tmp/alpine.tar")
        repo.loadImageArchive("/tmp/alpine.tar", keepBlobs = true)
        repo.saveImageArchive("alpine:3.21", "/tmp/out.tar", dnsPort = 15353, isOnline = true)
        repo.saveImageArchive("alpine:3.21", "/tmp/offline.tar", dnsPort = 15353, isOnline = false, allowOffline = true)
        repo.pushImage("alpine:3.21", targetRef = "ghcr.io/gfire-sigs/alpine:3.21", dnsPort = 15353)
        repo.pushImage("alpine:3.21", dnsPort = 15353)
        repo.systemPrune()
        repo.volumePrune()

        assertTrue(recordedCommands.contains(listOf("pull", "--host-dns", "127.0.0.1:15353", "alpine:3.21")))
        assertTrue(recordedCommands.contains(listOf("pull", "-keep-blobs", "--host-dns", "127.0.0.1:15353", "alpine:3.21")))
        assertTrue(recordedCommands.contains(listOf("image", "load", "-i", "/tmp/alpine.tar")))
        assertTrue(recordedCommands.contains(listOf("image", "load", "-keep-blobs", "-i", "/tmp/alpine.tar")))
        assertTrue(
            recordedCommands.contains(
                listOf("image", "save", "--host-dns", "127.0.0.1:15353", "-o", "/tmp/out.tar", "alpine:3.21"),
            ),
        )
        assertTrue(
            recordedCommands.contains(
                listOf("image", "save", "-o", "/tmp/offline.tar", "alpine:3.21"),
            ),
        )
        assertTrue(
            recordedCommands.contains(
                listOf("image", "push", "--host-dns", "127.0.0.1:15353", "alpine:3.21", "ghcr.io/gfire-sigs/alpine:3.21"),
            ),
        )
        assertTrue(
            recordedCommands.contains(
                listOf("image", "push", "--host-dns", "127.0.0.1:15353", "alpine:3.21"),
            ),
        )
        assertTrue(recordedCommands.contains(listOf("system", "prune", "-f")))
        assertTrue(recordedCommands.contains(listOf("volume", "prune", "-f")))

        assertThrows(IllegalStateException::class.java) {
            CatalogRepository.buildImageSaveArgs("alpine:3.21", "/tmp/out.tar", dnsPort = 15353, isOnline = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRepository.buildImagePushArgs("", dnsPort = 15353)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRepository.buildImagePushArgs("alpine:3.21", dnsPort = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRepository.buildPullArgs("--registry-url=https://evil.example", dnsPort = 15353)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRepository.buildImagePushArgs("alpine:3.21", targetRef = "-q", dnsPort = 15353)
        }
    }

    @Test
    fun `INV-C6 CatalogRepository validates and executes volume create and volume rm lifecycle`() = runTest {
        val recordedCommands = mutableListOf<List<String>>()
        var nextExitCode = 0
        val repo = CatalogRepository(
            filesDir = tempDir,
            assetSource = localAssets,
            commandExecutor = { args, _ ->
                recordedCommands += args
                RungvCommandResult(nextExitCode, "", if (nextExitCode == 0) "" else "volume error")
            },
        )

        repo.createVolume("workspace_data.01")
        repo.removeVolume("workspace_data.01")
        repo.removeVolume("workspace_data.01", force = true)

        assertEquals(
            listOf(
                listOf("volume", "create", "workspace_data.01"),
                listOf("volume", "rm", "workspace_data.01"),
                listOf("volume", "rm", "-f", "workspace_data.01"),
            ),
            recordedCommands,
        )

        for (badName in listOf("", ".", "..", "UpperCase", "bad/vol", "bad name", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) {
                CatalogRepository.buildVolumeCreateArgs(badName)
            }
            assertThrows(IllegalArgumentException::class.java) {
                CatalogRepository.buildVolumeRmArgs(badName, force = true)
            }
        }

        nextExitCode = 1
        var createFailed = false
        try {
            repo.createVolume("valid-vol")
        } catch (_: IllegalStateException) {
            createFailed = true
        }
        assertTrue(createFailed)

        var removeFailed = false
        try {
            repo.removeVolume("valid-vol")
        } catch (_: IllegalStateException) {
            removeFailed = true
        }
        assertTrue(removeFailed)
    }

    @Test
    fun `INV-C7 Volume parsers decode AllocatedBytes and LockMode with backwards compatibility and compactVolume runs -cache-dir`() = runTest {
        // Property: AllocatedBytes defaults to SizeBytes when absent in legacy NDJSON/inspect JSON and preserves sparse values when present;
        // LockMode resolves "none" | "shared" | "exclusive" or falls back to InUse; compactVolume passes <name> -cache-dir <filesDir>/gvx-store/gvx
        // and returns Result.failure when volume is in use or invalid.
        // Seam: CatalogRepository.listVolumes, inspectVolume, compactVolume, buildVolumeCompactArgs.
        // Oracle: Phase 1 gvx volume ls/inspect/compact CLI contract.
        // Fault detected: Reporting 0 B allocated on legacy JSON, misrouting -cache-dir to <filesDir>/gvx-store instead of <filesDir>/gvx-store/gvx,
        // or throwing uncaught exceptions from compactVolume when InUse.
        val recordedCommands = mutableListOf<List<String>>()
        var compactExitCode = 0
        var compactStderr = ""
        val repo = CatalogRepository(
            filesDir = tempDir,
            assetSource = localAssets,
            commandExecutor = { args, _ ->
                recordedCommands += args
                when {
                    args.take(2) == listOf("volume", "ls") -> RungvCommandResult(
                        0,
                        """
                        {"Name":"legacy-vol","Driver":"ext4lite","Mountpoint":"/v/legacy","SizeBytes":536870912}
                        {"Name":"sparse-shared","Driver":"ext4lite","Mountpoint":"/v/sparse","SizeBytes":1503238553,"AllocatedBytes":134217728,"InUse":true,"LockMode":"shared"}
                        {"Name":"active-rw","Driver":"ext4lite","Mountpoint":"/v/rw","SizeBytes":268435456,"AllocatedBytes":268435456,"InUse":true}
                        """.trimIndent(),
                        "",
                    )
                    args.take(2) == listOf("volume", "inspect") -> RungvCommandResult(
                        0,
                        """[{"Name":"sparse-shared","Driver":"ext4lite","Mountpoint":"/v/sparse","CreatedAt":"2026-11-17T00:00:00Z","SizeBytes":1503238553,"AllocatedBytes":134217728,"InUse":true,"LockMode":"shared"}]""",
                        "",
                    )
                    args.take(2) == listOf("volume", "compact") -> RungvCommandResult(
                        compactExitCode,
                        "",
                        compactStderr,
                    )
                    else -> RungvCommandResult(0, "", "")
                }
            },
        )

        val lsEntries = repo.listVolumes()
        assertEquals(3, lsEntries.size)

        val legacy = lsEntries[0]
        assertEquals(536870912L, legacy.sizeBytes)
        assertEquals(536870912L, legacy.allocatedBytes)
        assertEquals(VolumeLockMode.NONE, legacy.lockMode)

        val sparseShared = lsEntries[1]
        assertEquals(1503238553L, sparseShared.sizeBytes)
        assertEquals(134217728L, sparseShared.allocatedBytes)
        assertTrue(sparseShared.inUse)
        assertEquals(VolumeLockMode.SHARED, sparseShared.lockMode)

        val activeRw = lsEntries[2]
        assertEquals(268435456L, activeRw.allocatedBytes)
        assertEquals(VolumeLockMode.EXCLUSIVE, activeRw.lockMode)

        val inspected = repo.inspectVolume("sparse-shared").single()
        assertEquals(1503238553L, inspected.sizeBytes)
        assertEquals(134217728L, inspected.allocatedBytes)
        assertEquals(VolumeLockMode.SHARED, inspected.lockMode)

        val expectedCacheDir = File(tempDir, "gvx-store/gvx").absolutePath
        val okCompact = repo.compactVolume("legacy-vol")
        assertTrue(okCompact.isSuccess)
        assertTrue(
            recordedCommands.contains(
                listOf("volume", "compact", "legacy-vol", "-cache-dir", expectedCacheDir),
            ),
        )

        compactExitCode = 1
        compactStderr = "rungv volume compact: gvx: volume is in use"
        val busyCompact = repo.compactVolume("active-rw")
        assertTrue(busyCompact.isFailure)
        assertTrue(busyCompact.exceptionOrNull()?.message?.contains("in use") == true)

        val invalidNameCompact = repo.compactVolume("../escape")
        assertTrue(invalidNameCompact.isFailure)
    }
}
