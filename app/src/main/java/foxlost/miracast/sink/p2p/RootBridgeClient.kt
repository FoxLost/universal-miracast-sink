package foxlost.miracast.sink.p2p

import android.content.Context
import java.io.BufferedReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Owned by one sink session. All commands cross a private inherited stdio pipe. */
internal class RootBridgeClient(private val context: Context) : AutoCloseable {
    companion object {
        private val cleanupBarrier = RootCleanupBarrier()
        fun awaitCleanup(timeoutMs: Long): Boolean = cleanupBarrier.awaitClean(timeoutMs)
    }
    private val processes = mutableSetOf<Process>()
    private val restorationOwners = mutableSetOf<Process>()
    private var cleanupOwner: RootCleanupBarrier.Owner? = null
    @Volatile private var closed = false
    val closedAndRestored = java.util.concurrent.CompletableFuture<Boolean>()
    private var frameworkProcess: Process? = null
    private var frameworkChannel: RootFrameworkChannel? = null
    private var frameworkGeneration = -1L
    @Volatile private var appConnect: P2pConnectOperation? = null
    private val frameworkLock = Any()
    private val socketLock = Any()
    private var socketProcess: Process? = null
    private var socketReader: BufferedReader? = null
    @Volatile private var socketReady = false
    private val io = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "RootBridge-io").apply { isDaemon = true }
    }

    private fun launch(entry: String): Process {
        val owner = synchronized(processes) {
            check(!closed) { "Root session cancelled" }
            cleanupOwner ?: cleanupBarrier.reserve().also { cleanupOwner = it }
        }
        owner.awaitPrevious()
        return synchronized(processes) {
            check(!closed) { "Root session cancelled" }
            val command = "CLASSPATH=${RootBridgeProtocol.shellQuote(context.packageCodePath)} app_process / foxlost.miracast.sink.p2p.$entry"
            ProcessBuilder("su", "-c", command).start().also { process ->
                processes += process
                if (entry == "RootFrameworkBridge") frameworkProcess = process
                if (entry == "RootSocketBridge") socketProcess = process
                thread(name = "RootBridge-stderr", isDaemon = true) {
                    try {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.forEachLine { android.util.Log.w("MiracastRoot", it.take(300)) }
                        }
                    } catch (e: java.io.IOException) {
                        // Android can interrupt this read when Process closes its pipes on exit.
                        if (!closed && (process.isAlive || process.exitValue() != 0)) {
                            android.util.Log.w("MiracastRoot", "Root diagnostic pipe failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun send(process: Process, command: String, changesState: Boolean = false) = synchronized(processes) {
        check(!closed) { "Root session cancelled" }
        if (changesState) restorationOwners += process
        process.outputStream.write(command.toByteArray())
        process.outputStream.flush()
    }

    private fun <T> bounded(process: Process, timeoutSeconds: Long = 12, action: () -> T): T {
        val result = io.submit<T> { action() }
        try {
            return result.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            // A rejected optional command must not tear down the WFD owner.
            val cause = e.cause ?: e
            if (cause !is java.net.SocketTimeoutException) throw cause
            abortOperation(process, cause)
        } catch (e: Exception) {
            abortOperation(process, e)
        } finally {
            result.cancel(true)
        }
    }

    private fun abortOperation(process: Process, cause: Throwable): Nothing {
        try { process.outputStream.close() } catch (_: Exception) {}
        if (process !== frameworkProcess) {
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroy()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroy()
            }
        }
        // The framework child observes EOF cooperatively. Killing su before its child
        // restores STA can sever stdout and lose the only trustworthy cleanup proof.
        if (cause is InterruptedException) Thread.currentThread().interrupt()
        throw IllegalStateException(if (cause is InterruptedException) "Root helper operation cancelled" else "Root helper operation timed out; restoration must complete before reuse", cause)
    }

    fun start(generation: Long, port: Int, throughput: Int, disconnect: Boolean, band: P2pBand = P2pBand.AUTO): Boolean = synchronized(frameworkLock) {
        require(port in 1..65535 && throughput in 0..65535)
        check(frameworkProcess == null) { "Root framework already started" }
        val process = launch("RootFrameworkBridge")
        val channel = RootFrameworkChannel(process.inputStream)
        frameworkChannel = channel
        val ready = bounded(process, timeoutSeconds = 120) {
            RootBridgeProtocol.requireOk(channel.readReply())
            send(process, "START $generation $port $throughput $disconnect ${band.name}\n", changesState = true)
            val reply = channel.readReply(startup = true)
            // WFD-only failure permits the fixed supplicant fallback; identity/STA failures do not.
            if (reply.startsWith("WFD_FAIL ")) false else {
                RootBridgeProtocol.requireOk(reply)
                true
            }
        }
        frameworkGeneration = generation
        if (band != P2pBand.AUTO) {
            // START acknowledged the framework-owned Auto baseline. The socket
            // owner restores it on EOF, including an unacknowledged mutation.
            check(supplicant("P2P_SET disallow_freq ${band.excludedFrequencies}")) {
                "The supplicant cannot enforce requested band ${band.name}"
            }
        }
        ready
    }

    fun listening(enabled: Boolean): Unit = synchronized(frameworkLock) {
        val process = checkNotNull(frameworkProcess) { "Root framework not started" }
        bounded(process) {
            send(process, if (enabled) "LISTEN\n" else "STOP_LISTEN\n")
            val reply = checkNotNull(frameworkChannel).readReply()
            if (reply == "UNSUPPORTED") throw UnsupportedOperationException("Framework listen unsupported")
            RootBridgeProtocol.requireOk(reply)
        }
    }

    fun availability(available: Boolean): Boolean = synchronized(frameworkLock) {
        check(!closed) { "Root session cancelled" }
        val process = checkNotNull(frameworkProcess) { "Root framework not started" }
        bounded(process) {
            send(process, "AVAILABLE $available\n")
            val reply = checkNotNull(frameworkChannel).readReply()
            if (reply.startsWith("WFD_FAIL ")) false else {
                RootBridgeProtocol.requireOk(reply)
                true
            }
        }
    }

    /** Include the app channel's CONNECT obligation in this session's global cleanup proof. */
    fun trackAppConnect(operation: P2pConnectOperation) = synchronized(processes) {
        check(!closed && frameworkGeneration == operation.generation) { "Stale app CONNECT owner" }
        check(appConnect?.canReplace() != false) { "Previous app CONNECT still owned" }
        appConnect = operation
    }

    fun connectPeer(generation: Long, address: String, goIntent: Int): Unit = synchronized(frameworkLock) {
        RootBridgeProtocol.validatePeer(generation, address, goIntent)
        check(!closed) { "Root session cancelled" }
        check(frameworkGeneration == generation) { "Stale peer session" }
        val process = checkNotNull(frameworkProcess) { "Root framework not started" }
        bounded(process) {
            send(process, "CONNECT $generation $address $goIntent\n", changesState = true)
            RootBridgeProtocol.requireOk(checkNotNull(frameworkChannel).readReply())
        }
    }

    /** Read-only inspection through the existing owner; cleanup must never launch a helper. */
    fun inspectPeer(generation: Long, address: String): RootPendingConnect.State = synchronized(frameworkLock) {
        RootBridgeProtocol.validatePeer(generation, address, 0)
        check(!closed && frameworkGeneration == generation) { "Stale peer inspection" }
        val process = checkNotNull(frameworkProcess) { "Root framework not started" }
        bounded(process, timeoutSeconds = 30) {
            send(process, "INSPECT_CONNECT $generation $address\n")
            val reply = checkNotNull(frameworkChannel).readReply()
            check(reply.startsWith("STATE ")) { "Peer inspection failed: $reply" }
            RootPendingConnect.State.valueOf(reply.removePrefix("STATE "))
        }
    }

    fun launchPlayer(generation: Long, rtp: Int, rtcp: Int): Unit = synchronized(frameworkLock) {
        RootBridgeProtocol.validatePlayer(generation, rtp, rtcp)
        check(!closed && frameworkProcess != null && frameworkGeneration == generation) { "Stale player session" }
        val process = launch("RootPlayerBridge")
        val result = io.submit {
            process.outputStream.use {
                it.write("$generation $rtp $rtcp\n".toByteArray(Charsets.US_ASCII))
                it.flush()
            }
            val reply = process.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                while (true) {
                    val byte = reader.read()
                    if (byte == -1) break
                    check(text.length < 512) { "Oversized root player reply" }
                    text.append(byte.toChar())
                }
                text.toString().trimEnd('\n', '\r')
            }
            check(process.waitFor() == 0) { "Root player launch failed: ${reply.take(240)}" }
            RootBridgeProtocol.requireOk(reply)
        }
        try {
            result.get(12, TimeUnit.SECONDS)
            check(!closed && frameworkGeneration == generation) { "Stale player session" }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("Root player launch timed out", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Root player launch cancelled", e)
        } finally {
            result.cancel(true)
            try { process.outputStream.close() } catch (_: Exception) {}
            if (process.isAlive) process.destroyForcibly()
            synchronized(processes) { processes -= process }
        }
    }

    private fun rootOperation(operation: String): String = synchronized(socketLock) {
        check(!closed) { "Root session cancelled" }
        val process = socketProcess ?: launch("RootSocketBridge").also {
            socketProcess = it
            socketReader = it.inputStream.bufferedReader()
            bounded(it) {
                send(it, "SESSION\n")
                RootBridgeProtocol.requireOk(socketReader?.readLine() ?: "EOF during socket initialization")
                socketReady = true
            }
        }
        bounded(process) {
            send(process, "$operation\n", changesState = operation.startsWith("COMMAND "))
            val result = socketReader?.readLine() ?: error("Root socket owner EOF")
            if (result == "UNCERTAIN") throw SupplicantOutcomeUnknown()
            check(!result.startsWith("ERR ")) { result.take(1024) }
            result
        }
    }

    fun supplicant(command: String): Boolean {
        require(RootBridgeProtocol.validSupplicant(command)) { "Unsupported supplicant operation" }
        return try {
            rootOperation("COMMAND $command") == "OK"
        } catch (e: Exception) {
            android.util.Log.w("MiracastRoot", "Supplicant failed: ${e.message}")
            false
        }
    }

    /** Cleanup cannot create a socket owner or alter extended listen owned by another session. */
    fun clearExtendedListen() = synchronized(socketLock) {
        if (closed || !socketReady) return@synchronized
        try {
            RootBridgeProtocol.requireOk(rootOperation("CLEAR_EXT_LISTEN"))
        } catch (e: Exception) {
            android.util.Log.w("MiracastRoot", "Extended listen cleanup failed: ${e.message}")
        }
    }

    fun readArp(): String? {
        val reply = rootOperation("ARP")
        check(reply.startsWith("ARP ")) { "Invalid ARP reply" }
        val table = String(java.util.Base64.getDecoder().decode(reply.removePrefix("ARP ")), Charsets.UTF_8)
        check(table.startsWith("IP address")) { "Invalid ARP table" }
        return table
    }

    fun readPeerInterfaceAddress(interfaceName: String, peerMac: String): String? {
        RootBridgeProtocol.validatePeerLookup(interfaceName, peerMac)
        val reply = rootOperation("PEER_INTERFACE $interfaceName $peerMac")
        if (reply == "NOT_FOUND" || reply == "LOOKUP_UNAVAILABLE") return null
        check(reply.startsWith("PEER_INTERFACE ")) { "Invalid peer interface reply" }
        val address = reply.removePrefix("PEER_INTERFACE ")
        check(RootBridgeProtocol.validUnicastMac(address)) { "Invalid peer interface address" }
        return address
    }

    fun monitor(): Process {
        val process = launch("SupplicantEventMonitor")
        bounded(process) {
            val ready = StringBuilder()
            while (ready.length < 256) {
                val byte = process.inputStream.read()
                check(byte != -1) { "Monitor initialization failed" }
                if (byte == 10) break
                ready.append(byte.toChar())
            }
            check(ready.toString() == "MONITOR_READY") { "Monitor ATTACH was not acknowledged" }
        }
        return process
    }

    fun releaseMonitor(process: Process) {
        synchronized(processes) { processes -= process }
    }

    override fun close() {
        val owned: List<Process>
        val restoring: Set<Process>
        val owner: RootCleanupBarrier.Owner?
        synchronized(processes) {
            if (closed) return
            closed = true
            // Driver cleanup must finish before framework teardown restores STA/mode.
            owned = processes.sortedBy { if (it === socketProcess) 0 else if (it === frameworkProcess) 1 else 2 }
            restoring = restorationOwners.toSet()
            owner = cleanupOwner
            processes.clear()
        }
        thread(name = "RootBridge-close", isDaemon = true) {
            var clean = true
            try {
                owned.forEach { process ->
                    try {
                        try { process.outputStream.close() } catch (_: Exception) {}
                        if (process === frameworkProcess && process in restoring) {
                            // Deliberately keep the barrier pending while the real helper restores.
                            // New startup remains bounded by awaitPrevious(); progress/timeout is
                            // neither success nor a permanent cleanup failure.
                            val restored = checkNotNull(frameworkChannel).cleanup.get()
                            if (!restored) {
                                clean = false
                                android.util.Log.e("MiracastRoot", "Framework exited without successful restoration proof")
                            }
                            if (!process.waitFor(5, TimeUnit.SECONDS) && restored) process.destroy()
                        } else if (!process.waitFor(30, TimeUnit.SECONDS)) {
                            process.destroy()
                            if (process in restoring) clean = false
                            android.util.Log.e("MiracastRoot", "Root helper teardown timed out; restorationRequired=${process in restoring}")
                        } else if (process in restoring && process.exitValue() != 0) {
                            clean = false
                            android.util.Log.e("MiracastRoot", "Root restoration helper exited status=${process.exitValue()}")
                        }
                    } catch (e: Exception) {
                        if (process in restoring) clean = false
                        android.util.Log.e("MiracastRoot", "Root helper teardown failed", e)
                    }
                }
            } finally {
                if (appConnect?.canReplace() == false) {
                    clean = false
                    android.util.Log.e("MiracastRoot", "App-channel CONNECT exited without settlement proof")
                }
                io.shutdownNow()
                owner?.complete(clean)
                closedAndRestored.complete(clean)
            }
        }
    }
}
