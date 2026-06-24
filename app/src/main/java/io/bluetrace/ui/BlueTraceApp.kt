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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.bluetrace.R
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.session.SessionController
import io.bluetrace.shared.data.SessionStore
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.ui.nav.Route
import io.bluetrace.ui.nav.TopLevel
import io.bluetrace.ui.screen.collect.CollectHomeScreen
import io.bluetrace.ui.screen.data.DataHomeScreen
import io.bluetrace.ui.screen.data.SessionDetailScreen
import io.bluetrace.ui.screen.device.DeviceConnectScreen
import io.bluetrace.ui.screen.permission.BluetoothOffScreen
import io.bluetrace.ui.screen.permission.PermissionGateScreen
import io.bluetrace.ui.screen.permission.PowerSaveGuideScreen
import io.bluetrace.ui.screen.run.CollectionRunScreen
import io.bluetrace.ui.screen.settings.AboutScreen
import io.bluetrace.ui.screen.settings.AppearanceScreen
import io.bluetrace.ui.screen.settings.AppLogScreen
import io.bluetrace.ui.screen.settings.DeviceMaintenanceScreen
import io.bluetrace.ui.screen.settings.EnvCheckScreen
import io.bluetrace.ui.screen.settings.ExportLocationScreen
import io.bluetrace.ui.screen.settings.LanguageScreen
import io.bluetrace.ui.screen.settings.SettingsHomeScreen
import io.bluetrace.ui.screen.settings.StorageScreen
import io.bluetrace.ui.screen.subject.SubjectEditScreen
import io.bluetrace.ui.screen.subject.SubjectSelectScreen
import io.bluetrace.ui.screen.summary.SessionSummaryScreen
import io.bluetrace.ui.startup.AppSplash
import io.bluetrace.ui.startup.AppStartup
import io.bluetrace.ui.startup.StartDecision
import io.bluetrace.ui.theme.BlueTraceTheme
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/** 应用内启动屏最短展示时长（冷启动一闪而过，§5.1）。 */
private const val SPLASH_MIN_MS = 900L

/**
 * 根导航：启动决策（[AppStartup]）就绪后再渲染。冷启动一次性走首启/恢复；暖/热启动直落当前界面（§5.1）。
 * 系统 SplashScreen 在决策就绪前一直保持（见 MainActivity）。
 */
@Composable
fun BlueTraceApp(onReady: () -> Unit) {
    BlueTraceTheme {
        val prefs = koinInject<AppPreferences>()
        val store = koinInject<SessionStore>()
        val clock = koinInject<EpochClock>()
        val controller = koinInject<SessionController>()
        val context = LocalContext.current

        val cold = remember { AppStartup.peekCold() }
        var decision by remember { mutableStateOf<StartDecision?>(null) }
        // 系统 SplashScreen 立即退场 → 由应用内 AppSplash 接管（同底色），把"系统层 + 应用内"两段
        // 合成一个开屏；decide() 期间正好让 AppSplash 的三点动画转。
        LaunchedEffect(Unit) { onReady() }
        LaunchedEffect(Unit) {
            decision = AppStartup.decide(prefs, store, clock, controller)
        }
        // 应用内启动屏（原型启动A：渐变 logo + 字标 + 副标 + 三点动画）。仅冷启动展示一次（§5.1）：
        // 暖/热启动 cold=false → 跳过直落当前界面；系统 SplashScreen 在 onReady 后退场，由此接管。
        var splashElapsed by remember { mutableStateOf(!cold) }
        if (cold) {
            LaunchedEffect(Unit) {
                delay(SPLASH_MIN_MS)
                splashElapsed = true
            }
        }
        val d = decision
        if (d == null || !splashElapsed) {
            if (cold) AppSplash()
            return@BlueTraceTheme // 系统 splash / 应用内 splash 展示期间
        }

        LaunchedEffect(d) {
            if (d.recoveredCount > 0) {
                Toast.makeText(context, context.getString(R.string.recovery_toast), Toast.LENGTH_LONG).show()
            }
        }

        val rootNav = rememberNavController()
        NavHost(
            navController = rootNav,
            startDestination = if (d.firstLaunch) Route.PermissionGate else Route.Main,
        ) {
            composable<Route.PermissionGate> {
                PermissionGateScreen(
                    onContinue = { rootNav.navigate(Route.Main) { popUpTo(Route.PermissionGate) { inclusive = true } } },
                    onPowerSaveGuide = { rootNav.navigate(Route.PowerSaveGuide) },
                )
            }
            composable<Route.PowerSaveGuide> { PowerSaveGuideScreen(onBack = { rootNav.popBackStack() }) }
            composable<Route.Main> { MainScaffold(initialToRun = d.collecting) }
        }
    }
}

/** 三 Tab 自适应脚手架（NavigationSuiteScaffold，§7.2）。每 Tab 独立嵌套 NavGraph + 独立返回栈。 */
@Composable
private fun MainScaffold(initialToRun: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val topLevel = topLevelOf(dest)
    val showBar = isTopLevelRoot(dest)

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

    // 进程恢复（服务活、会话仍采集中）→ 直落运行页（§5.10 / v2-B）。
    LaunchedEffect(Unit) {
        if (initialToRun) nav.navigate(Route.CollectionRun)
    }
}

