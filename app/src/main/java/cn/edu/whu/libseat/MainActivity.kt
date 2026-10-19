package cn.edu.whu.libseat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cn.edu.whu.libseat.ui.navigation.AppNavHost
import cn.edu.whu.libseat.ui.quickbook.QuickBookRequest
import cn.edu.whu.libseat.ui.theme.LibrarySeatTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** 小部件点“预约”带过来的请求，交给导航层消费一次。 */
    private val quickBookRequest = MutableStateFlow<QuickBookRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 只有首次创建才消费，避免旋转 / 重建时重复提交预约
        if (savedInstanceState == null) consumeIntent(intent)
        val app = application as LibrarySeatApp
        setContent {
            LibrarySeatTheme {
                val request by quickBookRequest.collectAsState()
                AppNavHost(
                    app = app,
                    quickBookRequest = request,
                    onQuickBookConsumed = { quickBookRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent?.action != ACTION_QUICK_BOOK) return
        val seatId = intent.getLongExtra(EXTRA_SEAT_ID, 0L)
        val date = intent.getStringExtra(EXTRA_DATE).orEmpty()
        val start = intent.getIntExtra(EXTRA_START, -1)
        val end = intent.getIntExtra(EXTRA_END, -1)
        if (seatId <= 0L || date.isBlank() || start < 0 || end <= start) return
        quickBookRequest.value = QuickBookRequest(seatId, date, start, end)
        intent.action = null
    }

    companion object {
        const val ACTION_QUICK_BOOK = "cn.edu.whu.libseat.action.QUICK_BOOK"
        const val EXTRA_SEAT_ID = "extra_seat_id"
        const val EXTRA_DATE = "extra_date"
        const val EXTRA_START = "extra_start"
        const val EXTRA_END = "extra_end"
    }
}
