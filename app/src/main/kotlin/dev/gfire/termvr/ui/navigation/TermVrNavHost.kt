package dev.gfire.termvr.ui.navigation

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.gfire.termvr.ui.theme.TermVrButton
import dev.gfire.termvr.ui.theme.TermVrOutlinedButton
import dev.gfire.termvr.ui.theme.TermVrTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.gfire.termvr.R
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.runtime.SandboxForegroundService
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.catalog.CatalogScreen
import dev.gfire.termvr.ui.catalog.CatalogViewModel
import dev.gfire.termvr.ui.files.FilesScreen
import dev.gfire.termvr.ui.files.FilesViewModel
import dev.gfire.termvr.ui.home.HomeScreen
import dev.gfire.termvr.ui.home.HomeViewModel
import dev.gfire.termvr.ui.network.NetworkScreen
import dev.gfire.termvr.ui.network.NetworkViewModel
import dev.gfire.termvr.ui.settings.SettingsScreen
import dev.gfire.termvr.ui.settings.SettingsViewModel
import dev.gfire.termvr.ui.terminal.TerminalScreen
import dev.gfire.termvr.ui.terminal.TerminalViewModel
import kotlinx.coroutines.launch

enum class TermVrRoute(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    HOME("home", "Containers", dev.gfire.termvr.ui.theme.TermVrIcons.Containers),
    TERMINAL("terminal", "Terminal", dev.gfire.termvr.ui.theme.TermVrIcons.Terminal),
    CATALOG("catalog", "Catalog", dev.gfire.termvr.ui.theme.TermVrIcons.Catalog),
    NETWORK("network", "Network", dev.gfire.termvr.ui.theme.TermVrIcons.Network),
    FILES("files", "Files", dev.gfire.termvr.ui.theme.TermVrIcons.Files),
    SETTINGS("settings", "Settings", dev.gfire.termvr.ui.theme.TermVrIcons.Settings),
}

/**
 * Hosts adaptive navigation, notification-denied status controls, and all six top-level screens.
 */
@Composable
fun TermVrNavHost(
    homeViewModel: HomeViewModel,
    terminalViewModel: TerminalViewModel,
    catalogViewModel: CatalogViewModel,
    networkViewModel: NetworkViewModel,
    filesViewModel: FilesViewModel,
    settingsViewModel: SettingsViewModel,
    runtimeGateway: SandboxRuntimeGateway,
    onRequestNotificationsPermission: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: TermVrRoute.HOME.route

    val configuration = LocalConfiguration.current
    val isExpandedWidth = configuration.screenWidthDp >= 600

    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    val showBottomBar = !isExpandedWidth && !(currentRoute == TermVrRoute.TERMINAL.route && isImeVisible)

    val notificationsDenied by runtimeGateway.notificationsDeniedBanner.collectAsStateWithLifecycle()
    val runStates by runtimeGateway.runStates.collectAsStateWithLifecycle()
    val anyActive = runStates.values.any {
        it == SandboxRunState.STARTING || it == SandboxRunState.RUNNING || it == SandboxRunState.RESTORING
    }
    val scope = rememberCoroutineScope()

    val navigateTo: (TermVrRoute) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        bottomBar = {
            if (showBottomBar) {
                val navBarTokens = dev.gfire.termvr.ui.theme.LocalTermVrPaletteTokens.current
                Column {
                    androidx.compose.material3.HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    NavigationBar(
                        containerColor = navBarTokens.navBarBackground,
                        tonalElevation = 0.dp,
                    ) {
                        TermVrRoute.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = { navigateTo(dest) },
                                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                icon = {
                                    androidx.compose.material3.Icon(
                                        imageVector = dest.icon,
                                        contentDescription = dest.label,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                label = {
                                    Text(
                                        text = dest.label,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 11.sp,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (isExpandedWidth) {
                val navBarTokens = dev.gfire.termvr.ui.theme.LocalTermVrPaletteTokens.current
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = navBarTokens.navBarBackground,
                ) {
                    TermVrRoute.entries.forEach { dest ->
                        NavigationRailItem(
                            selected = currentRoute == dest.route,
                            onClick = { navigateTo(dest) },
                            colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            icon = {
                                androidx.compose.material3.Icon(
                                    imageVector = dest.icon,
                                    contentDescription = dest.label,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            label = {
                                Text(
                                    text = dest.label,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                        )
                    }
                }
                androidx.compose.material3.VerticalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (notificationsDenied && (anyActive || currentRoute != TermVrRoute.TERMINAL.route)) {
                    NotificationsDeniedBanner(
                        onFreezeAll = {
                            runtimeGateway.startForegroundAction(SandboxForegroundService.ACTION_FREEZE_ALL)
                        },
                        onStopAll = {
                            scope.launch { runtimeGateway.stopAllSandboxes() }
                        },
                        onEnableNotifications = onRequestNotificationsPermission,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = TermVrRoute.HOME.route,
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        composable(TermVrRoute.HOME.route) {
                            HomeScreen(
                                viewModel = homeViewModel,
                                onOpenTerminal = { navigateTo(TermVrRoute.TERMINAL) },
                                onOpenSystemDiagnostics = {
                                    settingsViewModel.selectTab(dev.gfire.termvr.ui.settings.SettingsTab.SYSTEM)
                                    navigateTo(TermVrRoute.SETTINGS)
                                },
                            )
                        }
                        composable(TermVrRoute.TERMINAL.route) {
                            TerminalScreen(
                                viewModel = terminalViewModel,
                                onNavigateHome = { navigateTo(TermVrRoute.HOME) },
                            )
                        }
                        composable(TermVrRoute.CATALOG.route) {
                            CatalogScreen(
                                viewModel = catalogViewModel,
                                onOpenTerminal = { navigateTo(TermVrRoute.TERMINAL) },
                            )
                        }
                        composable(TermVrRoute.NETWORK.route) {
                            NetworkScreen(viewModel = networkViewModel)
                        }
                        composable(TermVrRoute.FILES.route) {
                            FilesScreen(
                                viewModel = filesViewModel,
                                isExpandedWidth = isExpandedWidth,
                            )
                        }
                        composable(TermVrRoute.SETTINGS.route) {
                            SettingsScreen(viewModel = settingsViewModel)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationsDeniedBanner(
    onFreezeAll: () -> Unit,
    onStopAll: () -> Unit,
    onEnableNotifications: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.banner_notifications_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TermVrButton(onClick = onFreezeAll) {
                    Text(stringResource(R.string.action_freeze_all))
                }
                TermVrOutlinedButton(
                    onClick = onStopAll,
                    borderColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text(stringResource(R.string.action_stop_all))
                }
                TermVrTextButton(
                    onClick = {
                        onEnableNotifications()
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }.onFailure {
                            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(fallback) }
                        }
                    },
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text("Notification Settings")
                }
            }
        }
    }
}
