package dev.gfire.termvr.network

import android.content.ContentResolver
import android.database.CharArrayBuffer
import android.database.ContentObserver
import android.database.Cursor
import android.database.DataSetObserver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import dev.gfire.termvr.core.data.AppPersistenceState
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.data.RungvVolumeLsEntry
import dev.gfire.termvr.core.data.SecretStore
import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.NetBackend
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.runtime.HostPtyHandle
import dev.gfire.termvr.core.runtime.MuxSessionClient
import dev.gfire.termvr.core.runtime.PortConflictException
import dev.gfire.termvr.core.runtime.ProxyTestResult
import dev.gfire.termvr.core.runtime.PublishedPortMapping
import dev.gfire.termvr.runtime.ClipboardReadPrompt
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.network.NetworkViewModel
import dev.gfire.termvr.ui.network.extractSafDocumentMetadata
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkViewModelResilienceTest {

    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `extractSafDocumentMetadata reads OpenableColumns DISPLAY_NAME and SIZE and handles missing values`() {
        val populated = FakeSafCursor(
            rows = listOf(
                mapOf(
                    OpenableColumns.DISPLAY_NAME to "corp-seoul.conf",
                    OpenableColumns.SIZE to 1024L,
                ),
            ),
        )
        val meta = extractSafDocumentMetadata(populated)
        assertEquals("corp-seoul.conf", meta.displayName)
        assertEquals(1024L, meta.sizeBytes)

        val empty = FakeSafCursor(rows = emptyList())
        val emptyMeta = extractSafDocumentMetadata(empty)
        assertNull(emptyMeta.displayName)
        assertNull(emptyMeta.sizeBytes)

        val negativeSize = FakeSafCursor(
            rows = listOf(mapOf(OpenableColumns.DISPLAY_NAME to null, OpenableColumns.SIZE to -1L)),
        )
        val negMeta = extractSafDocumentMetadata(negativeSize)
        assertNull(negMeta.displayName)
        assertNull(negMeta.sizeBytes)
    }

    @Test
    fun `sanitizeWireGuardConfigName strips conf extension, blocks traversal, and falls back when blank`() {
        assertEquals(
            "mullvad-kr1",
            NetworkViewModel.sanitizeWireGuardConfigName("mullvad-kr1.conf", "wg-fallback"),
        )
        assertEquals(
            "Mullvad-Sweden-1",
            NetworkViewModel.sanitizeWireGuardConfigName("Mullvad Sweden (1).CONF", "wg-fallback"),
        )
        assertEquals(
            "office",
            NetworkViewModel.sanitizeWireGuardConfigName("../../etc/wireguard/office.conf", "wg-fallback"),
        )
        assertEquals(
            "wg-fallback",
            NetworkViewModel.sanitizeWireGuardConfigName("   ", "wg-fallback"),
        )
        assertEquals(
            "wg-fallback",
            NetworkViewModel.sanitizeWireGuardConfigName(".conf", "wg-fallback"),
        )
        assertEquals(
            "wg-fallback",
            NetworkViewModel.sanitizeWireGuardConfigName("..", "wg-fallback"),
        )
        val longName = "a".repeat(90) + ".conf"
        assertEquals(64, NetworkViewModel.sanitizeWireGuardConfigName(longName, "wg-fallback").length)
    }

    @Test
    fun `WireGuard SAF stream import enforces exact 64 KiB boundary, closes streams, and surfaces errors`() =
        runTest(testDispatcher) {
            val repo = ProfileRepository(LocalDataStore(), tempDir, LocalSecretStore())
            val gateway = StubNetworkGateway(tempDir)
            val profile = SandboxProfile(id = "wg-box", displayName = "WG Box", imageRef = "alpine:3.21")
            repo.upsertProfile(profile)

            val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
            val vm = NetworkViewModel(repo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
            advanceUntilIdle()

            // 1. Exact 64 KiB (65536 bytes) WireGuard config succeeds and preserves sanitized filename.
            val header = "[Interface]\nPrivateKey = AAAA\n[Peer]\nPublicKey = BBBB\n#"
            val exact64KiB = header + "x".repeat(NetworkViewModel.MAX_WIREGUARD_CONFIG_BYTES - header.length)
            assertEquals(65_536, exact64KiB.toByteArray(StandardCharsets.UTF_8).size)

            val closedExact = AtomicBoolean(false)
            vm.importWireGuardConfigFromStream(
                rawDisplayName = "Mullvad Sweden (1).conf",
                declaredSizeBytes = 65_536L,
                openStream = {
                    TrackingInputStream(exact64KiB.toByteArray(StandardCharsets.UTF_8), closedExact)
                },
            )
            awaitCondition { vm.uiState.value.selectedProfile?.network?.wireGuardConfigName == "Mullvad-Sweden-1" }

            assertTrue(closedExact.get(), "InputStream must be closed after import")
            assertNull(vm.uiState.value.errorMessage)
            assertEquals(NetBackend.WIREGUARD, vm.uiState.value.selectedProfile?.network?.backend)
            assertTrue(vm.uiState.value.wireGuardConfigs.contains("Mullvad-Sweden-1"))
            assertFalse(vm.uiState.value.wireGuardConfigs.any { it.endsWith(".conf") })

            // 2. Declared size of 65537 bytes (> 64 KiB) rejects BEFORE opening the stream.
            val openedOversizedDeclared = AtomicBoolean(false)
            vm.importWireGuardConfigFromStream(
                rawDisplayName = "too-big-declared.conf",
                declaredSizeBytes = 65_537L,
                openStream = {
                    openedOversizedDeclared.set(true)
                    ByteArrayInputStream(ByteArray(65_537))
                },
            )
            advanceUntilIdle()
            assertFalse(openedOversizedDeclared.get(), "Stream should not open when declaredSizeBytes exceeds 64 KiB")
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("64 KiB"))
            assertFalse(vm.uiState.value.wireGuardConfigs.contains("too-big-declared"))

            // 3. Unknown declared size (null) with 65537-byte stream rejects at 64 KiB + 1 and closes stream.
            val closedOversizedStream = AtomicBoolean(false)
            val oversizedBytes = (header + "y".repeat(65_600)).toByteArray(StandardCharsets.UTF_8)
            vm.importWireGuardConfigFromStream(
                rawDisplayName = "too-big-stream.conf",
                declaredSizeBytes = null,
                openStream = { TrackingInputStream(oversizedBytes, closedOversizedStream) },
            )
            advanceUntilIdle()
            assertTrue(closedOversizedStream.get(), "Oversized InputStream must still be closed")
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("64 KiB"))
            assertFalse(vm.uiState.value.wireGuardConfigs.contains("too-big-stream"))

            // 4. Unreadable null stream and SAF query error report clear UI errors.
            vm.importWireGuardConfigFromStream(
                rawDisplayName = "broken.conf",
                declaredSizeBytes = null,
                openStream = { null },
            )
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.errorMessage)

            vm.reportWireGuardImportError("Permission denied reading SAF URI")
            advanceUntilIdle()
            assertEquals("Permission denied reading SAF URI", vm.uiState.value.errorMessage)

            // 5. Direct importWireGuardConfig also enforces 64 KiB limit.
            vm.importWireGuardConfig("huge.conf", header + "z".repeat(65_600))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("64 KiB"))

            vmScope.cancel()
        }

    @Test
    fun `addPortRule blocks cross-profile port conflict against running container and duplicate self rule`() =
        runTest(testDispatcher) {
            val repo = ProfileRepository(LocalDataStore(), tempDir, LocalSecretStore())
            val gateway = StubNetworkGateway(tempDir)

            val docker = SandboxProfile(
                id = "docker-engine",
                displayName = "Docker Engine",
                imageRef = "docker:28-dind",
                portForwards = listOf(
                    PortForwardRule("d-web", ForwardProtocol.TCP, ListenHost.LAN_ALL_V4, 8080, 8080, 80, 80),
                ),
            )
            val web = SandboxProfile(
                id = "web-dev",
                displayName = "Web Dev",
                imageRef = "debian:trixie-slim",
            )
            repo.upsertProfile(docker)
            repo.upsertProfile(web)
            gateway.runStates.value = mapOf(
                "docker-engine" to SandboxRunState.RUNNING,
                "web-dev" to SandboxRunState.RUNNING,
            )
            gateway.publishedPortsByProfile.value = mapOf(
                "docker-engine" to listOf(
                    PublishedPortMapping("127.0.0.1", 49155, 5000, ForwardProtocol.TCP),
                ),
            )

            val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
            val vm = NetworkViewModel(repo, gateway, scope = vmScope)
            advanceUntilIdle()

            vm.selectProfile("web-dev")
            advanceUntilIdle()

            // UNIX_STREAM on numeric port form is rejected cleanly with errorMessage instead of throwing IllegalArgumentException.
            assertFalse(vm.validateCandidatePortRule(ForwardProtocol.UNIX_STREAM, ListenHost.LOOPBACK_V4, 8080, 80))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("TCP and UDP"))
            vm.addPortRule(ForwardProtocol.UNIX_STREAM, ListenHost.LOOPBACK_V4, 8082, 82)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("TCP and UDP"))

            // LOOPBACK_V4 8080 conflicts with running Docker Engine's LAN_ALL_V4 8080.
            vm.addPortRule(ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 8080, 3000)
            advanceUntilIdle()

            val conflictMsg = vm.uiState.value.errorMessage
            assertNotNull(conflictMsg, "Expected cross-profile port conflict error")
            assertTrue(conflictMsg!!.contains("8080"), "Expected port 8080 in error: $conflictMsg")
            assertTrue(conflictMsg.contains("Docker Engine"), "Expected container name in error: $conflictMsg")
            assertNull(vm.uiState.value.stagedPortApply)
            assertTrue(vm.uiState.value.selectedProfile!!.portForwards.isEmpty())

            // Ephemeral published port 49155 on Docker Engine is also detected as a conflict.
            assertFalse(vm.validateCandidatePortRule(ForwardProtocol.TCP, ListenHost.LAN_ALL_V4, 49155, 80))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("49155"))

            // Non-conflicting port 8081 stages cleanly, and adding duplicate 8081 on same container is rejected.
            vm.addPortRule(ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 8081, 3000)
            advanceUntilIdle()
            assertNull(vm.uiState.value.errorMessage)
            assertEquals(1, vm.uiState.value.selectedProfile!!.portForwards.size)

            vm.addPortRule(ForwardProtocol.TCP, ListenHost.LAN_ALL_V4, 8081, 4000)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.errorMessage)
            assertEquals(1, vm.uiState.value.selectedProfile!!.portForwards.size)

            vmScope.cancel()
        }

    @Test
    fun `confirmStagedPortApply rolls back persisted rules and surfaces error when restore port bind fails`() =
        runTest(testDispatcher) {
            val repo = ProfileRepository(LocalDataStore(), tempDir, LocalSecretStore())
            val gateway = StubNetworkGateway(tempDir)
            val existingRule = PortForwardRule("r3000", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 3000, 3000, 3000, 3000)
            val profile = SandboxProfile(
                id = "app-srv",
                displayName = "App Server",
                imageRef = "debian:trixie-slim",
                portForwards = listOf(existingRule),
            )
            repo.upsertProfile(profile)
            gateway.runStates.value = mapOf("app-srv" to SandboxRunState.RUNNING)
            gateway.applyFailure = PortConflictException(
                conflictingPort = 9090,
                protocol = ForwardProtocol.TCP,
                message = "Host port 9090 is already in use (address already in use). Choose another host port or stop the conflicting process.",
            )

            val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
            val vm = NetworkViewModel(repo, gateway, scope = vmScope)
            advanceUntilIdle()

            vm.addPortRule(ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 9090, 90)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.stagedPortApply)

            vm.confirmStagedPortApply(checkpointAndRestore = true)
            advanceUntilIdle()

            val err = vm.uiState.value.errorMessage
            assertNotNull(err)
            assertTrue(err!!.contains("9090"))
            val persistedAfterFailure = repo.getProfile("app-srv")!!.portForwards
            assertEquals(listOf(existingRule), persistedAfterFailure)

            vmScope.cancel()
        }

    private fun kotlinx.coroutines.test.TestScope.awaitCondition(
        timeoutMs: Long = 3_000L,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (predicate()) break
            Thread.sleep(10L)
        }
        advanceUntilIdle()
    }
}

