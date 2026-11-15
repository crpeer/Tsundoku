package app.tsundoku.reader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.tsundoku.reader.ui.AppRoot
import app.tsundoku.reader.ui.reader.ReaderKeyEvents
import app.tsundoku.reader.ui.theme.TsundokuTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TsundokuApp).container
        val initialQuery = intent?.getStringExtra(EXTRA_SEARCH)
        setContent {
            TsundokuTheme {
                AppRoot(container = container, initialSearchQuery = initialQuery)
            }
        }
    }

    /** 阅读器打开时把音量键交给它翻页（WebView 抢焦点后 Compose 收不到按键）。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = ReaderKeyEvents.handler
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {

            if (handler != null && handler(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** 调试/自动化入口：`adb shell am start -n app.tsundoku.reader/.MainActivity --es q 关键字` */
        const val EXTRA_SEARCH = "q"
    }
}
