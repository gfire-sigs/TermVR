package dev.gfire.termvr.ui.settings

import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.FontConfig
import dev.gfire.termvr.core.model.GlobalAppSettings
import dev.gfire.termvr.core.model.KeyBarConfig
import dev.gfire.termvr.core.model.KeySlotSpec
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

data class SettingsUiState(
    val fontConfig: FontConfig = FontConfig(),
    val keyBarConfig: KeyBarConfig = KeyBarConfig(),
    val appSettings: GlobalAppSettings = GlobalAppSettings(),
    val palettes: List<ColorPalette> = ColorPalette.BUILT_IN_PALETTES,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * Coordinates typography, custom font SHA-256 import, palette, key bar layout, and runtime defaults.
 */
class SettingsViewModel(
    private val profileRepository: ProfileRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val statusMessage = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        profileRepository.observeFontConfig(),
        profileRepository.observeKeyBarConfig(),
        profileRepository.observeAppSettings(),
        statusMessage,
        errorMessage,
    ) { font, keyBar, app, status, error ->
        SettingsUiState(
            fontConfig = font,
            keyBarConfig = keyBar,
            appSettings = app,
            statusMessage = status,
            errorMessage = error,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(),
    )

    fun clearBanners() {
        statusMessage.value = null
        errorMessage.value = null
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
            errorMessage.value = null
            runCatching {
                val imported = profileRepository.importCustomFont(
                    inputStream = stream,
                    expectedSha256 = expectedSha256?.trim()?.ifEmpty { null },
                )
                statusMessage.value = "Imported font (SHA-256: ${imported.customFontSha256?.take(12)}...)."
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Font import failed"
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
        statusMessage.value = "Restored default 2 x 7 key bar layout."
    }

    private fun saveFont(config: FontConfig) {
        scope.launch {
            runCatching { profileRepository.updateFontConfig(config) }
                .onFailure { err -> errorMessage.value = err.message ?: "Failed to save font" }
        }
    }

    private fun saveAppSettings(settings: GlobalAppSettings) {
        scope.launch {
            runCatching { profileRepository.updateAppSettings(settings) }
                .onFailure { err -> errorMessage.value = err.message ?: "Failed to save settings" }
        }
    }

    private fun saveKeyBar(config: KeyBarConfig) {
        scope.launch {
            runCatching { profileRepository.updateKeyBarConfig(config) }
                .onFailure { err -> errorMessage.value = err.message ?: "Failed to save key bar" }
        }
    }

    fun close() {
        scope.cancel()
    }
}
