package foxlost.miracast.sink.p2p

/** Datagram transaction rules shared by command, peer-query and event consumers. */
internal class SupplicantControlSession(
    private val send: (String) -> Unit,
    private val receive: () -> String,
    private val event: (String) -> Unit = {},
) {
    private var prefix = ""
    private var verified = false

    companion object {
        private val interfaceName = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,14}")
        fun validInterface(value: String) = interfaceName.matches(value)
        fun isEvent(value: String): Boolean = value.startsWith("<") ||
            (value.startsWith("IFNAME=") && value.substringAfter(' ', "").startsWith("<"))
    }

    private fun exchange(command: String): String {
        send(command)
        // Events can precede ATTACH's OK and any command reply. Bound event storms;
        // the underlying receiver also enforces the transaction's absolute deadline.
        repeat(256) {
            val reply = receive()
            if (isEvent(reply)) event(reply) else return reply
        }
        error("Supplicant event flood before reply")
    }

    fun verify() {
        check(exchange("PING") == "PONG") { "Supplicant transport PING did not return PONG" }
        val direct = exchange("IFNAME")
        if (validInterface(direct) && direct != "FAIL" && direct != "OK" && direct != "PONG") {
            verified = true
            return
        }
        // A PONG alone cannot distinguish an interface socket from a global one.
        // INTERFACES + routed IFNAME establishes the context, rather than guessing
        // from /dev/socket naming or blindly adding IFNAME to a per-interface RPC.
        val interfaces = exchange("INTERFACES").lineSequence().filter(::validInterface).toList()
        val selected = interfaces.firstOrNull { it.startsWith("p2p") }
            ?: interfaces.firstOrNull { it.startsWith("wlan") }
        check(selected != null) { "Supplicant target interface is unavailable" }
        val route = "IFNAME=$selected "
        check(exchange(route + "PING") == "PONG") { "Routed supplicant PING did not return PONG" }
        check(exchange(route + "IFNAME") == selected) { "Supplicant route identity mismatch" }
        prefix = route
        verified = true
    }

    fun request(command: String): String {
        check(verified) { "Supplicant route is not verified" }
        return exchange(prefix + command)
    }

    fun attach() {
        check(verified) { "Supplicant route is not verified" }
        // ATTACH belongs to the Unix control socket, not the routed interface
        // dispatcher. IFNAME=<iface> ATTACH is UNKNOWN COMMAND on global sockets.
        check(exchange("ATTACH") == "OK") { "ATTACH rejected" }
    }

    fun mutate(command: String): Boolean {
        require(RootBridgeProtocol.validSupplicant(command))
        check(verified) { "Supplicant route is not verified" }
        // Even send failure can be ambiguous. No caller may replay after entering
        // this boundary unless a complete explicit rejection was received.
        val reply = try { request(command) } catch (e: Exception) { throw SupplicantOutcomeUnknown(e) }
        if (reply == "OK") return true
        if (reply == "UNKNOWN COMMAND" || reply.startsWith("FAIL")) return false
        throw SupplicantOutcomeUnknown()
    }
}
