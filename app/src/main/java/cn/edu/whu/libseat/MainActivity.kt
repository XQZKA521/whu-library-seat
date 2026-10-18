package cn.edu.whu.libseat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.edu.whu.libseat.ui.navigation.AppNavHost
import cn.edu.whu.libseat.ui.theme.LibrarySeatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as LibrarySeatApp
        setContent {
            LibrarySeatTheme {
                AppNavHost(app)
            }
        }
    }
}