private class TrackingInputStream(
    bytes: ByteArray,
    private val closedFlag: AtomicBoolean,
) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun close() {
        closedFlag.set(true)
        delegate.close()
    }
}

private class FakeSafCursor(
    private val rows: List<Map<String, Any?>>,
) : Cursor {
    private val columns = listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    private var pos = -1

    override fun moveToFirst(): Boolean {
        if (rows.isEmpty()) return false
        pos = 0
        return true
    }

    override fun getColumnIndex(columnName: String?): Int = columns.indexOf(columnName)
    override fun isNull(columnIndex: Int): Boolean = rows[pos][columns[columnIndex]] == null
    override fun getString(columnIndex: Int): String? = rows[pos][columns[columnIndex]] as? String
    override fun getLong(columnIndex: Int): Long = (rows[pos][columns[columnIndex]] as? Number)?.toLong() ?: 0L
    override fun close() = Unit
    override fun isClosed(): Boolean = false
    override fun getCount(): Int = rows.size
    override fun getPosition(): Int = pos
    override fun move(offset: Int): Boolean = false
    override fun moveToPosition(position: Int): Boolean = false
    override fun moveToLast(): Boolean = false
    override fun moveToNext(): Boolean = false
    override fun moveToPrevious(): Boolean = false
    override fun isFirst(): Boolean = pos == 0
    override fun isLast(): Boolean = pos == rows.lastIndex
    override fun isBeforeFirst(): Boolean = pos < 0
    override fun isAfterLast(): Boolean = pos >= rows.size
    override fun getColumnIndexOrThrow(columnName: String?): Int = getColumnIndex(columnName)
    override fun getColumnName(columnIndex: Int): String = columns[columnIndex]
    override fun getColumnNames(): Array<String> = columns.toTypedArray()
    override fun getColumnCount(): Int = columns.size
    override fun getBlob(columnIndex: Int): ByteArray = ByteArray(0)
    override fun copyStringToBuffer(columnIndex: Int, buffer: CharArrayBuffer?) = Unit
    override fun getShort(columnIndex: Int): Short = 0
    override fun getInt(columnIndex: Int): Int = 0
    override fun getFloat(columnIndex: Int): Float = 0f
    override fun getDouble(columnIndex: Int): Double = 0.0
    override fun getType(columnIndex: Int): Int = Cursor.FIELD_TYPE_STRING
    @Deprecated("Deprecated in Java")
    override fun deactivate() = Unit
    @Deprecated("Deprecated in Java")
    override fun requery(): Boolean = false
    override fun registerContentObserver(observer: ContentObserver?) = Unit
    override fun unregisterContentObserver(observer: ContentObserver?) = Unit
    override fun registerDataSetObserver(observer: DataSetObserver?) = Unit
    override fun unregisterDataSetObserver(observer: DataSetObserver?) = Unit
    override fun setNotificationUri(cr: ContentResolver?, uri: Uri?) = Unit
    override fun getNotificationUri(): Uri? = null
    override fun getWantsAllOnMoveCalls(): Boolean = false
    override fun setExtras(extras: Bundle?) = Unit
    override fun getExtras(): Bundle = Bundle.EMPTY
    override fun respond(extras: Bundle?): Bundle = Bundle.EMPTY
}

