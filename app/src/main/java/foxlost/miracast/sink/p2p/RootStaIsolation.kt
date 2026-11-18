package foxlost.miracast.sink.p2p

/**
 * O(1) preparation of the current STA connection, not session-long autojoin suppression.
 * Saved-network flags and global autojoin policy remain entirely framework-owned.
 */
internal class RootStaIsolation(private val policy: Policy) : AutoCloseable {
    internal data class Snapshot(
        val wifiEnabled: Boolean,
        val networkId: Int,
        val active: Boolean,
        val idle: Boolean,
    )

    internal interface Policy {
        fun read(): Snapshot
        fun disconnect(): Boolean
        fun reconnect(): Boolean
    }

    private var acquired = false
    private var reconnectRequired = false

    fun acquire(checkActive: () -> Unit) {
        check(!acquired) { "STA preparation already acquired" }
        checkActive()
        val before = policy.read()
        acquired = true
        checkActive()
        if (!before.wifiEnabled || before.networkId < 0 || !before.active) return
        // A rejected/throwing Binder call does not prove that disconnect was unapplied.
        reconnectRequired = true
        check(policy.disconnect()) { "STA disconnect rejected" }
        checkActive()
    }

    override fun close() {
        if (!reconnectRequired) return
        val current = policy.read()
        // An active/connecting STA (even the original AP) supersedes this preparation.
        // Never turn the radio back on or force reconnect from an unconfirmed state.
        if (current.wifiEnabled && current.networkId < 0 && !current.active) {
            check(current.idle) { "STA restoration state unconfirmed" }
            check(policy.reconnect()) { "STA reconnect request rejected" }
        }
        reconnectRequired = false
        // Android 11 reconnect requests a connectivity scan, not a particular AP.
        // Its acknowledgement is the cleanup proof; association is not guaranteed.
    }
}
