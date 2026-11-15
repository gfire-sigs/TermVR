package dev.gfire.termvr.ui.settings

import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.FontConfig
import dev.gfire.termvr.core.model.GlobalAppSettings
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.KeyBarConfig
import dev.gfire.termvr.core.model.KeySlotSpec
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.licenses.LicenseCatalogLoader
import dev.gfire.termvr.ui.licenses.ThirdPartyLicenseEntry
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SettingsTab(val label: String) {
    APPEARANCE("Appearance"),
    INPUT("Input"),
    SYSTEM("System"),
    LICENSES("Licenses"),
}

data class SettingsUiState(
    val selectedTab: SettingsTab = SettingsTab.APPEARANCE,
    val fontConfig: FontConfig = FontConfig(),
    val keyBarConfig: KeyBarConfig = KeyBarConfig(),
    val appSettings: GlobalAppSettings = GlobalAppSettings(),
    val palettes: List<ColorPalette> = ColorPalette.BUILT_IN_PALETTES,
    val hostReadiness: HostReadinessReport? = null,
    val appVersionName: String = "",
    val gvxVersion: String = "",
    val licenses: List<ThirdPartyLicenseEntry> = emptyList(),
    val activeLicense: ThirdPartyLicenseEntry? = null,
    val activeLicenseText: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

private data class SettingsLocalState(
    val selectedTab: SettingsTab = SettingsTab.APPEARANCE,
    val hostReadiness: HostReadinessReport? = null,
    val licenses: List<ThirdPartyLicenseEntry> = emptyList(),
    val activeLicense: ThirdPartyLicenseEntry? = null,
    val activeLicenseText: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * Coordinates typography, custom font SHA-256 import, palette, key bar layout, system diagnostics, and licenses.
 */
class SettingsViewModel(
    private val profileRepository: ProfileRepository,
    private val runtimeGateway: SandboxRuntimeGateway? = null,
    private val licenseLoader: LicenseCatalogLoader? = null,
    private val appVersionName: String = "",
    private val gvxVersion: String = "",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val localState = MutableStateFlow(SettingsLocalState())

    val uiState: StateFlow<SettingsUiState> = combine(
        profileRepository.observeFontConfig(),
        profileRepository.observeKeyBarConfig(),
        profileRepository.observeAppSettings(),
        localState,
        runtimeGateway?.hostReadiness ?: MutableStateFlow<HostReadinessReport?>(null),
    ) { font, keyBar, app, local, gatewayReadiness ->
        SettingsUiState(
            selectedTab = local.selectedTab,
            fontConfig = font,
            keyBarConfig = keyBar,
            appSettings = app,
            hostReadiness = gatewayReadiness ?: local.hostReadiness,
            appVersionName = appVersionName,
            gvxVersion = gvxVersion,
            licenses = local.licenses,
            activeLicense = local.activeLicense,
            activeLicenseText = local.activeLicenseText,
            statusMessage = local.statusMessage,
            errorMessage = local.errorMessage,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(
            hostReadiness = runtimeGateway?.hostReadiness?.value,
            appVersionName = appVersionName,
            gvxVersion = gvxVersion,
        ),
    )

    init {
        refreshSystemDiagnostics()
        loadLicenses()
    }

    fun selectTab(tab: SettingsTab) {
        localState.value = localState.value.copy(selectedTab = tab)
        if (tab == SettingsTab.SYSTEM) {
            refreshSystemDiagnostics()
        } else if (tab == SettingsTab.LICENSES && localState.value.licenses.isEmpty()) {
            loadLicenses()
        }
    }

    fun refreshSystemDiagnostics() {
        val gateway = runtimeGateway ?: return
        gateway.refreshHostReadiness()
        localState.value = localState.value.copy(hostReadiness = gateway.hostReadiness.value)
    }

    fun loadLicenses() {
        val loader = licenseLoader ?: return
        scope.launch {
            runCatching { loader.loadEntries() }
                .onSuccess { entries ->
                    localState.value = localState.value.copy(licenses = entries)
                }
                .onFailure { err ->
                    localState.value = localState.value.copy(
                        errorMessage = err.message ?: "Failed to load licenses",
                    )
                }
        }
    }

    fun openLicense(entry: ThirdPartyLicenseEntry) {
        val loader = licenseLoader ?: return
        scope.launch {
            runCatching { loader.loadLicenseText(entry) }
                .onSuccess { text ->
                    localState.value = localState.value.copy(
                        activeLicense = entry,
                        activeLicenseText = text,
                    )
                }
                .onFailure { err ->
                    localState.value = localState.value.copy(
                        errorMessage = err.message ?: "Failed to open license text",
                    )
                }
        }
    }

    fun closeLicense() {
        localState.value = localState.value.copy(
            activeLicense = null,
            activeLicenseText = null,
        )
    }

    fun clearBanners() {
        localState.value = localState.value.copy(
            statusMessage = null,
            errorMessage = null,
        )
    }

    fun updateFontFamily(familyId: String) {
        val next = uiState.value.fontConfig.copy(
            familyId = familyId,
            customFontPath = null,
            customFontSha256 = null,
        )
        saveFont(next)
    }

    fun updateFontMetrics(sizeSp: Float, cellHeightMultiplier: Float) {
        val next = uiState.value.fontConfig.copy(
            sizeSp = sizeSp,
            cellHeightMultiplier = cellHeightMultiplier,
        ).withClampedMetrics()
        saveFont(next)
    }

    fun updateFontFeatures(ligatures: Boolean, nerdFonts: Boolean) {
        val next = uiState.value.fontConfig.copy(
            ligaturesEnabled = ligatures,
            nerdFontSymbolsEnabled = nerdFonts,
        )
        saveFont(next)
    }

    fun importCustomFont(stream: InputStream, expectedSha256: String?) {
        scope.launch {
            localState.value = localState.value.copy(errorMessage = null)
            runCatching {
                val imported = profileRepository.importCustomFont(
                    inputStream = stream,
                    expectedSha256 = expectedSha256?.trim()?.ifEmpty { null },
                )
                localState.value = localState.value.copy(
                    statusMessage = "Imported font (${imported.customFontSha256?.take(12)}...).",
                )
            }.onFailure { err ->
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Font import failed",
                )
            }
        }
    }

    fun selectPalette(paletteId: String) {
        val next = uiState.value.appSettings.copy(selectedPaletteId = paletteId)
        saveAppSettings(next)
    }

    fun updateAppSettings(transform: (GlobalAppSettings) -> GlobalAppSettings) {
        saveAppSettings(transform(uiState.value.appSettings))
    }

    fun updateKeyBarSettings(visible: Boolean, hapticFeedback: Boolean) {
        saveKeyBar(uiState.value.keyBarConfig.copy(visible = visible, hapticFeedback = hapticFeedback))
    }

    fun updateKeyBarSlot(isTopRow: Boolean, index: Int, slot: KeySlotSpec) {
        val current = uiState.value.keyBarConfig
        val next = if (isTopRow) {
            val updatedRow = current.topRow.toMutableList().apply { set(index, slot) }
            current.copy(topRow = updatedRow)
        } else {
            val updatedRow = current.bottomRow.toMutableList().apply { set(index, slot) }
            current.copy(bottomRow = updatedRow)
        }
        saveKeyBar(next)
    }

    fun resetKeyBarDefaults() {
        saveKeyBar(
            KeyBarConfig(
                visible = uiState.value.keyBarConfig.visible,
                hapticFeedback = uiState.value.keyBarConfig.hapticFeedback,
            ),
        )
        localState.value = localState.value.copy(statusMessage = "Restored default 2 x 7 key bar.")
    }

    private fun saveFont(config: FontConfig) {
        scope.launch {
            runCatching { profileRepository.updateFontConfig(config) }
                .onFailure { err ->
                    localState.value = localState.value.copy(errorMessage = err.message ?: "Failed to save font")
                }
        }
    }

    private fun saveAppSettings(settings: GlobalAppSettings) {
        scope.launch {
            runCatching { profileRepository.updateAppSettings(settings) }
                .onFailure { err ->
                    localState.value = localState.value.copy(errorMessage = err.message ?: "Failed to save settings")
                }
        }
    }

    private fun saveKeyBar(config: KeyBarConfig) {
        scope.launch {
            runCatching { profileRepository.updateKeyBarConfig(config) }
                .onFailure { err ->
                    localState.value = localState.value.copy(errorMessage = err.message ?: "Failed to save key bar")
                }
        }
    }

    fun close() {
        scope.cancel()
    }
}