private class LocalDataStore : DataStore<AppPersistenceState> {
    private val state = MutableStateFlow(AppPersistenceState())
    private val mutex = Mutex()
    override val data: Flow<AppPersistenceState> = state
    override suspend fun updateData(transform: suspend (t: AppPersistenceState) -> AppPersistenceState): AppPersistenceState =
        mutex.withLock {
            val next = transform(state.value)
            state.value = next
            next
        }
}

private class LocalSecretStore : SecretStore {
    private val secrets = ConcurrentHashMap<String, CharArray>()
    override fun putSecret(alias: String, secret: CharArray) {
        secrets[alias] = secret.copyOf()
    }

    override fun getSecret(alias: String): CharArray? = secrets[alias]?.copyOf()
    override fun deleteSecret(alias: String) {
        secrets.remove(alias)
    }

    override fun containsAlias(alias: String): Boolean = secrets.containsKey(alias)
}

private class StubNetworkGateway(private val rootDir: File) : SandboxRuntimeGateway {
    override val runStates = MutableStateFlow<Map<String, SandboxRunState>>(emptyMap())
    override val telemetryByProfile = MutableStateFlow<Map<String, SandboxTelemetrySnapshot>>(emptyMap())
    override val publishedPortsByProfile = MutableStateFlow<Map<String, List<PublishedPortMapping>>>(emptyMap())
    override val lanReconfirmationRequired = MutableStateFlow<Set<String>>(emptySet())
    override val wakeLockRemainingSeconds = MutableStateFlow<Long?>(null)
    override val notificationsDeniedBanner = MutableStateFlow(false)
    override val hostReadiness = MutableStateFlow(HostReadinessReport(hostSdkInt = 35, hostAbi = "arm64-v8a"))
    override val activeServiceNotice = MutableStateFlow<String?>(null)
    override val clipboardReadPrompt = MutableStateFlow<ClipboardReadPrompt?>(null)
    override val dnsProxyPort: Int = 5353

