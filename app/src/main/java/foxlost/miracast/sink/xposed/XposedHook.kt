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
        if (pkg != "android" && !pkg.contains("wifi") && pkg != "com.google.android.networkstack") return
        Log.i(TAG, "LSPosed module loaded for $pkg")

        try {
            bypassConfigureWfdPermission(lpparam)
            injectWfdInfoOnSet(lpparam)
        } catch (e: Exception) {
            Log.e(TAG, "Hook setup failed in $pkg: ${e.message}")
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

    private fun injectWfdInfoOnSet(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            var supplicantCls = XposedHelpers.findClassIfExists(
                "com.android.server.wifi.p2p.SupplicantP2pIfaceHal",
                lpparam.classLoader
            )
            if (supplicantCls == null) {
                supplicantCls = XposedHelpers.findClassIfExists(
                    "android.hardware.wifi.supplicant.V1_4.ISupplicantP2pIface",
                    lpparam.classLoader
                )
            }
            if (supplicantCls != null) {
                var hooked = false
                for (method in supplicantCls.declaredMethods) {
                    if (method.name == "setWfdDeviceInfo" && method.parameterTypes.size == 1) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.args[0] = "0151001C4432" 
                                Log.d(TAG, "Injected WFD device info hex into supplicant")
                            }
                        })
                        hooked = true
                    }
                    if (method.name == "enableWfd" && method.parameterTypes.size == 1) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.args[0] = true
                                Log.d(TAG, "Forced enableWfd(true)")
                            }
                        })
                    }
                }
                Log.i(TAG, "Supplicant level WFD info injection: $hooked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Supplicant WFD hook: ${e.message}")
        }

        try {
            val wifiNative = XposedHelpers.findClassIfExists(
                "com.android.server.wifi.p2p.WifiP2pNative", lpparam.classLoader
            )
            if (wifiNative != null) {
                for (method in wifiNative.declaredMethods) {
                    if (method.name == "enableWfd" && method.parameterTypes.size == 1) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.args[0] = true
                                Log.d(TAG, "Forced WifiP2pNative.enableWfd(true)")
                            }
                        })
                    }
                    if (method.name == "setWfdDeviceInfo" && method.parameterTypes.size == 1) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.args[0] = "0151001C4432"
                                Log.d(TAG, "Injected WFD info via WifiP2pNative")
                            }
                        })
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WifiP2pNative hook: ${e.message}")
        }
    }
}
