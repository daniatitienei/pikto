package pikto.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pikto.installPikto

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Must be before setContent. The permission prompt and the delete confirmation sheet are
        // both activity results, and Android only lets those be registered before the activity
        // reaches STARTED — which is why the library cannot arrange it for you.
        installPikto()

        setContent { App() }
    }
}
