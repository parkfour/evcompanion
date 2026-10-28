package com.evcompanion.phone.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.evcompanion.phone.viewmodel.MainViewModel

private enum class Tab(val route: String, val label: String) {
    CONNECT("connect", "Connect"),
    PRESET("preset", "Preset"),
    LIVE("live", "Live data"),
}

@Composable
fun AppNavGraph(viewModel: MainViewModel) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {},
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.CONNECT.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.CONNECT.route) { BluetoothPickerScreen(viewModel) }
            composable(Tab.PRESET.route) { PresetPickerScreen(viewModel) }
            composable(Tab.LIVE.route) { LiveDataListScreen(viewModel) }
        }
    }
}
