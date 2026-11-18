package foxlost.miracast.sink.p2p

import org.junit.Assert.assertTrue
import org.junit.Test

class RootPlayerStartResultTest {
    private val completed = """
        Starting: Intent { flg=0x10000000 cmp=foxlost.miracast.sink/.PlayerActivity (has extras) }
        Status: ok
        LaunchState: COLD
        Activity: foxlost.miracast.sink/.PlayerActivity
        TotalTime: 120
        WaitTime: 130
        Complete
    """.trimIndent()

    @Test
    fun acceptsCompletedFixedActivityStart() {
        RootPlayerStartResult.requireStarted(0, completed)
        RootPlayerStartResult.requireStarted(0, completed.replace(
            "Activity: foxlost.miracast.sink/.PlayerActivity",
            "Activity: ${RootPlayerStartResult.COMPONENT}",
        ))
    }

    @Test
    fun acceptsVerifiedExistingActivityDeliveryAndTaskBringToFront() {
        listOf(
            "Warning: Activity not started, intent has been delivered to currently running top-most instance.",
            "Warning: Activity not started, its current task has been brought to the front",
        ).forEach { warning -> RootPlayerStartResult.requireStarted(0, "$warning\n$completed") }
    }

    @Test
    fun rejectsNonzeroExitEvenWithSuccessfulLookingOutput() {
        reject(1, completed)
        reject(-1, completed)
    }

    @Test
    fun rejectsErrorsTimeoutsAndIncompleteOrUnrelatedResultsEvenAtExitZero() {
        listOf(
            "", "OK", "Starting: Intent { cmp=foxlost.miracast.sink/.PlayerActivity }",
            "Error type 3\nError: Activity class does not exist.",
            "java.lang.SecurityException: Permission Denial: unknown caller\n$completed",
            "$completed\nError: Activity not started, unknown error code -94",
            completed.replace("Status: ok", "Status: timeout"),
            completed.replace("Status: ok", "Status: not-ok"),
            completed.replace("Status: ok", ""),
            completed.replace("Complete", ""),
            completed.replace("Activity: foxlost.miracast.sink/.PlayerActivity", "Activity: other/.PlayerActivity"),
            completed.replace("Activity: foxlost.miracast.sink/.PlayerActivity", ""),
            "$completed\nStatus: ok", "$completed\nStatus: timeout", "$completed\nComplete",
            "$completed\nActivity: foxlost.miracast.sink/.PlayerActivity",
        ).forEach { output -> reject(0, output) }
    }

    @Test
    fun rejectsAospZeroExitWhenActivityWasNotActuallyStarted() {
        listOf(
            "Warning: Activity not started because the  current activity is being kept for the user.",
            "Warning: Activity not started because intent should be handled by the caller",
            "Warning: unknown vendor launch restriction",
        ).forEach { warning -> reject(0, "$warning\n$completed") }
    }

    private fun reject(exitCode: Int, output: String) {
        var rejected = false
        try { RootPlayerStartResult.requireStarted(exitCode, output) }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue("exit=$exitCode output=$output", rejected)
    }
}
