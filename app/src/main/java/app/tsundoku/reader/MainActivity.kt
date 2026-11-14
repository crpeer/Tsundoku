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
        setContent {
            TsundokuTheme {
                AppRoot(container)
            }
        }
    }
}
