package pikto.sample

import androidx.compose.ui.window.ComposeUIViewController

/**
 * The whole iOS entry point. Swift calls this from `ContentView`.
 *
 * There is no Pikto setup here: the usage descriptions in `Info.plist` are all iOS needs, and
 * PhotoKit is reached from `commonMain`.
 */
@Suppress("unused", "FunctionName")
fun MainViewController() = ComposeUIViewController { App() }
