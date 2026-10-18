package cn.edu.whu.libseat.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class MainTab(val route: String, val label: String) {
    HOME("home", "预约"),
    FAVORITES("favorites", "常用"),
    SETTINGS("settings", "设置"),
}

@Composable
fun AppBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        MainTab.entries.forEach { tab ->
            val selected = currentRoute == tab.route
            val icon: ImageVector = when (tab) {
                MainTab.HOME -> Icons.Rounded.EventSeat
                MainTab.FAVORITES -> if (selected) Icons.Rounded.Star else Icons.Rounded.StarBorder
                MainTab.SETTINGS -> if (selected) Icons.Rounded.Settings else Icons.Rounded.Person
            }
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onNavigate(tab.route) },
                icon = { Icon(icon, contentDescription = tab.label, modifier = Modifier.size(22.dp)) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
