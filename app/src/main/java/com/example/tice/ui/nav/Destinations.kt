package com.example.tice.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.tice.R

/** 底部导航的页面定义；标签使用字符串资源，便于统一维护与无障碍朗读。 */
sealed class Screen(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    data object Import : Screen("import", R.string.nav_data, Icons.Filled.Home)
    data object Classes : Screen("classes", R.string.nav_classes, Icons.AutoMirrored.Filled.List)
    data object Batch : Screen("batch", R.string.nav_test, Icons.Filled.Edit)
    data object Center : Screen("center", R.string.nav_center, Icons.Filled.Settings)
    data object Test : Screen("test", R.string.nav_record, Icons.Filled.Edit)
}
