package app.tsundoku.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.tsundoku.reader.ui.AppRoot
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

    companion object {
        /** 调试/自动化入口：`adb shell am start -n app.tsundoku.reader/.MainActivity --es q 关键字` */
        const val EXTRA_SEARCH = "q"
    }
}
