package foxlost.miracast.sink.xposed

import android.os.Build
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedHook : IXposedHookLoadPackage {
    private val TAG = "MiracastRoot"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        // Use XposedBridge.log() so messages go to the Vector/LSPosed log file,
        // not just logcat (which may not capture system_server boot-time logs).
        XposedBridge.log("[$TAG] handleLoadPackage called for pkg=$pkg classLoader=${lpparam.classLoader}")
        if (pkg != "android" && !pkg.contains("wifi") && pkg != "com.google.android.networkstack") {
            XposedBridge.log("[$TAG] Skipping pkg=$pkg (not in scope)")
            return
        }
        XposedBridge.log("[$TAG] LSPosed module loaded for $pkg — setting up hooks")
        Log.i(TAG, "LSPosed module loaded for $pkg")

        try {
            bypassConfigureWfdPermission(lpparam)
            hookExtListen(lpparam)
            XposedBridge.log("[$TAG] All hooks set up successfully for $pkg")
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] Hook setup failed in $pkg: ${e.message}")
            Log.e(TAG, "Hook setup failed in $pkg: ${e.message}")
        }
    }

    /**
     * Hook Extended Listen Timing so the supplicant re-enters LISTEN quickly
     * after Provision Discovery, before Windows sends the GO Negotiation Request.
     *
     * Root cause: the framework calls p2pExtListen(true, 500, 500) when
     * startListening() is invoked — a 500 ms interval. After PD completes,
     * wpa_supplicant's internal P2P state machine drops to IDLE. The next
     * extended-listen window fires up to 500 ms later. Windows sends its GO
     * Negotiation Request ~28 ms after PD — inside that IDLE gap — and the
     * supplicant rejects it with Status 1 ("not ready"), deadlocking the
     * exchange.
     *
     * Fix: force a 10/10 ms extended-listen interval (period=interval=10) so
     * the supplicant re-enters LISTEN within 10 ms of PD completion — well
     * before the GO-neg arrives. With Wi-Fi STA disconnected (no channel
     * conflict), the single radio stays on the P2P listen channel full-time,
     * so the 10 ms windows are never skipped due to concurrency.
     *
     * When the framework calls p2pExtListen(false, 0, 0) to DISABLE extended
     * listen (on stopListening), we let it through unchanged.
     */
    private fun hookExtListen(lpparam: XC_LoadPackage.LoadPackageParam) {
        val extPeriod = 10   // ms — short enough to re-enter LISTEN before GO-neg
        val extInterval = 10 // ms — period == interval → 100% duty cycle

        // Hook WifiP2pNative.p2pExtListen(boolean, int, int)
        try {
            val wifiNative = XposedHelpers.findClassIfExists(
                "com.android.server.wifi.p2p.WifiP2pNative", lpparam.classLoader
            )
            XposedBridge.log("[$TAG] WifiP2pNative class: $wifiNative")
            if (wifiNative != null) {
                XposedHelpers.findAndHookMethod(wifiNative, "p2pExtListen",
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val enable = param.args[0] as Boolean
                            if (enable) {
                                param.args[1] = extPeriod
                                param.args[2] = extInterval
                                XposedBridge.log("[$TAG] Forced p2pExtListen(true, $extPeriod, $extInterval)")
                            }
                        }
                    })
                XposedBridge.log("[$TAG] Hooked WifiP2pNative.p2pExtListen")
                Log.i(TAG, "Hooked WifiP2pNative.p2pExtListen")
            } else {
                XposedBridge.log("[$TAG] WifiP2pNative NOT FOUND in classLoader")
            }
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] p2pExtListen hook failed: ${e.message}")
            Log.e(TAG, "p2pExtListen hook: ${e.message}")
        }

        // Hook SupplicantP2pIfaceHal.configureExtListen(boolean, int, int) as backup
        try {
            val supplicantCls = XposedHelpers.findClassIfExists(
                "com.android.server.wifi.p2p.SupplicantP2pIfaceHal", lpparam.classLoader
            )
            XposedBridge.log("[$TAG] SupplicantP2pIfaceHal class: $supplicantCls")
            if (supplicantCls != null) {
                XposedHelpers.findAndHookMethod(supplicantCls, "configureExtListen",
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val enable = param.args[0] as Boolean
                            if (enable) {
                                param.args[1] = extPeriod
                                param.args[2] = extInterval
                                XposedBridge.log("[$TAG] Forced configureExtListen(true, $extPeriod, $extInterval)")
                            }
                        }
                    })
                XposedBridge.log("[$TAG] Hooked SupplicantP2pIfaceHal.configureExtListen")
                Log.i(TAG, "Hooked SupplicantP2pIfaceHal.configureExtListen")
            } else {
                XposedBridge.log("[$TAG] SupplicantP2pIfaceHal NOT FOUND in classLoader")
            }
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] configureExtListen hook failed: ${e.message}")
            Log.e(TAG, "configureExtListen hook: ${e.message}")
        }
    }

    private fun bypassConfigureWfdPermission(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = XposedHelpers.findClassIfExists(
                "com.android.server.wifi.p2p.WifiP2pServiceImpl",
                lpparam.classLoader
            ) ?: return
            XposedHelpers.findAndHookMethod(cls, "checkConfigureWifiDisplayPermission",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                        Log.d(TAG, "Bypassed checkConfigureWifiDisplayPermission")
                    }
                })
            Log.i(TAG, "Hooked checkConfigureWifiDisplayPermission")
        } catch (e: Exception) {
            Log.e(TAG, "checkConfigureWifiDisplayPermission hook: ${e.message}")
        }
    }

}
