package io.bluetrace.ui

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.bluetrace.ui.nav.Route
import io.bluetrace.ui.nav.TopLevel
import io.bluetrace.ui.screen.collect.CollectHomeScreen
import io.bluetrace.ui.screen.data.DataHomeScreen
import io.bluetrace.ui.screen.data.SessionDetailScreen
import io.bluetrace.ui.screen.device.DeviceConnectScreen
import io.bluetrace.ui.screen.permission.BluetoothOffScreen
import io.bluetrace.ui.screen.permission.GnssScreen
import io.bluetrace.ui.screen.permission.PermissionGateScreen
import io.bluetrace.ui.screen.permission.PowerSaveGuideScreen
import io.bluetrace.ui.screen.permission.SplashScreen
import io.bluetrace.ui.screen.run.CollectionRunScreen
import io.bluetrace.ui.screen.settings.AboutScreen
import io.bluetrace.ui.screen.settings.AppLogScreen
import io.bluetrace.ui.screen.settings.DeviceMaintenanceScreen
import io.bluetrace.ui.screen.settings.EnvCheckScreen
import io.bluetrace.ui.screen.settings.ExportLocationScreen
import io.bluetrace.ui.screen.settings.SettingsHomeScreen
import io.bluetrace.ui.screen.settings.StorageScreen
import io.bluetrace.ui.screen.subject.SubjectEditScreen
import io.bluetrace.ui.screen.subject.SubjectSelectScreen
import io.bluetrace.ui.screen.summary.SessionSummaryScreen
import io.bluetrace.ui.theme.BlueTraceTheme

/** 根导航：Splash → 权限门控 → Main（三 Tab 脚手架）。 */
@Composable
fun BlueTraceApp() {
    BlueTraceTheme {
        val rootNav = rememberNavController()
        NavHost(navController = rootNav, startDestination = Route.Splash) {
            composable<Route.Splash> {
                SplashScreen(
                    onGate = { rootNav.navigate(Route.PermissionGate) { popUpTo(Route.Splash) { inclusive = true } } },
                    onMain = { rootNav.navigate(Route.Main) { popUpTo(Route.Splash) { inclusive = true } } },
                )
            }
            composable<Route.PermissionGate> {
                PermissionGateScreen(onContinue = { rootNav.navigate(Route.Main) { popUpTo(Route.PermissionGate) { inclusive = true } } })
            }
            composable<Route.Main> { MainScaffold() }
        }
    }
}

/** 三 Tab 自适应脚手架（NavigationSuiteScaffold，§7.2）。仅顶级显示 Bar，子页/运行隐藏。 */
@Composable
private fun MainScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val topLevel = topLevelOf(dest)
    val showBar = topLevel != null

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevel.entries.forEach { tab ->
                item(
                    selected = tab == topLevel,
                    onClick = { navigateTab(nav, tab) },
                    icon = { Icon(tabIcon(tab), contentDescription = stringResource(tabLabelRes(tab))) },
                    label = { Text(stringResource(tabLabelRes(tab))) },
                )
            }
        },
        layoutType = if (showBar) {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        } else {
            NavigationSuiteType.None
        },
    ) {
        BlueTraceNavHost(nav)
    }
}