@Composable
private fun BlueTraceNavHost(nav: NavHostController) {
    val context = LocalContext.current
    NavHost(navController = nav, startDestination = Route.CollectGraph) {

        // ===== 采集 Tab 子图（独立返回栈）=====
        navigation<Route.CollectGraph>(startDestination = Route.CollectHome) {
            composable<Route.CollectHome> {
                CollectHomeScreen(
                    onOpenDevice = { nav.navigate(Route.DeviceConnect) },
                    onOpenSubject = { nav.navigate(Route.SubjectSelect) },
                    onStart = { nav.navigate(Route.CollectionRun) },
                    onBluetoothOff = { nav.navigate(Route.BluetoothOff) },
                )
            }
            composable<Route.DeviceConnect> {
                DeviceConnectScreen(
                    onBack = { nav.popBackStack() },
                    onBluetoothOff = { nav.navigate(Route.BluetoothOff) },
                )
            }
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
                    onHardLockHint = { Toast.makeText(context, context.getString(R.string.run_hardlock_hint), Toast.LENGTH_SHORT).show() },
                )
            }
            composable<Route.SessionSummary> {
                SessionSummaryScreen(
                    onViewDetail = { folder -> nav.navigate(Route.SessionDetail(folder)) },
                    onDone = { navigateTab(nav, TopLevel.DATA) },
                )
            }
            composable<Route.BluetoothOff> { BluetoothOffScreen(onBack = { nav.popBackStack() }) }
        }

        // ===== 数据 Tab 子图 =====
        navigation<Route.DataGraph>(startDestination = Route.DataHome) {
            composable<Route.DataHome> { DataHomeScreen(onOpenDetail = { folder -> nav.navigate(Route.SessionDetail(folder)) }) }
            composable<Route.SessionDetail> { entry ->
                val r = entry.toRoute<Route.SessionDetail>()
                SessionDetailScreen(folderName = r.folderName, onBack = { nav.popBackStack() })
            }
        }

        // ===== 设置 Tab 子图 =====
        navigation<Route.SettingsGraph>(startDestination = Route.SettingsHome) {
            composable<Route.SettingsHome> {
                SettingsHomeScreen(
                    onEnv = { nav.navigate(Route.EnvCheck) },
                    onExportLoc = { nav.navigate(Route.ExportLocation) },
                    onStorage = { nav.navigate(Route.Storage) },
                    onLog = { nav.navigate(Route.AppLog) },
                    onDeviceMaint = { nav.navigate(Route.DeviceMaintenance) },
                    onAbout = { nav.navigate(Route.About) },
                    onAppearance = { nav.navigate(Route.Appearance) },
                    onLanguage = { nav.navigate(Route.Language) },
                )
            }
            composable<Route.EnvCheck> { EnvCheckScreen(onBack = { nav.popBackStack() }) }
            composable<Route.ExportLocation> { ExportLocationScreen(onBack = { nav.popBackStack() }) }
            composable<Route.Storage> { StorageScreen(onBack = { nav.popBackStack() }) }
            composable<Route.AppLog> { AppLogScreen(onBack = { nav.popBackStack() }) }
            composable<Route.DeviceMaintenance> { DeviceMaintenanceScreen(onBack = { nav.popBackStack() }) }
            composable<Route.About> { AboutScreen(onBack = { nav.popBackStack() }) }
            composable<Route.Appearance> { AppearanceScreen(onBack = { nav.popBackStack() }) }
            composable<Route.Language> { LanguageScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

/** 仅三个顶级 Tab 根目的地显示底部 Bar（子页/运行隐藏，§5.1 ③）。 */
private fun isTopLevelRoot(dest: NavDestination?): Boolean =
    dest != null && (
        dest.hasRoute(Route.CollectHome::class) ||
            dest.hasRoute(Route.DataHome::class) ||
            dest.hasRoute(Route.SettingsHome::class)
        )

/** 当前所属 Tab（按嵌套子图归属，子页也高亮所在 Tab）。 */
private fun topLevelOf(dest: NavDestination?): TopLevel? = when {
    dest == null -> null
    dest.hierarchy.any { it.hasRoute(Route.CollectGraph::class) } -> TopLevel.COLLECT
    dest.hierarchy.any { it.hasRoute(Route.DataGraph::class) } -> TopLevel.DATA
    dest.hierarchy.any { it.hasRoute(Route.SettingsGraph::class) } -> TopLevel.SETTINGS
    else -> null
}

/** 切 Tab：导航到该 Tab 子图 → 保存/恢复各自返回栈与滚动位（§7.2②），重选回根。 */
private fun navigateTab(nav: NavHostController, tab: TopLevel) {
    val graph: Route = when (tab) {
        TopLevel.COLLECT -> Route.CollectGraph
        TopLevel.DATA -> Route.DataGraph
        TopLevel.SETTINGS -> Route.SettingsGraph
    }
    nav.navigate(graph) {
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
    TopLevel.COLLECT -> R.string.tab_collect
    TopLevel.DATA -> R.string.tab_data
    TopLevel.SETTINGS -> R.string.tab_settings
}
