package cn.edu.whu.libseat.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.whu.libseat.ui.captcha.SliderCaptchaDialog
import cn.edu.whu.libseat.ui.logs.LogsScreen
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

    fun seatMap(roomId: Long, buildingId: Long, floor: Int, date: String) =
        "seatmap/$roomId/$buildingId/$floor/$date"
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
fun AppNavHost(app: LibrarySeatApp) {
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
    }

    SliderCaptchaDialog(
        state = captchaState,
        onToken = app.container.captchaManager::onToken,
        onCancel = app.container.captchaManager::cancel,
    )
    }
}