    var applyFailure: Exception? = null

    override fun refreshHostReadiness() = Unit
    override fun setNotificationsPermissionGranted(granted: Boolean) = Unit
    override fun clearNotice() {
        activeServiceNotice.value = null
    }

    override suspend fun startOrResumeSandbox(profile: SandboxProfile, reconfirmLan: Boolean): SandboxRunState {
        runStates.value = runStates.value + (profile.id to SandboxRunState.RUNNING)
        return SandboxRunState.RUNNING
    }

    override suspend fun freezeSandbox(profileId: String): SandboxRunState {
        runStates.value = runStates.value + (profileId to SandboxRunState.FROZEN)
        return SandboxRunState.FROZEN
    }

    override suspend fun stopSandbox(profileId: String) {
        runStates.value = runStates.value + (profileId to SandboxRunState.STOPPED)
    }

    override suspend fun stopAllSandboxes() = Unit
    override suspend fun updateOutboundProxyLive(profileId: String, outboundProxy: OutboundProxyConfig) = Unit

    override suspend fun applyPortForwards(
        profileId: String,
        updatedRules: List<PortForwardRule>,
        checkpointAndRestore: Boolean,
    ): SandboxRunState {
        applyFailure?.let { throw it }
        return SandboxRunState.RUNNING
    }

