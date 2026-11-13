package com.example.tice.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.tice.ui.screens.ClassSelectScreen
import com.example.tice.ui.screens.DataCenterScreen
import com.example.tice.ui.screens.ImportScreen
import com.example.tice.ui.screens.TestHomeScreen
import com.example.tice.ui.screens.TestScreen
import com.example.tice.ui.vm.TiceViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Import : Screen("import", "数据", Icons.Filled.Home)
    data object Classes : Screen("classes", "选班", Icons.AutoMirrored.Filled.List)
    data object Batch : Screen("batch", "测试", Icons.Filled.Edit)
    data object Center : Screen("center", "中心", Icons.Filled.Settings)
    data object Test : Screen("test", "录入", Icons.Filled.Edit)
}

@Composable
fun AppNavHost() {
    val navController: NavHostController = rememberNavController()
    // 页面切换都用 navigateFlat 清空返回栈（栈里只剩当前页），再禁用导航自身的返回处理，
    // 系统返回键统一交给 MainActivity：第一次提示，2 秒内再按一次退出程序
    LaunchedEffect(navController) { navController.enableOnBackPressed(false) }
    val vm: TiceViewModel = viewModel()
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val backEntry by navController.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route

    LaunchedEffect(state.message, state.error) {
        val text = state.error.ifEmpty { state.message }
        if (text.isNotEmpty()) {
            snackbarHostState.showSnackbar(text)
            vm.clearNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf(Screen.Import, Screen.Classes, Screen.Batch, Screen.Center).forEach { screen ->
                    NavigationBarItem(
                        selected = route == screen.route || (screen is Screen.Batch && route == Screen.Test.route),
                        onClick = { navController.navigateFlat(screen.route) },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Import.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Import.route) { ImportScreen(vm, navController) }
            composable(Screen.Classes.route) { ClassSelectScreen(vm, navController) }
            composable(Screen.Batch.route) { TestHomeScreen(vm, navController) }
            composable(Screen.Test.route) { TestScreen(vm, navController) }
            composable(Screen.Center.route) { DataCenterScreen(vm, navController) }
        }
    }
}
