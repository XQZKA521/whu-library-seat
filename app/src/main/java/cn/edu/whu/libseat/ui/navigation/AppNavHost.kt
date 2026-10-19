package cn.edu.whu.libseat.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.whu.libseat.ui.captcha.SliderCaptchaDialog
import cn.edu.whu.libseat.ui.logs.LogsScreen
import cn.edu.whu.libseat.ui.quickbook.QuickBookRequest
import cn.edu.whu.libseat.ui.quickbook.QuickBookScreen
import cn.edu.whu.libseat.ui.stats.StatsScreen
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.edu.whu.libseat.LibrarySeatApp
import cn.edu.whu.libseat.ui.favorites.FavoritesScreen
import cn.edu.whu.libseat.ui.home.HomeScreen
import cn.edu.whu.libseat.ui.login.WebLoginScreen
import cn.edu.whu.libseat.ui.seatmap.SeatMapScreen
import cn.edu.whu.libseat.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val SEAT_MAP = "seatmap/{roomId}/{buildingId}/{floor}/{date}"
    const val LOGS = "logs"
    const val STATS = "stats"
    const val QUICK_BOOK = "quickbook/{seatId}/{date}/{start}/{end}"

    fun seatMap(roomId: Long, buildingId: Long, floor: Int, date: String) =
        "seatmap/$roomId/$buildingId/$floor/$date"

    fun quickBook(request: QuickBookRequest) =
        "quickbook/${request.seatId}/${request.date}/${request.start}/${request.end}"
}

private fun NavHostController.navigateToTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppNavHost(
    app: LibrarySeatApp,
    quickBookRequest: QuickBookRequest? = null,
    onQuickBookConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val captchaState by app.container.captchaManager.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                app = app,
                onOpenSeatMap = { roomId, buildingId, floor, date ->
                    navController.navigate(Routes.seatMap(roomId, buildingId, floor, date))
                },
                onOpenFavorites = { navController.navigateToTab(Routes.FAVORITES) },
                onOpenSettings = { navController.navigateToTab(Routes.SETTINGS) },
                onLogin = { navController.navigate(Routes.LOGIN) },
            )
        }
        composable(Routes.FAVORITES) {
            FavoritesScreen(
                app = app,
                onOpenSeatMap = { roomId, buildingId, floor, date ->
                    navController.navigate(Routes.seatMap(roomId, buildingId, floor, date))
                },
                onGoHome = { navController.navigateToTab(Routes.HOME) },
                onGoSettings = { navController.navigateToTab(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                app = app,
                onGoHome = { navController.navigateToTab(Routes.HOME) },
                onGoFavorites = { navController.navigateToTab(Routes.FAVORITES) },
                onLogin = { navController.navigate(Routes.LOGIN) },
                onOpenLogs = { navController.navigate(Routes.LOGS) },
                onOpenStats = { navController.navigate(Routes.STATS) },
            )
        }
        composable(Routes.LOGIN) {
            WebLoginScreen(
                app = app,
                onBack = { navController.popBackStack() },
                onLoggedIn = {
                    navController.popBackStack()
                },
            )
        }
        composable(
            route = Routes.SEAT_MAP,
            arguments = listOf(
                navArgument("roomId") { type = NavType.LongType },
                navArgument("buildingId") { type = NavType.LongType },
                navArgument("floor") { type = NavType.IntType },
                navArgument("date") { type = NavType.StringType },
            ),
        ) { entry ->
            val roomId = entry.arguments?.getLong("roomId") ?: 0L
            val buildingId = entry.arguments?.getLong("buildingId") ?: 0L
            val floor = entry.arguments?.getInt("floor") ?: 0
            val date = entry.arguments?.getString("date").orEmpty()
            SeatMapScreen(
                app = app,
                roomId = roomId,
                buildingId = buildingId,
                floor = floor,
                dateText = date,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LOGS) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.STATS) {
            StatsScreen(app = app, onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.QUICK_BOOK,
            arguments = listOf(
                navArgument("seatId") { type = NavType.LongType },
                navArgument("date") { type = NavType.StringType },
                navArgument("start") { type = NavType.IntType },
                navArgument("end") { type = NavType.IntType },
            ),
        ) { entry ->
            val seatId = entry.arguments?.getLong("seatId") ?: 0L
            val date = entry.arguments?.getString("date").orEmpty()
            val start = entry.arguments?.getInt("start") ?: 0
            val end = entry.arguments?.getInt("end") ?: 0
            QuickBookScreen(
                app = app,
                request = QuickBookRequest(seatId, date, start, end),
                onClose = { navController.popBackStack() },
                onOpenHome = {
                    if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) { launchSingleTop = true }
                    }
                },
                onLogin = { navController.navigate(Routes.LOGIN) },
            )
        }
    }

    // 小部件发起的预约：直接进入确认页并立刻提交（放在 NavHost 之后，确保图已就绪）
    LaunchedEffect(quickBookRequest) {
        val request = quickBookRequest ?: return@LaunchedEffect
        navController.navigate(Routes.quickBook(request)) { launchSingleTop = true }
        onQuickBookConsumed()
    }

    SliderCaptchaDialog(
        state = captchaState,
        onToken = app.container.captchaManager::onToken,
        onCancel = app.container.captchaManager::cancel,
    )
    }
}
