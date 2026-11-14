package app.tsundoku.reader.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tsundoku.reader.TsundokuApp
import app.tsundoku.reader.di.AppContainer

/** 用 AppContainer 构造 ViewModel，替代 Hilt/Koin。 */
@Composable
inline fun <reified T : ViewModel> appViewModel(
    crossinline create: (AppContainer) -> T,
): T {
    val context = LocalContext.current
    val container = (context.applicationContext as TsundokuApp).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
