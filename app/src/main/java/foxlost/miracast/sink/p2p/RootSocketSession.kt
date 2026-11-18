package foxlost.miracast.sink.p2p

import java.util.Base64

/** Tracks driver changes in the privileged owner, so pipe EOF also cleans up after app death. */
internal class RootSocketSession(
    private val execute: (String) -> Boolean,
    private val readArp: () -> String,
    private val readPeerInterface: (String, String) -> String?,
) : AutoCloseable {
    private var dirty = 0
    private var closed = false

    fun request(line: String): String {
        check(!closed) { "Socket session closed" }
        if (line == "ARP") {
            return "ARP " + Base64.getEncoder().encodeToString(readArp().toByteArray(Charsets.UTF_8))
        }
        if (line.startsWith("PEER_INTERFACE ")) {
            val fields = line.split(' ')
            require(fields.size == 3) { "Invalid peer lookup" }
            RootBridgeProtocol.validatePeerLookup(fields[1], fields[2])
            val address = try {
                readPeerInterface(fields[1], fields[2])
            } catch (_: SupplicantTransportUnavailable) {
                // The optional management route can appear after group formation.
                // Keep that readiness outcome distinct from a failed owner/IPC.
                return "LOOKUP_UNAVAILABLE"
            }
            check(address == null || RootBridgeProtocol.validUnicastMac(address)) { "Invalid peer interface reply" }
            return address?.let { "PEER_INTERFACE $it" } ?: "NOT_FOUND"
        }
        if (line == "CLEAR_EXT_LISTEN") {
            if (dirty and 4 == 0) return "OK"
            return try {
                if (execute("P2P_EXT_LISTEN 0 0")) {
                    dirty = dirty and 4.inv()
                    "OK"
                } else "ERR Extended listen cleanup rejected"
            } catch (_: SupplicantOutcomeUnknown) {
                "UNCERTAIN"
            }
        }
        require(line.startsWith("COMMAND ")) { "Unsupported socket operation" }
        val command = line.removePrefix("COMMAND ")
        require(RootBridgeProtocol.validSupplicant(command)) { "Unsupported supplicant operation" }
        val bit = when {
            command.startsWith("WFD_SUBELEM_SET 0 ") -> 1
            command.startsWith("WFD_SUBELEM_SET 7 ") -> 2
            command.startsWith("P2P_EXT_LISTEN ") -> 4
            command.startsWith("P2P_SET disallow_freq ") -> 8
            else -> 0
        }
        return try {
            if (execute(command)) {
                val neutral = command == "WFD_SUBELEM_SET 0 " ||
                    command == "WFD_SUBELEM_SET 7 " || command == "P2P_EXT_LISTEN 0 0" ||
                    command == "P2P_SET disallow_freq "
                dirty = if (neutral) dirty and bit.inv() else dirty or bit
                "OK"
            } else {
                "ERR Supplicant rejected operation"
            }
        } catch (_: SupplicantOutcomeUnknown) {
            dirty = dirty or bit
            "UNCERTAIN"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        fun restore(bit: Int, command: String) {
            if (dirty and bit == 0) return
            try {
                check(execute(command)) { "Supplicant neutral cleanup rejected: $command" }
            } catch (e: Exception) {
                if (failure == null) failure = e else failure?.addSuppressed(e)
            }
        }
        // Attempt every independent restoration even if one command fails.
        // The framework owner established Auto before this session narrowed
        // the band. Clear here too: a queued socket mutation can finish after
        // the independent framework owner's EOF cleanup.
        restore(8, "P2P_SET disallow_freq ")
        restore(4, "P2P_EXT_LISTEN 0 0")
        restore(2, "WFD_SUBELEM_SET 7 ")
        restore(1, "WFD_SUBELEM_SET 0 ")
        if (failure != null) throw IllegalStateException("Supplicant cleanup failed: ${failure?.message}", failure)
    }
}
