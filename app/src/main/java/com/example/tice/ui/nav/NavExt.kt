package com.example.tice.ui.nav

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * 页面切换一律清空返回栈，保证返回栈里始终只有当前页面。
 *
 * 这样系统返回键不会被 Navigation 组件解释成「回到上一页」，
 * 而是交给 Activity 的回调：第一次提示，2 秒内再按一次退出程序。
 */
fun NavController.navigateFlat(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { inclusive = true }
        launchSingleTop = true
    }
}
