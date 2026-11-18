package foxlost.miracast.sink.p2p

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog

/** Public WFD getters arrived in API 30; Android 10 exposes the legacy hidden field/getters. */
internal data class WfdPeerInfo(val enabled: Boolean, val controlPort: Int?, val deviceType: Int? = null) {
    val isSource: Boolean get() = enabled && (deviceType == 0 || deviceType == 3)
}

/** Select only an associated remote source, never the local GO or discovery metadata. */
internal fun WifiP2pGroup.remoteWfdSource(isGroupOwner: Boolean): WifiP2pDevice? =
    if (isGroupOwner) clientList.firstOrNull { it.readWfdPeerInfo().isSource }
    else owner?.takeIf { it.readWfdPeerInfo().isSource }

internal fun WifiP2pDevice.readWfdPeerInfo(): WfdPeerInfo {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val info = wfdInfo ?: return WfdPeerInfo(false, null)
        return WfdPeerInfo(info.isEnabled, info.controlPort.takeIf { it in 1..65535 }, info.deviceType)
    }
    return try {
        val info = WifiP2pDevice::class.java.getField("wfdInfo").get(this)
            ?: return WfdPeerInfo(false, null)
        val enabled = info.javaClass.getMethod("isWfdEnabled").invoke(info) as Boolean
        val port = info.javaClass.getMethod("getControlPort").invoke(info) as Int
        val deviceType = info.javaClass.getMethod("getDeviceType").invoke(info) as Int
        WfdPeerInfo(enabled, port.takeIf { it in 1..65535 }, deviceType)
    } catch (e: ReflectiveOperationException) {
        DebugEventLog.recordRateLimited(DebugEventCategory.WFD, "legacy-wfd-metadata", "Android 10 WFD metadata unavailable: ${e.javaClass.simpleName}")
        WfdPeerInfo(false, null)
    } catch (e: SecurityException) {
        DebugEventLog.recordRateLimited(DebugEventCategory.WFD, "legacy-wfd-metadata-denied", "Android 10 WFD metadata blocked by platform policy")
        WfdPeerInfo(false, null)
    }
}
