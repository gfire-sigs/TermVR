package dev.gfire.termvr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.GlobalAppSettings
import dev.gfire.termvr.ui.catalog.CatalogViewModel
import dev.gfire.termvr.ui.files.FilesViewModel
import dev.gfire.termvr.ui.home.HomeViewModel
import dev.gfire.termvr.ui.navigation.TermVrNavHost
import dev.gfire.termvr.ui.network.NetworkViewModel
import dev.gfire.termvr.ui.settings.SettingsViewModel
import dev.gfire.termvr.ui.terminal.TerminalViewModel
import dev.gfire.termvr.ui.theme.TermVrTheme

/**
 * Single-activity Compose shell hosting TermVR screens, runtime bindings, and permissions.
 */
class MainActivity : ComponentActivity() {
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var terminalViewModel: TerminalViewModel
    private lateinit var catalogViewModel: CatalogViewModel
    private lateinit var networkViewModel: NetworkViewModel
    private lateinit var filesViewModel: FilesViewModel
    private lateinit var settingsViewModel: SettingsViewModel

    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val app = application as TermVrApplication
        app.container.runtimeGateway.setNotificationsPermissionGranted(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TermVrApplication).container

        homeViewModel = HomeViewModel(
            profileRepository = container.profileRepository,
            catalogRepository = container.catalogRepository,
            runtimeGateway = container.runtimeGateway,
            sessionCoordinator = container.sessionCoordinator,
        )
        terminalViewModel = TerminalViewModel(
            profileRepository = container.profileRepository,
            runtimeGateway = container.runtimeGateway,
            sessionCoordinator = container.sessionCoordinator,
        )
        catalogViewModel = CatalogViewModel(
            catalogRepository = container.catalogRepository,
            profileRepository = container.profileRepository,
            runtimeGateway = container.runtimeGateway,
            sessionCoordinator = container.sessionCoordinator,
        )
        networkViewModel = NetworkViewModel(
            profileRepository = container.profileRepository,
            runtimeGateway = container.runtimeGateway,
        )
        filesViewModel = FilesViewModel(
            profileRepository = container.profileRepository,
            runtimeGateway = container.runtimeGateway,
        )
        settingsViewModel = SettingsViewModel(
            profileRepository = container.profileRepository,
            runtimeGateway = container.runtimeGateway,
            licenseLoader = dev.gfire.termvr.ui.licenses.LicenseCatalogLoader(assets),
            appVersionName = BuildConfig.VERSION_NAME,
            gvxVersion = BuildConfig.GVX_VERSION,
        )

        syncNotificationsPermissionState(container)
        requestNotificationsIfNeeded()

        setContent {
            val appSettings by container.profileRepository.observeAppSettings()
                .collectAsStateWithLifecycle(initialValue = GlobalAppSettings())
            val palette = ColorPalette.byId(appSettings.selectedPaletteId) ?: ColorPalette.DEFAULT_DARK

            TermVrTheme(palette = palette) {
                TermVrNavHost(
                    homeViewModel = homeViewModel,
                    terminalViewModel = terminalViewModel,
                    catalogViewModel = catalogViewModel,
                    networkViewModel = networkViewModel,
                    filesViewModel = filesViewModel,
                    settingsViewModel = settingsViewModel,
                    runtimeGateway = container.runtimeGateway,
                    onRequestNotificationsPermission = ::requestNotificationsIfNeeded,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as TermVrApplication).container
        syncNotificationsPermissionState(container)
        container.runtimeGateway.refreshHostReadiness()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::homeViewModel.isInitialized) homeViewModel.close()
        if (::terminalViewModel.isInitialized) terminalViewModel.close()
        if (::catalogViewModel.isInitialized) catalogViewModel.close()
        if (::networkViewModel.isInitialized) networkViewModel.close()
        if (::filesViewModel.isInitialized) filesViewModel.close()
        if (::settingsViewModel.isInitialized) settingsViewModel.close()
    }

    private fun syncNotificationsPermissionState(container: AppContainer) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        container.runtimeGateway.setNotificationsPermissionGranted(granted)
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
