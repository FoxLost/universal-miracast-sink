package foxlost.miracast.sink

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Retains an in-flight service acknowledgement across activity recreation. */
class NavigationReset(application: Application) : AndroidViewModel(application) {
    var pending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var completedAction by mutableStateOf<String?>(null)
        private set
    var requestedAction: String? = null
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var requestId = 0L
    private val timeout = Runnable {
        if (pending) fail("Cleanup acknowledgement timed out. Retry before starting or leaving this page.")
    }

    fun request(action: String) {
        if (pending || completedAction != null) return
        pending = true
        error = null
        requestedAction = action
        val id = ++requestId
        val receiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (id != requestId || !pending) return
                handler.removeCallbacks(timeout)
                pending = false
                if (resultCode == MiracastService.RESET_OK) completedAction = action
                else fail("Connection cleanup failed. Retry; the sink has not been marked ready.")
            }
        }
        handler.postDelayed(timeout, 65_000L)
        try {
            // A foreground activity may start a stopped service for cleanup only.
            // The service enters foreground immediately, never starts a sink, and
            // retires itself only after root/group and media cleanup finish.
            getApplication<Application>().startForegroundService(
                Intent(getApplication(), MiracastService::class.java)
                    .setAction(MiracastService.ACTION_RESET_FOR_NAVIGATION)
                    .putExtra(MiracastService.EXTRA_RESET_RECEIVER, receiver),
            )
        } catch (e: Exception) {
            fail("Unable to request cleanup: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(message: String) {
        handler.removeCallbacks(timeout)
        pending = false
        error = message
    }

    fun consume(): String? {
        val action = completedAction ?: return null
        completedAction = null
        requestedAction = null
        return action
    }

    override fun onCleared() {
        handler.removeCallbacks(timeout)
    }
}

@Composable
internal fun ComponentActivity.ResetCompletion(reset: NavigationReset, onComplete: (String) -> Unit) {
    val callback by rememberUpdatedState(onComplete)
    fun deliver() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            reset.consume()?.let(callback)
        }
    }
    LaunchedEffect(reset.completedAction) { deliver() }
    DisposableEffect(lifecycle, reset) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) deliver()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