@Composable
private fun BlueTraceNavHost(nav: NavHostController) {
    val context = LocalContext.current
    // 进程恢复（服务活则重绑续采，§5.10）：进入主界面时若会话仍在采集，直接落运行页。
    val controller = org.koin.compose.koinInject<io.bluetrace.shared.session.SessionController>()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (controller.state.value.status == io.bluetrace.shared.session.RunStatus.COLLECTING) {
            nav.navigate(Route.CollectionRun)
        }
    }
    NavHost(navController = nav, startDestination = Route.CollectHome) {
        // ---- 采集 Tab ----
        composable<Route.CollectHome> {
            CollectHomeScreen(
                onOpenDevice = { nav.navigate(Route.DeviceConnect) },
                onOpenSubject = { nav.navigate(Route.SubjectSelect) },
                onStart = { nav.navigate(Route.CollectionRun) },
            )
        }
        composable<Route.DeviceConnect> { DeviceConnectScreen(onBack = { nav.popBackStack() }) }
        composable<Route.SubjectSelect> {
            SubjectSelectScreen(onBack = { nav.popBackStack() }, onNew = { nav.navigate(Route.SubjectEdit()) })
        }
        composable<Route.SubjectEdit> { entry ->
            val r = entry.toRoute<Route.SubjectEdit>()
            SubjectEditScreen(subjectId = r.subjectId, onBack = { nav.popBackStack() })
        }
        composable<Route.CollectionRun> {
            CollectionRunScreen(
                onFinished = { nav.navigate(Route.SessionSummary) { popUpTo(Route.CollectionRun) { inclusive = true } } },
                onHardLockHint = { Toast.makeText(context, context.getString(io.bluetrace.R.string.run_hardlock_hint), Toast.LENGTH_SHORT).show() },
            )
        }
        composable<Route.SessionSummary> {
            SessionSummaryScreen(
                onViewDetail = { folder -> nav.navigate(Route.SessionDetail(folder)) },
                onDone = { navigateTab(nav, TopLevel.DATA) },
            )
        }

        // ---- 数据 Tab ----
        composable<Route.DataHome> { DataHomeScreen(onOpenDetail = { folder -> nav.navigate(Route.SessionDetail(folder)) }) }
        composable<Route.SessionDetail> { entry ->
            val r = entry.toRoute<Route.SessionDetail>()
            SessionDetailScreen(folderName = r.folderName, onBack = { nav.popBackStack() })
        }

        // ---- 设置 Tab ----
        composable<Route.SettingsHome> {
            SettingsHomeScreen(
                onEnv = { nav.navigate(Route.EnvCheck) },
                onGnss = { nav.navigate(Route.Gnss) },
                onExportLoc = { nav.navigate(Route.ExportLocation) },
                onStorage = { nav.navigate(Route.Storage) },
                onLog = { nav.navigate(Route.AppLog) },
                onDeviceMaint = { nav.navigate(Route.DeviceMaintenance) },
                onAbout = { nav.navigate(Route.About) },
            )
        }
        composable<Route.EnvCheck> { EnvCheckScreen(onBack = { nav.popBackStack() }) }
        composable<Route.Gnss> { GnssScreen(onBack = { nav.popBackStack() }) }
        composable<Route.ExportLocation> { ExportLocationScreen(onBack = { nav.popBackStack() }) }
        composable<Route.Storage> { StorageScreen(onBack = { nav.popBackStack() }) }
        composable<Route.AppLog> { AppLogScreen(onBack = { nav.popBackStack() }) }
        composable<Route.DeviceMaintenance> { DeviceMaintenanceScreen(onBack = { nav.popBackStack() }) }
        composable<Route.About> { AboutScreen(onBack = { nav.popBackStack() }) }
        composable<Route.PowerSaveGuide> { PowerSaveGuideScreen(onBack = { nav.popBackStack() }) }
        composable<Route.BluetoothOff> { BluetoothOffScreen(onBack = { nav.popBackStack() }) }
    }
}

private fun topLevelOf(dest: NavDestination?): TopLevel? = when {
    dest == null -> null
    dest.hasRoute(Route.CollectHome::class) -> TopLevel.COLLECT
    dest.hasRoute(Route.DataHome::class) -> TopLevel.DATA
    dest.hasRoute(Route.SettingsHome::class) -> TopLevel.SETTINGS
    else -> null
}

private fun navigateTab(nav: NavHostController, tab: TopLevel) {
    val route: Route = when (tab) {
        TopLevel.COLLECT -> Route.CollectHome
        TopLevel.DATA -> Route.DataHome
        TopLevel.SETTINGS -> Route.SettingsHome
    }
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun tabIcon(tab: TopLevel): ImageVector = when (tab) {
    TopLevel.COLLECT -> Icons.Filled.Bluetooth
    TopLevel.DATA -> Icons.Filled.Folder
    TopLevel.SETTINGS -> Icons.Filled.Settings
}

private fun tabLabelRes(tab: TopLevel): Int = when (tab) {
    TopLevel.COLLECT -> io.bluetrace.R.string.tab_collect
    TopLevel.DATA -> io.bluetrace.R.string.tab_data
    TopLevel.SETTINGS -> io.bluetrace.R.string.tab_settings
}
