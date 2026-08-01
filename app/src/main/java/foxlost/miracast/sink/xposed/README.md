# Xposed Package (`xposed`)

LSPosed/Xposed module for bypassing Android framework restrictions at runtime. Provides a secondary mechanism for enabling WFD sink functionality when the primary method (platform-signed system app) encounters issues.

## `XposedHook.kt` — LSPosed Module

Implements `IXposedHookLoadPackage` to inject hooks into Android's Wi-Fi framework (`WifiP2pServiceImpl`, `SupplicantP2pIfaceHal`, `WifiP2pNative`).

### Hooks

#### 1. Permission Bypass
```kotlin
// Bypasses CONFIGURE_WIFI_DISPLAY permission check
WifiP2pServiceImpl.checkConfigureWifiDisplayPermission()
```
This hook nullifies the signature-level permission check, allowing the app to call `setWfdInfo()` without the `CONFIGURE_WIFI_DISPLAY` permission.

#### 2. WFD Device Info Injection
```kotlin
// Forces specific WFD IE hex into the supplicant
SupplicantP2pIfaceHal.setWfdDeviceInfo("0151001C4432")
WifiP2pNative.setWfdDeviceInfo("0151001C4432")
```
Injects our WFD capabilities hex directly into wpa_supplicant commands.

#### 3. WFD Enable Forcing
```kotlin
// Forces WFD feature flags to true
SupplicantP2pIfaceHal.enableWfd(true)
WifiP2pNative.enableWfd(true)
```
Ensures Wi-Fi Display is always enabled in the supplicant.

### Target Scope (Xposed)
```xml
<item>android</item>
<item>com.android.wifi</item>
<item>com.android.server.wifi</item>
<item>com.google.android.networkstack</item>
<item>com.android.networkstack</item>
<item>foxlost.miracast.sink</item>
```

### When Hooks Are Used

The Xposed module acts as a **fallback** for the primary method (platform-signed system app with reflected `setWfdInfo()`). It's needed when:

1. The device's Wi-Fi stack uses a non-standard package name
2. The system app config doesn't fully grant `CONFIGURE_WIFI_DISPLAY`
3. `wpa_supplicant` commands need to be forced at the framework level

### Mechanism

All hooks run in the `system_server` process context (UID 1000), allowing them to modify framework behavior. The `xposed_init` file registers this class as an LSPosed module.

### Setup

1. Install LSPosed/Zygisk on the device
2. Enable the "Universal Miracast Sink" module in LSPosed
3. Set scope to: System Framework, foxlost.miracast.sink
4. Reboot

### Debug Logs

```
MiracastRoot: LSPosed module loaded for android
MiracastRoot: Hooked checkConfigureWifiDisplayPermission
MiracastRoot: Bypassed checkConfigureWifiDisplayPermission
MiracastRoot: Injected WFD device info hex into supplicant
MiracastRoot: Forced enableWfd(true)
```

## Relationship to Primary Method

| Method | Mechanism | Reliability |
|---|---|---|
| **Primary** | System app + platform signing + reflection API | Works when `setWfdInfo` succeeds |
| **Xposed** | LSPosed hooks on framework classes | Fallback when reflection fails |
| **Root Injection** | `app_process` + supplicant DGRAM socket | Last resort, direct supplicant control |
