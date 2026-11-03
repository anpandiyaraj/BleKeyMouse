package com.blekeymouse.ui.screens

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.blekeymouse.ui.theme.BorderSubtle
import com.blekeymouse.ui.theme.CyanGlow
import com.blekeymouse.ui.theme.DeepNavy
import com.blekeymouse.ui.theme.GrayMuted
import com.blekeymouse.ui.theme.SurfaceDark
import com.blekeymouse.ui.theme.TextSecondary
import com.blekeymouse.viewmodel.MainViewModel

// ─────────────────────────────────────────────────────────────────────────────
// Navigation Routes
// ─────────────────────────────────────────────────────────────────────────────

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Devices  : Screen("devices",  "Devices",  Icons.Rounded.Devices)
    object Touchpad : Screen("touchpad", "Touchpad", Icons.Rounded.Mouse)
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen — navigation host + bottom bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val navController: NavHostController = rememberNavController()

    Scaffold(
        containerColor = DeepNavy,
        bottomBar = {
            AppBottomNavBar(navController = navController)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepNavy)
                .padding(innerPadding)
        ) {
            NavHost(
                navController    = navController,
                startDestination = Screen.Devices.route,
                enterTransition  = {
                    slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Start,
                        tween(250)
                    )
                },
                exitTransition   = {
                    slideOutOfContainer(
                        AnimatedContentTransitionScope.SlideDirection.Start,
                        tween(250)
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.End,
                        tween(250)
                    )
                },
                popExitTransition  = {
                    slideOutOfContainer(
                        AnimatedContentTransitionScope.SlideDirection.End,
                        tween(250)
                    )
                }
            ) {
                composable(Screen.Devices.route) {
                    DeviceListScreen(viewModel = viewModel)
                }
                composable(Screen.Touchpad.route) {
                    TouchpadScreen(viewModel = viewModel)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom Navigation Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppBottomNavBar(navController: NavHostController) {
    val tabs = listOf(Screen.Devices, Screen.Touchpad)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(
        containerColor = SurfaceDark,
        tonalElevation = 0.dp,
    ) {
        tabs.forEach { screen ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                selected = selected,
                onClick  = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState    = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = screen.label,
                        tint = if (selected) CyanGlow else GrayMuted
                    )
                },
                label = {
                    Text(
                        text = screen.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) CyanGlow else TextSecondary
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor      = CyanGlow,
                    indicatorColor         = CyanGlow.copy(alpha = 0.12f),
                    unselectedIconColor    = GrayMuted,
                    unselectedTextColor    = TextSecondary
                )
            )
        }
    }
}
