package foxlost.miracast.sink.p2p

/** Fixed stdio protocol: no shell fragments, caller-selected paths or arbitrary supplicant RPC. */
internal object RootBridgeProtocol {
    private val mac = "[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}"
    private val peerAddress = Regex(mac)
    private val connect = Regex("P2P_CONNECT $mac pbc go_intent=0")
    private val deviceInfo = Regex("WFD_SUBELEM_SET 0 000600(?:11|01)[0-9a-fA-F]{8}")
    private val listen = Regex("P2P_EXT_LISTEN ([0-9]{1,5}) ([0-9]{1,5})")

    fun validSupplicant(command: String): Boolean {
        if (command == "P2P_SET disallow_freq 0-2400,2500-7125" ||
            command == "P2P_SET disallow_freq 0-4900,5900-7125" ||
            command == "P2P_SET disallow_freq ") return true
        if (command == "WFD_SUBELEM_SET 7 " || command == "WFD_SUBELEM_SET 7 00020001") return true
        if (connect.matches(command) || deviceInfo.matches(command)) return true
        if (command == "WFD_SUBELEM_SET 0 ") return true
        val match = listen.matchEntire(command) ?: return false
        val period = match.groupValues[1].toInt()
        val interval = match.groupValues[2].toInt()
        return (period == 0 && interval == 0) || (period in 1..65535 && interval in period..65535)
    }

    fun validUnicastMac(value: String): Boolean = peerAddress.matches(value) &&
        value.substring(0, 2).toInt(16) and 1 == 0 &&
        !value.equals("00:00:00:00:00:00", ignoreCase = true)

    fun validatePeerLookup(interfaceName: String, address: String) {
        require(SupplicantControlSession.validInterface(interfaceName) && validUnicastMac(address)) {
            "Invalid peer lookup"
        }
    }

    /** P2P_PEER starts with the selected peer's device MAC, not an ARP/IP row. */
    fun peerInterfaceAddress(peerMac: String, reply: String): String? {
        if (!validUnicastMac(peerMac)) return null
        val lines = reply.lineSequence().iterator()
        if (!lines.hasNext() || !lines.next().equals(peerMac, ignoreCase = true)) return null
        var intended: String? = null
        var observed: String? = null
        while (lines.hasNext()) {
            val line = lines.next()
            when {
                line.startsWith("interface_addr=") -> {
                    if (observed != null) return null
                    observed = line.removePrefix("interface_addr=")
                }
                line.startsWith("intended_addr=") -> {
                    if (intended != null) return null
                    intended = line.removePrefix("intended_addr=")
                }
            }
        }
        // Persistent invitations need not perform GO negotiation. The interface
        // observed in P2P frames is authoritative even when intended_addr is unset.
        val current = observed?.takeUnless { it == "00:00:00:00:00:00" }
        return (current ?: intended)?.takeIf(::validUnicastMac)?.lowercase(java.util.Locale.ROOT)
    }

    fun validatePeer(generation: Long, address: String, goIntent: Int) {
        require(generation >= 0 && peerAddress.matches(address) && goIntent in 0..15) {
            "Invalid peer connection"
        }
    }

    fun validatePlayer(generation: Long, rtp: Int, rtcp: Int) {
        require(generation >= 0) { "Invalid session generation" }
        require(rtp in 1..65534 && rtcp in 0..65535 && rtp != rtcp) { "Invalid media ports" }
    }

    fun requireOk(reply: String) {
        check(reply == "OK") { "Root bridge failed: ${reply.take(240)}" }
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

internal class SupplicantOutcomeUnknown(cause: Throwable? = null) :
    IllegalStateException("Supplicant outcome unknown", cause)
