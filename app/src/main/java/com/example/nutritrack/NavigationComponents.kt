package com.example.nutritrack

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.nutritrack.data.AppRoute

data class BottomNavItem(
    val route: AppRoute,
    val label: String,
    val icon: ImageVector
)

object NavigationItems {
    val HOME = BottomNavItem(AppRoute.HOME, "Home", Icons.Default.Home)
    val INSIGHTS = BottomNavItem(AppRoute.INSIGHTS, "Insights", Icons.Default.Info)
    val NUTRICOACH = BottomNavItem(AppRoute.NUTRI_COACH, "NutriCoach", Icons.Default.Person)
    val SETTINGS = BottomNavItem(AppRoute.SETTINGS, "Settings", Icons.Default.Settings)

    val items = listOf(HOME, INSIGHTS, NUTRICOACH, SETTINGS)
}

@Composable
fun HomeNavigationScaffold(
    initialRoute: AppRoute,
    onRouteChanged: (AppRoute) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (
        route: AppRoute,
        navigateTo: (AppRoute) -> Unit,
        navigateBack: () -> Unit,
        contentModifier: Modifier
    ) -> Unit
) {
    HomeNavigationScaffold(
        navController = rememberNavController(),
        initialRoute = initialRoute,
        onRouteChanged = onRouteChanged,
        modifier = modifier,
        content = content
    )
}

@Composable
internal fun HomeNavigationScaffold(
    navController: NavHostController,
    initialRoute: AppRoute,
    onRouteChanged: (AppRoute) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (
        route: AppRoute,
        navigateTo: (AppRoute) -> Unit,
        navigateBack: () -> Unit,
        contentModifier: Modifier
    ) -> Unit
) {
    var restoredRouteApplied by rememberSaveable(initialRoute.storedValue) {
        mutableStateOf(initialRoute == AppRoute.HOME)
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedRoute = AppRoute.fromStoredValue(backStackEntry?.destination?.route)

    LaunchedEffect(initialRoute, restoredRouteApplied, backStackEntry) {
        if (!restoredRouteApplied && backStackEntry != null) {
            navController.navigate(initialRoute.storedValue) {
                launchSingleTop = true
            }
            restoredRouteApplied = true
        }
    }
    LaunchedEffect(selectedRoute, restoredRouteApplied) {
        if (restoredRouteApplied) onRouteChanged(selectedRoute)
    }

    fun navigateTo(route: AppRoute) {
        navController.navigate(route.storedValue) {
            if (NavigationItems.items.any { it.route == route }) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                restoreState = true
            }
            launchSingleTop = true
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("NutriTrack", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { navigateTo(AppRoute.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Open settings", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 5.dp,
                tonalElevation = 0.dp
            ) {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                NavigationItems.items.forEach { item ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("bottom_nav_${item.route.storedValue}"),
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label
                            )
                        },
                        label = {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        selected = selectedRoute == item.route,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        onClick = { navigateTo(item.route) }
                    )
                }
            }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = AppRoute.HOME.storedValue,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 30 } },
            exitTransition = { fadeOut(tween(110)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(110)) }
        ) {
            AppRoute.entries.forEach { route ->
                composable(route.storedValue) {
                    content(
                        route,
                        ::navigateTo,
                        {
                            if (!navController.popBackStack()) {
                                navigateTo(AppRoute.HOME)
                            }
                        },
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .testTag("destination_${route.storedValue}")
                    )
                }
            }
        }
    }
}
