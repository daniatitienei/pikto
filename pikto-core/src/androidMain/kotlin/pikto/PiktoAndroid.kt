package pikto

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.startup.Initializer
import kotlinx.coroutines.CompletableDeferred

/**
 * Wires Pikto to [this] activity, so it can show the system permission prompt and the delete
 * confirmation sheet. **Call it from `onCreate`, before `setContent`.**
 *
 * ```kotlin
 * class MainActivity : ComponentActivity() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         installPikto()
 *         setContent { App() }
 *     }
 * }
 * ```
 *
 * Both of those are activity results, and Android only lets a launcher be registered before the
 * activity reaches STARTED, which is why this is a call you make rather than something the
 * library can arrange for itself. Everything unregisters on destroy, so it is safe across
 * configuration changes and safe to call on more than one activity.
 *
 * Reading the library needs none of this. Skip it if you never call
 * [PhotoLibrary.requestPermission] or [PhotoLibrary.delete]. Those are the only two that throw
 * without it.
 */
public fun ComponentActivity.installPikto() {
    PiktoAndroid.attach(applicationContext)

    val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> ActivityBridge.completePermissions(granted) }

    val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> ActivityBridge.completeIntentSender(result.resultCode == Activity.RESULT_OK) }

    ActivityBridge.register(
        onRequestPermissions = { permissionLauncher.launch(it) },
        onLaunchIntentSender = {
            intentSenderLauncher.launch(IntentSenderRequest.Builder(it).build())
        },
    )

    lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                ActivityBridge.unregister()
            }
        },
    )
}

/**
 * The application context, captured once at process start by [PiktoInitializer] so that
 * [PhotoLibrary] can be built from `commonMain` with no arguments.
 */
internal object PiktoAndroid {

    private var applicationContext: Context? = null

    fun attach(context: Context) {
        applicationContext = context.applicationContext
    }

    fun requireContext(): Context = applicationContext ?: error(
        "Pikto has no application context. The androidx.startup initializer that normally " +
            "captures it is missing from the merged manifest. Either re-enable it, call " +
            "installPikto() on your activity, or build the library with PhotoLibrary(context).",
    )
}

/**
 * Captures the application context at process start.
 *
 * Remove it by merging `<meta-data android:name="pikto.PiktoInitializer" tools:node="remove" />`
 * into the androidx.startup provider, and construct the library with `PhotoLibrary(context)`.
 */
public class PiktoInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        PiktoAndroid.attach(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * The one place a suspending call can reach the activity result APIs.
 *
 * A pending request is completed with a refusal when the activity goes away, so a rotation during
 * the permission sheet resumes the caller instead of leaving it suspended forever.
 */
internal object ActivityBridge {

    private var onRequestPermissions: ((Array<String>) -> Unit)? = null
    private var onLaunchIntentSender: ((IntentSender) -> Unit)? = null

    private var pendingPermissions: CompletableDeferred<Map<String, Boolean>>? = null
    private var pendingIntentSender: CompletableDeferred<Boolean>? = null

    fun register(
        onRequestPermissions: (Array<String>) -> Unit,
        onLaunchIntentSender: (IntentSender) -> Unit,
    ) {
        this.onRequestPermissions = onRequestPermissions
        this.onLaunchIntentSender = onLaunchIntentSender
    }

    fun unregister() {
        onRequestPermissions = null
        onLaunchIntentSender = null
        completePermissions(emptyMap())
        completeIntentSender(false)
    }

    fun completePermissions(granted: Map<String, Boolean>) {
        pendingPermissions?.complete(granted)
        pendingPermissions = null
    }

    fun completeIntentSender(confirmed: Boolean) {
        pendingIntentSender?.complete(confirmed)
        pendingIntentSender = null
    }

    suspend fun requestPermissions(permissions: Array<String>): Map<String, Boolean> {
        val launch = onRequestPermissions ?: missingInstall("requesting the photo permission")
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        pendingPermissions = deferred
        launch(permissions)
        return deferred.await()
    }

    suspend fun confirmWithUser(intentSender: IntentSender): Boolean {
        val launch = onLaunchIntentSender ?: missingInstall("deleting photos")
        val deferred = CompletableDeferred<Boolean>()
        pendingIntentSender = deferred
        launch(intentSender)
        return deferred.await()
    }

    private fun missingInstall(what: String): Nothing = error(
        "Pikto is not installed on an activity, and $what needs one. Call installPikto() from " +
            "your ComponentActivity's onCreate, before setContent.",
    )
}
