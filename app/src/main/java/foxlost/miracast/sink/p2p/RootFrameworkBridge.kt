package foxlost.miracast.sink.p2p

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.HandlerThread
import android.os.Looper
import android.system.Os
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Launched only by the session client, never at boot. No sockets or exported service. */
object RootFrameworkBridge {
    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(args: Array<String>) {
        var session: Session? = null
        try {
            if (args.isEmpty()) {
                // app_process has already opened Binder as root before entering main.
                // Exec a fresh VM after dropping credentials so Binder also opens as system.
                RootIdentity.becomeSystem()
                Os.execv("/system/bin/app_process", arrayOf(
                    "/system/bin/app_process", "/",
                    "foxlost.miracast.sink.p2p.RootFrameworkBridge", "--system-identity"
                ))
                error("System identity exec unexpectedly returned")
            }
            check(args.contentEquals(arrayOf("--system-identity"))) { "Invalid helper arguments" }
            RootIdentity.requireSystem()
            // app_process is not an APK component: open hidden APIs only in this helper VM.
            val runtime = Class.forName("dalvik.system.VMRuntime")
            runtime.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime.getDeclaredMethod("getRuntime").invoke(null), arrayOf("L"))
            // The new VM opened Binder with the verified system credentials.
            // Construct SystemContext only after validating every identity invariant.
            Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            // WifiP2pService checks the Binder UID and package attribution, not the APK cert.
            val thread = activityThread.getDeclaredMethod("systemMain").invoke(null)
            val context = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            check(Os.getuid() == 1000 && context.opPackageName == "android") { "Invalid Binder identity" }
            val inbox = RootCommandInbox(System.`in`)
            val activeSession = Session(context, inbox)
            session = activeSession
            reply("OK")
            while (true) {
                val line = inbox.next() ?: break
                try {
                    val fields = line.split(' ')
                    inbox.checkActive()
                    when (fields[0]) {
                        "START" -> {
                            require(fields.size == 6)
                            val generation = fields[1].toLong().also { require(it >= 0) }
                            val port = fields[2].toInt().also { require(it in 1..65535) }
                            val throughput = fields[3].toInt().also { require(it in 0..65535) }
                            val disconnect = fields[4].toBooleanStrict()
                            val band = P2pBand.entries.firstOrNull { it.name == fields[5] }
                                ?: error("Invalid P2P band")
                            activeSession.prepare(generation, disconnect, band)
                            try {
                                activeSession.wfd(port, throughput, true)
                                reply("OK")
                            } catch (e: Exception) {
                                if (e is java.util.concurrent.TimeoutException) throw e
                                reply("WFD_FAIL ${error(e)}")
                            }
                        }
                        "AVAILABLE" -> {
                            require(fields.size == 2)
                            try {
                                activeSession.availability(fields[1].toBooleanStrict())
                                reply("OK")
                            } catch (e: Exception) {
                                if (e is java.util.concurrent.TimeoutException) throw e
                                reply("WFD_FAIL ${error(e)}")
                            }
                        }
                        "LISTEN" -> { require(fields.size == 1); activeSession.listen(true); reply("OK") }
                        "STOP_LISTEN" -> { require(fields.size == 1); activeSession.listen(false); reply("OK") }
                        "CONNECT" -> {
                            require(fields.size == 4)
                            activeSession.connectPeer(fields[1].toLong(), fields[2], fields[3].toInt())
                            reply("OK")
                        }
                        "INSPECT_CONNECT" -> {
                            require(fields.size == 3)
                            reply("STATE ${activeSession.inspectPeer(fields[1].toLong(), fields[2]).name}")
                        }
                        else -> error("Unsupported operation")
                    }
                } catch (e: Exception) {
                    reply(if (e is UnsupportedOperationException && !line.startsWith("START ")) "UNSUPPORTED" else "ERR ${error(e)}")
                }
            }
        } catch (e: Exception) { reply("ERR ${error(e)}") }
        finally {
            var status = 0
            try { session?.close() } catch (e: Exception) { System.err.println("Cleanup failed: ${error(e)}"); status = 1 }
            reply(if (status == 0) "CLEANUP OK" else "CLEANUP ERR")
            kotlin.system.exitProcess(status)
        }
    }

    private fun error(e: Exception): String = (e.cause ?: e).toString().replace('\n', ' ').take(240)
    private fun reply(value: String) { println(value); System.out.flush() }

    @Suppress("DEPRECATION", "MissingPermission")
    private class Session(private val context: Context, private val inbox: RootCommandInbox) : AutoCloseable {
        private val callbackThread = HandlerThread("RootP2P-callbacks").apply { start() }
        private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        private val channel = manager.initialize(context, callbackThread.looper, null)
        private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        private val staIsolation = RootStaIsolation(RootStaPolicy(wifi))
        private var prepared = false
        private var modeChanged = false
        private var wfdChanged = false
        private var listening = false
        private var generation = -1L
        private var controlPort = 7236
        private var maxThroughput = 0
        private val pendingConnect = RootPendingConnect()

        fun prepare(generation: Long, disconnect: Boolean, band: P2pBand) {
            check(!prepared) { "Session already prepared" }
            prepared = true
            this.generation = generation
            if (band == P2pBand.GHZ_5 && !wifi.is5GHzBandSupported) {
                throw UnsupportedOperationException("This device does not support 5 GHz Wi-Fi")
            }
            val total = if (disconnect) 2 else 1
            var completed = 0
            reply("PROGRESS START $completed $total")
            if (disconnect) {
                staIsolation.acquire(inbox::checkActive)
                reply("PROGRESS START ${++completed} $total")
            }
            inbox.checkActive()
            mode(2)
            reply("PROGRESS START ${++completed} $total")
            inbox.checkActive()
            if (band != P2pBand.AUTO) {
                // An explicit forced-band session takes ownership of channel
                // policy. Confirm Auto first; the socket owner narrows it and
                // restores that baseline on EOF. No regulatory settings change.
                action("setWifiP2pChannels", 0, 0)
                inbox.checkActive()
            }
        }

        private fun mode(value: Int) {
            val method = try {
                WifiP2pManager::class.java.getMethod("setMiracastMode", WifiP2pManager.Channel::class.java, Int::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) {
                null
            }
            val legacy = if (method == null) WifiP2pManager::class.java.getMethod("setMiracastMode", Int::class.javaPrimitiveType) else null
            // Once dispatched, an exception does not prove the Binder mutation was unapplied.
            if (value != 0) modeChanged = true
            if (method != null) method.invoke(manager, channel, value) else legacy!!.invoke(manager, value)
            modeChanged = value != 0
        }

        private fun action(
            name: String,
            vararg values: Any,
            uncertain: () -> Unit = {},
            beforeDispatch: () -> Unit = {},
            rejected: () -> Unit = {},
        ) {
            val latch = CountDownLatch(1)
            var failure: Int? = null
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() { latch.countDown() }
                override fun onFailure(reason: Int) { failure = reason; latch.countDown() }
            }
            val arguments = arrayOf(channel, *values, listener)
            val method = manager.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == arguments.size
            } ?: throw UnsupportedOperationException("Framework operation unavailable: $name")
            beforeDispatch()
            try {
                method.invoke(manager, *arguments)
            } catch (e: Exception) {
                uncertain()
                throw e
            }
            val acknowledged = try {
                latch.await(6, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                uncertain()
                Thread.currentThread().interrupt()
                throw e
            }
            if (!acknowledged) {
                uncertain()
                throw java.util.concurrent.TimeoutException("$name callback timeout")
            }
            if (failure != null) rejected()
            if (failure == WifiP2pManager.P2P_UNSUPPORTED) throw UnsupportedOperationException("$name unsupported")
            check(failure == null) { "$name failed reason=$failure" }
        }

        fun wfd(port: Int, throughput: Int, enabled: Boolean) {
            controlPort = port
            maxThroughput = throughput
            writeWfd(port, throughput, enabled, true)
        }

        private fun writeWfd(port: Int, throughput: Int, enabled: Boolean, available: Boolean) {
            val type = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val info = type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(if (enabled) if (available) 0x11 else 0x01 else 0, port, throughput)
            type.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(info, enabled)
            val name = if (manager.javaClass.methods.any { it.name == "setWfdInfo" }) "setWfdInfo" else "setWFDInfo"
            // Local unavailability and an explicit rejection create no new obligation.
            // A dispatched call with a lost callback may still apply.
            action(name, info, uncertain = { if (enabled) wfdChanged = true })
            wfdChanged = enabled
            System.err.println(
                "sink-local WFD ACK generation=$generation enabled=$enabled available=${enabled && available} " +
                    "deviceInfo=${if (enabled) if (available) 0x11 else 0x01 else 0} " +
                    "controlPort=$port maxThroughput=$throughput",
            )
            // Read back the framework's local device only after setWfdInfo ACK.
            // Diagnostics must never turn an acknowledged advertisement into failure.
            try { logLocalWfd() } catch (e: Exception) {
                System.err.println("sink-local WFD readback unavailable generation=$generation: ${error(e)}")
            }
        }

        private fun <T> query(name: String, request: ((T) -> Unit) -> Unit): T {
            val result = java.util.concurrent.CompletableFuture<T>()
            request { result.complete(it) }
            return try { result.get(6, TimeUnit.SECONDS) }
            catch (e: java.util.concurrent.TimeoutException) {
                throw java.util.concurrent.TimeoutException("$name callback timeout")
            }
        }

        private fun logLocalWfd() {
            val observedGeneration = generation
            // Readback is diagnostic only; a missing callback must not delay
            // START, availability changes, or EOF restoration.
            manager.requestDeviceInfo(channel) { device ->
                try {
                    checkNotNull(device) { "Local device unavailable" }
                    val info = device.javaClass.getField("wfdInfo").get(device)
                        ?: error("Local WFD info unavailable")
                    fun value(name: String) = info.javaClass.getMethod(name).invoke(info)
                    val enabled = try { value("isEnabled") } catch (_: NoSuchMethodException) { value("isWfdEnabled") }
                    System.err.println(
                        "sink-local WFD readback generation=$observedGeneration enabled=$enabled " +
                            "available=${value("isSessionAvailable")} deviceInfo=${value("getDeviceInfo")} " +
                            "controlPort=${value("getControlPort")} maxThroughput=${value("getMaxThroughput")}",
                    )
                } catch (e: Exception) {
                    System.err.println("sink-local WFD readback unavailable generation=$observedGeneration: ${error(e)}")
                }
            }
        }

        fun inspectPeer(generation: Long, address: String): RootPendingConnect.State {
            RootBridgeProtocol.validatePeer(generation, address, 0)
            check(prepared && this.generation == generation) { "Stale peer inspection" }
            return inspectConnect(address)
        }

        private fun inspectConnect(address: String): RootPendingConnect.State {
            val group = query<android.net.wifi.p2p.WifiP2pGroup?>("connect group") {
                manager.requestGroupInfo(channel, it)
            }
            if (group != null) {
                return if (group.owner?.deviceAddress.equals(address, true) ||
                    group.clientList.any { it.deviceAddress.equals(address, true) })
                    RootPendingConnect.State.COMPLETED else RootPendingConnect.State.UNRELATED
            }
            // A matching formed group proves this CONNECT completed, even if
            // saved-peer metadata is stale or the hidden query is unavailable.
            val listenerType = Class.forName("android.net.wifi.p2p.WifiP2pManager\$OngoingPeerInfoListener")
            val peer = query<String?>("ongoing peer") { deliver ->
                val listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerType.classLoader, arrayOf(listenerType),
                ) { _, method, args ->
                    if (method.name == "onOngoingPeerAvailable") {
                        deliver((args?.get(0) as? android.net.wifi.p2p.WifiP2pConfig)?.deviceAddress)
                    }
                    null
                }
                manager.javaClass.getMethod("requestOngoingPeerConfig", WifiP2pManager.Channel::class.java, listenerType)
                    .invoke(manager, channel, listener)
            }
            if (!peer.isNullOrEmpty()) {
                return if (peer.equals(address, true)) RootPendingConnect.State.PENDING
                else RootPendingConnect.State.UNRELATED
            }
            val network = query<android.net.NetworkInfo?>("connect network state") {
                manager.requestNetworkInfo(channel, it)
            }
            return when (network?.detailedState) {
                android.net.NetworkInfo.DetailedState.IDLE,
                android.net.NetworkInfo.DetailedState.DISCONNECTED,
                android.net.NetworkInfo.DetailedState.FAILED -> RootPendingConnect.State.COMPLETED
                android.net.NetworkInfo.DetailedState.CONNECTED -> {
                    // Formation may have raced the earlier group snapshot.
                    val formed = query<android.net.wifi.p2p.WifiP2pGroup?>("formed connect group") {
                        manager.requestGroupInfo(channel, it)
                    }
                    when {
                        formed == null -> RootPendingConnect.State.UNKNOWN
                        formed.owner?.deviceAddress.equals(address, true) ||
                            formed.clientList.any { it.deviceAddress.equals(address, true) } ->
                            RootPendingConnect.State.COMPLETED
                        else -> RootPendingConnect.State.UNRELATED
                    }
                }
                else -> RootPendingConnect.State.UNKNOWN
            }
        }

        fun availability(available: Boolean) {
            check(prepared) { "Session not prepared" }
            writeWfd(controlPort, maxThroughput, true, available)
        }

        fun listen(enabled: Boolean) {
            action(if (enabled) "startListening" else "stopListening",
                uncertain = { if (enabled) listening = true })
            listening = enabled
        }

        fun connectPeer(generation: Long, address: String, goIntent: Int) {
            RootBridgeProtocol.validatePeer(generation, address, goIntent)
            check(prepared && this.generation == generation) { "Stale peer session" }
            val config = android.net.wifi.p2p.WifiP2pConfig().apply {
                deviceAddress = address
                groupOwnerIntent = goIntent
                wps.setup = android.net.wifi.WpsInfo.PBC
            }
            // Legacy MAC-only requests need the SDK-hidden public field. Reflection
            // stays in this exempt helper VM, never in the ordinary application.
            val networkId = config.javaClass.fields.firstOrNull { it.name == "netId" }
                ?: error("Temporary peer requests unsupported by this framework")
            networkId.setInt(config, -1)
            action(
                "connect", config,
                beforeDispatch = { pendingConnect.dispatched(generation, address) },
                rejected = { pendingConnect.rejected(generation, address) },
            )
        }


        override fun close() {
            var clean = true
            fun cleanup(block: () -> Unit) {
                try { block() } catch (e: Exception) { clean = false; System.err.println("Cleanup: ${error(e)}") }
            }
            cleanup {
                pendingConnect.close(generation, ::inspectConnect) {
                    action("cancelConnect")
                    System.err.println("Owned CONNECT cancellation ACK generation=$generation")
                }
            }
            if (listening) cleanup { listen(false) }
            if (wfdChanged) cleanup { wfd(7236, 0, false) }
            if (modeChanged) cleanup { mode(0) }
            cleanup { staIsolation.close() }
            cleanup { channel.close() }
            callbackThread.quitSafely()
            check(clean) { "Framework state restoration failed" }
        }
    }
}
