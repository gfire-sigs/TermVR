package dev.gfire.termvr.ui.catalog

import dev.gfire.termvr.core.data.CatalogRepository
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.data.RungvSystemDfEntry
import dev.gfire.termvr.core.data.RungvVolumeInspectEntry
import dev.gfire.termvr.core.data.RungvVolumeLsEntry
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.DistributionCatalogEntry
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.RecipeManifest
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.terminal.TerminalSessionCoordinator
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CatalogSubTab {
    DISTRIBUTIONS,
    RECIPES,
    LOCAL_IMAGES,
}

data class RecipePreflightStatus(
    val requiredRamMiB: Int,
    val profileRamMiB: Int = requiredRamMiB,
    val requiredDiskMiB: Long,
    val ramOk: Boolean,
    val diskOk: Boolean,
) {
    val passed: Boolean get() = ramOk && diskOk
}

data class CatalogUiState(
    val selectedTab: CatalogSubTab = CatalogSubTab.DISTRIBUTIONS,
    val distributions: List<DistributionCatalogEntry> = emptyList(),
    val recipes: List<RecipeManifest> = emptyList(),
    val hostReadiness: HostReadinessReport = HostReadinessReport(hostSdkInt = 31, hostAbi = "arm64-v8a"),
    val runStates: Map<String, SandboxRunState> = emptyMap(),
    val calicoVxlanEnabled: Boolean = false,
    val recipeProbeReady: Map<String, Boolean> = emptyMap(),
    val dfEntries: List<RungvSystemDfEntry> = emptyList(),
    val volumeEntries: List<RungvVolumeLsEntry> = emptyList(),
    val inspectedVolume: List<RungvVolumeInspectEntry> = emptyList(),
    val busyAction: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    fun preflightFor(recipe: RecipeManifest): RecipePreflightStatus {
        val profileRam = if (calicoVxlanEnabled && recipe.optionalVariantMinProfileRamMiB != null) {
            maxOf(recipe.defaultProfileRamMiB, recipe.optionalVariantMinProfileRamMiB ?: recipe.defaultProfileRamMiB)
        } else {
            recipe.defaultProfileRamMiB
        }
        val hostRamFloor = maxOf(recipe.minHostRamMiB, profileRam)
        return RecipePreflightStatus(
            requiredRamMiB = hostRamFloor,
            profileRamMiB = profileRam,
            requiredDiskMiB = recipe.minFreeDiskMiB.toLong(),
            ramOk = hostRamFloor <= 0 || hostReadiness.totalHostRamMiB <= 0 || hostReadiness.totalHostRamMiB >= hostRamFloor,
            diskOk = recipe.minFreeDiskMiB <= 0 || hostReadiness.freeDiskMiB <= 0 || hostReadiness.freeDiskMiB >= recipe.minFreeDiskMiB,
        )
    }
}

