package foxlost.miracast.sink.p2p

import android.system.Os
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One request on inherited stdin; never shares the system-UID WFD or root socket owner. */
object RootPlayerBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        var status = 1
        try {
            require(args.isEmpty()) { "Unexpected player helper arguments" }
            check(Os.getuid() == 0 && Os.geteuid() == 0) { "Player launch requires root UID 0" }
            val request = StringBuilder()
            while (true) {
                val byte = System.`in`.read()
                check(byte != -1) { "Missing player request" }
                if (byte == 10) break
                require(request.length < 96) { "Oversized player request" }
                request.append(byte.toChar())
            }
            require(System.`in`.read() == -1) { "Only one player request is permitted" }
            val fields = request.toString().split(' ')
            require(fields.size == 3 && fields.all { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }) {
                "Invalid player request"
            }
            val generation = fields[0].toLong()
            val rtp = fields[1].toInt()
            val rtcp = fields[2].toInt()
            RootBridgeProtocol.validatePlayer(generation, rtp, rtcp)
            startPlayer(generation, rtp, rtcp)
            println("OK")
            status = 0
        } catch (e: Exception) {
            val failure = (e.cause ?: e).toString().replace('\n', ' ').replace('\r', ' ').take(240)
            println("ERR $failure")
        } finally {
            System.out.flush()
            kotlin.system.exitProcess(status)
        }
    }

    private fun startPlayer(generation: Long, rtp: Int, rtcp: Int) {
        // Android 16 ActivityManagerShellCommand.runStartActivity passes a null
        // IApplicationThread to startActivityAndWait; a SystemContext does not.
        // https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/am/ActivityManagerShellCommand.java
        val process = ProcessBuilder(
            "/system/bin/cmd", "activity", "start-activity", "-W", "--user", "0",
            "-n", RootPlayerStartResult.COMPONENT, "-f", "0x10000000",
            "--el", "foxlost.miracast.SESSION_GENERATION", generation.toString(),
            "--ei", "foxlost.miracast.RTP_PORT", rtp.toString(),
            "--ei", "foxlost.miracast.RTCP_PORT", rtcp.toString(),
        ).redirectErrorStream(true).start()
        val io = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RootPlayer-result").apply { isDaemon = true }
        }
        try {
            process.outputStream.close()
            val result = io.submit<Pair<Int, String>> {
                val output = process.inputStream.bufferedReader().use { reader ->
                    val text = StringBuilder()
                    while (true) {
                        val char = reader.read()
                        if (char == -1) break
                        check(text.length < 16384) { "Oversized activity start result" }
                        text.append(char.toChar())
                    }
                    text.toString()
                }
                process.waitFor() to output
            }
            // Bound cmd itself, not only the pipe read in the ordinary APK.
            val (exitCode, output) = result.get(8, TimeUnit.SECONDS)
            RootPlayerStartResult.requireStarted(exitCode, output)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            io.shutdownNow()
        }
    }
}