    override suspend fun acquireWakeLock(minutes: Int): Int = minutes
    override suspend fun releaseWakeLock() = Unit
    override suspend fun discardCheckpoint(profileId: String): Boolean = true
    override suspend fun hasActiveCheckpoint(profileId: String): Boolean = false
    override suspend fun testProxy(config: OutboundProxyConfig): ProxyTestResult =
        ProxyTestResult(true, 5L, config.mode, "OK")

    override suspend fun listVolumes(): List<RungvVolumeLsEntry> = emptyList()
    override suspend fun createVolume(name: String) = Unit
    override suspend fun removeVolume(name: String, force: Boolean) = Unit
    override suspend fun <T> withVolumeClient(
        profile: SandboxProfile,
        volumeName: String,
        readOnly: Boolean,
        isRootfsVolume: Boolean,
        block: suspend (MuxSessionClient, String) -> T,
    ): T = throw UnsupportedOperationException()

    override fun getMuxClient(profileId: String): MuxSessionClient? = null
    override fun getRawPtyHandle(profileId: String): HostPtyHandle? = null
    override fun resizeRawPty(profileId: String, rows: Int, cols: Int, xpixel: Int, ypixel: Int) = Unit
    override fun markProfileActivity(profileId: String) = Unit
    override fun resolveClipboardPrompt(allow: Boolean) = Unit
    override fun buildRungvHostEnv(trustHostCaCerts: Boolean, includeProxyVars: Boolean): Map<String, String> = emptyMap()
    override fun workspaceDir(profileId: String): File = File(rootDir, "workspaces/$profileId").apply { mkdirs() }
    override fun activeWifiIpv4(): String = "192.168.1.20"
    override fun openUrl(url: String) = Unit
    override fun shareText(text: String) = Unit
    override fun shareNamedFile(fileName: String, content: ByteArray) = Unit
    override fun startForegroundAction(action: String) = Unit
}