private data class CatalogLocalState(
    val selectedTab: CatalogSubTab = CatalogSubTab.DISTRIBUTIONS,
    val calicoVxlanEnabled: Boolean = false,
    val probeReady: Map<String, Boolean> = emptyMap(),
    val dfEntries: List<RungvSystemDfEntry> = emptyList(),
    val volumes: List<RungvVolumeLsEntry> = emptyList(),
    val inspected: List<RungvVolumeInspectEntry> = emptyList(),
    val busyAction: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * Coordinates curated distributions, DinD/k3s recipe launches, OCI image ops, and registry auth.
 */
class CatalogViewModel(
    private val catalogRepository: CatalogRepository,
    private val profileRepository: ProfileRepository,
    private val runtimeGateway: SandboxRuntimeGateway,
    private val sessionCoordinator: TerminalSessionCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val tcpProbe: suspend (Int) -> Boolean = { port -> probeLoopbackPort(port) },
    private val probeIntervalMs: Long? = 2_500L,
    private val readinessPollStepMs: Long = 250L,
) {
    private val distributions = runCatching { catalogRepository.getCuratedDistributions() }
        .getOrDefault(DistributionCatalogEntry.CURATED_ENTRIES)
    private val recipes = runCatching { catalogRepository.loadRecipes() }.getOrDefault(emptyList())

    private val localState = MutableStateFlow(CatalogLocalState())

    val uiState: StateFlow<CatalogUiState> = combine(
        localState,
        runtimeGateway.hostReadiness,
        runtimeGateway.runStates,
    ) { local, readiness, states ->
        CatalogUiState(
            selectedTab = local.selectedTab,
            distributions = distributions,
            recipes = recipes,
            hostReadiness = readiness,
            runStates = states,
            calicoVxlanEnabled = local.calicoVxlanEnabled,
            recipeProbeReady = local.probeReady,
            dfEntries = local.dfEntries,
            volumeEntries = local.volumes,
            inspectedVolume = local.inspected,
            busyAction = local.busyAction,
            statusMessage = local.statusMessage,
            errorMessage = local.errorMessage,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = CatalogUiState(
            distributions = distributions,
            recipes = recipes,
            hostReadiness = runtimeGateway.hostReadiness.value,
        ),
    )

    init {
        scope.launch {
            runtimeGateway.runStates.collect { runningStates ->
                refreshRecipeProbes(runningStates)
            }
        }
        if (probeIntervalMs != null) {
            scope.launch {
                while (true) {
                    delay(probeIntervalMs)
                    val runningStates = runtimeGateway.runStates.value
                    val hasRunningRecipe = recipes.any {
                        runningStates[it.id] == SandboxRunState.RUNNING
                    }
                    if (hasRunningRecipe) {
                        refreshRecipeProbes(runningStates)
                    }
                }
            }
        }
    }

    private suspend fun refreshRecipeProbes(runningStates: Map<String, SandboxRunState>) {
        val readyMap = HashMap<String, Boolean>()
        for (recipe in recipes) {
            val isRunning = runningStates[recipe.id] == SandboxRunState.RUNNING
            readyMap[recipe.id] = if (isRunning) {
                tcpProbe(recipe.readinessTcpPort)
            } else {
                false
            }
        }
        localState.value = localState.value.copy(probeReady = readyMap)
    }

    fun selectTab(tab: CatalogSubTab) {
        localState.value = localState.value.copy(selectedTab = tab)
        if (tab == CatalogSubTab.LOCAL_IMAGES && localState.value.dfEntries.isEmpty()) {
            refreshStorageStats()
        }
    }

    fun toggleCalicoVxlan() {
        localState.value = localState.value.copy(calicoVxlanEnabled = !localState.value.calicoVxlanEnabled)
    }

    fun clearBanner() {
        localState.value = localState.value.copy(statusMessage = null, errorMessage = null)
    }

    fun createAndLaunchDistribution(
        entry: DistributionCatalogEntry,
        pullFirst: Boolean,
        onOpenTerminal: () -> Unit,
    ) {
        scope.launch {
            localState.value = localState.value.copy(
                busyAction = "Launching ${entry.displayName}...",
                errorMessage = null,
                statusMessage = null,
            )
            runCatching {
                val env = runtimeGateway.buildRungvHostEnv()
                if (pullFirst) {
                    val res = catalogRepository.pullImage(
                        imageRef = entry.imageRef,
                        dnsPort = runtimeGateway.dnsProxyPort,
                        env = env,
                    )
                    if (res.exitCode != 0) {
                        error(res.stderr.ifBlank { "Image pull failed (${res.exitCode})" })
                    }
                }
                val profile = profileRepository.getProfile(entry.id)
                    ?: entry.toSandboxProfile(profileId = entry.id, profileDisplayName = entry.displayName).also {
                        profileRepository.upsertProfile(it)
                    }
                val finalState = runtimeGateway.startOrResumeSandbox(profile)
                if (finalState == SandboxRunState.RUNNING) {
                    val settings = profileRepository.observeAppSettings().first()
                    val palette = ColorPalette.byId(settings.selectedPaletteId) ?: ColorPalette.DEFAULT_DARK
                    sessionCoordinator.ensureTabsForProfile(
                        profile = profile,
                        maxScrollbackLines = settings.defaultScrollbackLines,
                        palette = palette,
                    )
                    onOpenTerminal()
                }
            }.onFailure { err ->
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Failed to launch distribution",
                )
            }
            localState.value = localState.value.copy(busyAction = null)
        }
    }

    fun launchRecipe(
        recipe: RecipeManifest,
        onOpenTerminal: () -> Unit,
    ) {
        scope.launch {
            val enableCalico = localState.value.calicoVxlanEnabled && recipe.optionalAssetManifest != null
            val requestedRam = if (enableCalico && recipe.optionalVariantMinProfileRamMiB != null) {
                maxOf(recipe.defaultProfileRamMiB, recipe.optionalVariantMinProfileRamMiB!!)
            } else {
                recipe.defaultProfileRamMiB
            }
            localState.value = localState.value.copy(
                busyAction = "Starting ${recipe.title}...",
                errorMessage = null,
                statusMessage = null,
            )
            runCatching {
                val profile = recipe.toSandboxProfile(
                    profileId = recipe.id,
                    displayName = recipe.title,
                    enableOptionalVariant = enableCalico,
                    requestedMemoryMiB = requestedRam,
                )
                profileRepository.upsertProfile(profile)

                val workspace = runtimeGateway.workspaceDir(profile.id).apply { mkdirs() }
                if (recipe.id == "k3s-single-node") {
                    File(workspace, "k3s").mkdirs()
                }
                if (enableCalico) {
                    val assetPath = recipe.optionalAssetManifest
                    if (assetPath != null) {
                        val yaml = catalogRepository.loadRecipeAssetText(assetPath)
                        File(workspace, File(assetPath).name).writeText(yaml)
                    }
                }

                val finalState = runtimeGateway.startOrResumeSandbox(profile)
                if (finalState == SandboxRunState.RUNNING) {
                    val settings = profileRepository.observeAppSettings().first()
                    val palette = ColorPalette.byId(settings.selectedPaletteId) ?: ColorPalette.DEFAULT_DARK
                    sessionCoordinator.ensureTabsForProfile(
                        profile = profile,
                        maxScrollbackLines = settings.defaultScrollbackLines,
                        palette = palette,
                        openInitialShell = false,
                    )
                    onOpenTerminal()
                    waitForRecipeReadinessAndOpenShell(
                        profile = profile,
                        readinessPort = recipe.readinessTcpPort,
                        maxScrollbackLines = settings.defaultScrollbackLines,
                        palette = palette,
                    )
                }
            }.onFailure { err ->
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Failed to start recipe",
                )
            }
            localState.value = localState.value.copy(busyAction = null)
        }
    }

    fun exportKubeconfig(profileId: String = "k3s-single-node") {
        scope.launch {
            runCatching {
                val workspace = runtimeGateway.workspaceDir(profileId)
                val candidates = listOf(
                    File(workspace, "k3s/kubeconfig.yaml"),
                    File(workspace, "kubeconfig.yaml"),
                )
                val source = candidates.firstOrNull { it.isFile }
                    ?: error("kubeconfig.yaml not found in ${workspace.absolutePath}")
                val raw = source.readText()
                val rewritten = catalogRepository.rewriteKubeconfigEndpoint(raw)
                runtimeGateway.shareNamedFile("kubeconfig.yaml", rewritten.toByteArray())
                localState.value = localState.value.copy(statusMessage = "Prepared loopback kubeconfig.yaml share.")
            }.onFailure { err ->
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Unable to export kubeconfig.yaml",
                )
            }
        }
    }

    fun pullImage(imageRef: String) {
        scope.launch {
            val clean = imageRef.trim()
            if (clean.isEmpty()) return@launch
            localState.value = localState.value.copy(
                busyAction = "Pulling $clean...",
                errorMessage = null,
                statusMessage = null,
            )
            runCatching {
                val res = catalogRepository.pullImage(
                    imageRef = clean,
                    dnsPort = runtimeGateway.dnsProxyPort,
                    env = runtimeGateway.buildRungvHostEnv(),
                )
                if (res.exitCode != 0) {
                    error(res.stderr.ifBlank { "Pull exited with status ${res.exitCode}" })
                }
                localState.value = localState.value.copy(statusMessage = "Pulled $clean.")
                refreshStorageStatsInternal()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Pull failed")
            }
            localState.value = localState.value.copy(busyAction = null)
        }
    }

    fun loadImageTar(tarFile: File) {
        scope.launch {
            localState.value = localState.value.copy(
                busyAction = "Loading image archive...",
                errorMessage = null,
                statusMessage = null,
            )
            runCatching {
                val res = catalogRepository.loadImageArchive(
                    tarPath = tarFile.absolutePath,
                    env = runtimeGateway.buildRungvHostEnv(),
                )
                if (res.exitCode != 0) {
                    error(res.stderr.ifBlank { "Image load failed (${res.exitCode})" })
                }
                localState.value = localState.value.copy(statusMessage = "Loaded OCI image archive.")
                refreshStorageStatsInternal()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Archive load failed")
            }
            localState.value = localState.value.copy(busyAction = null)
        }
    }

    fun exportImageTar(
        imageRef: String,
        outputTarFile: File,
        isOnline: Boolean,
        onArchiveReady: (File) -> Unit,
    ) {
        scope.launch {
            val clean = imageRef.trim()
            if (clean.isEmpty()) return@launch
            localState.value = localState.value.copy(
                busyAction = "Exporting $clean...",
                errorMessage = null,
                statusMessage = null,
            )
            runCatching {
                val res = catalogRepository.saveImageArchive(
                    imageRef = clean,
                    outputTarPath = outputTarFile.absolutePath,
                    dnsPort = runtimeGateway.dnsProxyPort,
                    isOnline = isOnline,
                    env = runtimeGateway.buildRungvHostEnv(),
                )
                if (res.exitCode != 0) {
                    error(res.stderr.ifBlank { "Image export failed (${res.exitCode})" })
                }
                onArchiveReady(outputTarFile)
                localState.value = localState.value.copy(statusMessage = "Exported $clean to .tar archive.")
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Export failed")
            }
            localState.value = localState.value.copy(busyAction = null)
        }
    }

    fun refreshStorageStats() {
        scope.launch {
            localState.value = localState.value.copy(busyAction = "Reading storage stats...", errorMessage = null)
            runCatching {
                refreshStorageStatsInternal()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Failed to query storage stats")
            }
            localState.value = localState.value.copy(busyAction = null)
        }
    }

    fun inspectVolume(volumeName: String) {
        scope.launch {
            runCatching {
                val details = catalogRepository.inspectVolume(
                    volumeName = volumeName,
                    env = runtimeGateway.buildRungvHostEnv(),
                )
                localState.value = localState.value.copy(inspected = details, errorMessage = null)
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Volume inspect failed")
            }
        }
    }

    fun pruneUnused() {
        scope.launch {
            localState.value = localState.value.copy(busyAction = "Pruning unused layers and volumes...", errorMessage = null)
            runCatching {
                val env = runtimeGateway.buildRungvHostEnv()
                val sysRes = catalogRepository.systemPrune(env)
                if (sysRes.exitCode != 0) {
                    error(sysRes.stderr.ifBlank { "system prune failed (${sysRes.exitCode})" })
                }
                val volRes = catalogRepository.volumePrune(env)
                if (volRes.exitCode != 0) {
                    error(volRes.stderr.ifBlank { "volume prune failed (${volRes.exitCode})" })
                }
                localState.value = localState.value.copy(statusMessage = "Pruned unused image layers and volumes.")
                refreshStorageStatsInternal()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Prune failed")
            }
            localState.value = localState.value.copy(busyAction = null)
        }
    }

    fun authenticateRegistry(registryHost: String, username: String, password: String) {
        scope.launch {
            localState.value = localState.value.copy(
                busyAction = "Authenticating over TLS 1.3...",
                errorMessage = null,
                statusMessage = null,
            )
            runCatching {
                val secretChars = password.toCharArray()
                val res = try {
                    catalogRepository.authenticateOciRegistry(
                        registryHost = registryHost,
                        username = username,
                        password = secretChars,
                    )
                } finally {
                    secretChars.fill('\u0000')
                }
                if (res.statusCode !in 200..299) {
                    error("Registry rejected credentials for $registryHost (HTTP ${res.statusCode})")
                }
                localState.value = localState.value.copy(
                    statusMessage = "Saved registry authentication for $registryHost.",
                )
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Registry auth failed")
            }
            localState.value = localState.value.copy(busyAction = null)
        }
    }

    private fun waitForRecipeReadinessAndOpenShell(
        profile: SandboxProfile,
        readinessPort: Int,
        maxScrollbackLines: Int,
        palette: ColorPalette,
    ) {
        scope.launch {
            var attempts = 0
            val maxAttempts = 1200
            while (
                attempts < maxAttempts &&
                runtimeGateway.runStates.value[profile.id] == SandboxRunState.RUNNING &&
                !sessionCoordinator.hasShellTab(profile.id)
            ) {
                if (tcpProbe(readinessPort)) {
                    sessionCoordinator.openNewShellTab(profile, maxScrollbackLines, palette)
                    return@launch
                }
                attempts++
                delay(readinessPollStepMs)
            }
        }
    }

    private suspend fun refreshStorageStatsInternal() {
        val env = runtimeGateway.buildRungvHostEnv()
        val df = catalogRepository.systemDf(env)
        val volumes = catalogRepository.listVolumes(env)
        localState.value = localState.value.copy(dfEntries = df, volumes = volumes)
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        private suspend fun probeLoopbackPort(port: Int): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 400)
                }
                true
            }.getOrDefault(false)
        }
    }
}
