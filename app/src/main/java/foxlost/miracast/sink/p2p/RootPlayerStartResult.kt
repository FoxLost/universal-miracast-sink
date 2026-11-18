package foxlost.miracast.sink.p2p

/** Accepts only a completed -W launch of our fixed activity, not cmd's exit code alone. */
internal object RootPlayerStartResult {
    const val COMPONENT = "foxlost.miracast.sink/foxlost.miracast.sink.PlayerActivity"
    private const val SHORT_COMPONENT = "foxlost.miracast.sink/.PlayerActivity"

    fun requireStarted(exitCode: Int, output: String) {
        fun rejected(): Nothing = throw IllegalStateException(
            "Activity start rejected (exit=$exitCode): ${output.replace('\n', ' ').replace('\r', ' ').take(240)}"
        )
        if (exitCode != 0) rejected()
        var statusSeen = false
        var activitySeen = false
        var complete = false
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Error") || line.contains("Exception")) rejected()
            when {
                line.startsWith("Warning:") -> {
                    // AOSP also returns zero for SWITCHES_CANCELED and
                    // RETURN_INTENT_TO_CALLER. Neither actually starts our player.
                    if (line != "Warning: Activity not started, intent has been delivered to currently running top-most instance." &&
                        line != "Warning: Activity not started, its current task has been brought to the front") rejected()
                }
                line.startsWith("Status:") -> {
                    if (statusSeen || line != "Status: ok") rejected()
                    statusSeen = true
                }
                line.startsWith("Activity:") -> {
                    if (activitySeen || (line != "Activity: $SHORT_COMPONENT" && line != "Activity: $COMPONENT")) rejected()
                    activitySeen = true
                }
                line == "Complete" -> {
                    if (complete) rejected()
                    complete = true
                }
            }
        }
        if (!statusSeen || !activitySeen || !complete) rejected()
    }
}
