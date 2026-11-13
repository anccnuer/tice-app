package com.example.tice

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.tice.ui.nav.AppNavHost
import com.example.tice.ui.theme.TiceTheme

class MainActivity : ComponentActivity() {

    /** 上一次按下返回键的时间，用于判断是否在双击间隔内。 */
    private var lastBackPressedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 系统返回键只用于退出程序：第一次按下给出提示，2 秒内再按一次才真正退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackPressedAt <= EXIT_INTERVAL_MS) {
                    finishAffinity()
                } else {
                    lastBackPressedAt = now
                    Toast.makeText(
                        this@MainActivity,
                        R.string.press_back_again_to_exit,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
        setContent {
            TiceTheme {
                AppNavHost()
            }
        }
    }

    private companion object {
        /** 两次返回键之间的最大间隔，超过后重新计时。 */
        const val EXIT_INTERVAL_MS = 2000L
    }
}
