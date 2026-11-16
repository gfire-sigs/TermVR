package dev.gfire.termvr

import android.content.Context
import dev.gfire.termvr.core.data.AndroidKeystoreSecretStore
import dev.gfire.termvr.core.data.CatalogRepository
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.runtime.SandboxForegroundService
import dev.gfire.termvr.runtime.AndroidSandboxRuntimeGateway
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.terminal.TerminalSessionCoordinator
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Holds application-scoped repositories, runtime gateway, and terminal session coordination.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val secretStore = AndroidKeystoreSecretStore(
        secretsDir = File(appContext.filesDir, "secrets"),
    )

    val profileRepository = ProfileRepository(
        dataStore = ProfileRepository.createDataStore(appContext.filesDir, appScope),
        filesDir = appContext.filesDir,
        secretStore = secretStore,
    )

    private val androidGateway = AndroidSandboxRuntimeGateway(
        context = appContext,
        secretStore = secretStore,
        profileRepository = profileRepository,
    )

    val runtimeGateway: SandboxRuntimeGateway = androidGateway
    val supervisor = androidGateway.supervisor

    val catalogRepository = CatalogRepository.create(
        context = appContext,
        commandExecutor = androidGateway.createRungvCommandExecutor(),
    )

    val sessionCoordinator = TerminalSessionCoordinator(
        runtimeGateway = runtimeGateway,
    )

    init {
        SandboxForegroundService.resumeActionHandler = { profileId ->
            appScope.launch {
                val profile = profileRepository.getProfile(profileId) ?: return@launch
                runCatching {
                    runtimeGateway.startOrResumeSandbox(profile)
                    val settings = profileRepository.observeAppSettings().first()
                    val palette = ColorPalette.byId(settings.selectedPaletteId) ?: ColorPalette.DEFAULT_DARK
                    sessionCoordinator.reattachAfterResume(
                        profile = profile,
                        maxScrollbackLines = settings.defaultScrollbackLines,
                        palette = palette,
                    )
                }.onFailure { err ->
                    sessionCoordinator.reportError(err.message ?: "Failed to resume container")
                }
            }
        }
    }
}
