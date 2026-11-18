# P2P Package (`p2p`)

Wi-Fi Direct and Wi-Fi Display setup — P2P discovery, WFD Information Element injection, and wpa_supplicant communication.

## Files

### `P2pManager.kt` — P2P/WFD Lifecycle Manager

Manages the Wi-Fi Direct sink lifecycle: starting discovery, injecting WFD capabilities into beacons, and reporting connection state changes.

**Key flows**:

1. **startSink(generation)**: Invalidates prior work, assigns a unique generation-scoped P2P attempt token, and confirms the previous native group is absent before starting the private root bridge. Incoming group probes cannot adopt an inherited group during this cleanup gate. It does not change global display settings.
2. **chainWfdInfo()**: The root-launched framework helper drops to UID 1000 before constructing the Android system context (`android` package attribution), initializes its own P2P channel, and sets Miracast sink mode and WFD capabilities. It waits for the actual WFD ActionListener result. WFD-specific rejection may use the fixed supplicant fallback; root identity, STA setup, denial, and timeout failures fail the attempt.
3. **Force GO role**: Calls normal-app `createGroup()` when configured. Normal discovery, peer requests and connection remain on the app channel.
4. **LISTEN/monitor serialization**: Framework listening is requested through the helper and must report success before listen readiness. An explicitly unsupported API falls back to ordinary peer discovery, not a fictitious listen success; denial, timeout and other failures fail the attempt. Optional `extListenMs` overrides are session-only. A group-started event stops pre-group listen/monitor work before readiness polling. There are no framework hooks.
5. **Group/network readiness**: `P2P-GROUP-STARTED` is only a candidate. Each poll pairs fresh group info with fresh connection info, checks the active group identity and consistent GO role, and waits up to 6s for an associated remote WFD source, an UP P2P interface, and framework GO IPv4. A local GO must own that exact IPv4, not merely retain some old interface address. Missing peer/WFD metadata remains waiting; failed queries do not reuse older snapshots. Both group probes and readiness have callback deadlines; readiness expiry precedes acceptance of a late ready snapshot.
6. **Bounded retry**: A failed formation, readiness timeout, group loss, invitation fallback failure/stall, or pre-RTSP control-connect failure cleans up the old token, waits up to 2s for `requestGroupInfo()` to report no group, and retries after 500ms, at most three total attempts. Each retry closes/restores the root owner before preparing a new owner. Delayed callbacks from an old generation or attempt are ignored.
7. **requestConnectionInfo()**: Queries the framework in its required order and notifies `onP2pGroupConnected(group, info, attemptId)` only after readiness.
Readiness and cleanup deadlines use `SystemClock.elapsedRealtime()`. Discovery, peer-list/auto-accept and connection-result callbacks carry their originating token; queued `reportAttemptFailure(generation, attemptId, reason)` validates the captured token again on delivery. Failure notification may reenter stop/start, so destructive cleanup rechecks pending-cleanup ownership afterward. Cleanup timers and results also require the exact current runnable, not merely a non-null gate.

**WFD Information Element:**
`00111C440032` is the canonical default payload: `0011` device info,
`1C44` RTSP control port (7236), and `0032` max throughput.
At runtime `P2pManager` generates this from the framework `setWfdInfo()` API
and the configured RTSP control port; it is not a hardcoded Xiaomi payload.
When UIBC is enabled, the framework path additionally attempts the standards-defined
WFD Extended Capability subelement 7 (`00020001`, bitmap bit 0 UIBC Support)
through `WFD_SUBELEM_SET`. The optional supplicant fallback sets subelement 0
with the current port/throughput and subelement 7 only when UIBC is enabled.

The WFD session-available bit is cleared once a group is accepted and restored by fresh setup on retry. Peer selection requires enabled source-capable WFD device type 0 (source) or 3 (source/primary sink), not merely any WFD-enabled device. Missing peer WFD metadata fails clearly.

The sink initiates RTSP TCP in **both** GO arrangements using the peer-advertised source port (default 7236), not its own advertisement port. A source GO address comes from framework connection info. For a source client, a fixed readonly root query binds its device MAC to the observed `interface_addr` (or valid `intended_addr` when unavailable) from `P2P_PEER`; the reported device identity must match. Root ARP lookup then requires that identity on the current group interface, complete flags, and a unique usable unicast IPv4 other than the GO address. Exact device-MAC matching remains eligible when no interface alias is available, but arbitrary neighbors, MAC-bit transformations and ambiguous addresses are never selected. Persistent invitations with unset intended addresses are supported through the observed interface field. There is no inbound sink RTSP listener.

Reference: [Wi-Fi Display Technical Specification v2.1](https://raw.githubusercontent.com/wiki/albfan/miraclecast/files/Wi-Fi_Display_Technical_Specification_v2.1_0.pdf), §4.5.4 (sink TCP initiator, independent of GO), §6.4 (M1–M7), and [AOSP WifiDisplaySink](https://android.googlesource.com/platform/frameworks/av/+/android-4.2.2_r1.2/media/libstagefright/wifi-display/sink/WifiDisplaySink.cpp). Platform identity is inferred only from explicit RTSP User-Agent/Server evidence, not GO ownership. Duplicate PLAY is not enabled by default.

Source IPv4 readiness and RTSP TCP startup share one monotonic 90-second retry window (§6.5.2); lookup does not reset the TCP budget. Because WPA completion time is not exposed, the window starts conservatively at group/network readiness. Generation/attempt checks surround blocking identity/ARP reads and prevent late publication after cancellation or expiry. Teardown closes an in-progress TCP socket, and every media/control callback must still match the current attempt. Terminal failure stops the service even if no session context was formed.


`WfdPeerInfo.kt` reads public peer WFD getters on API 30+ and uses Android 10's
legacy `wfdInfo`/`isWfdEnabled` getters only on API 29. Unavailable metadata is
reported rather than calling a newer API on an older device. Cleanup checks its
applicable Wi-Fi runtime permission and handles revocation as a failed barrier.
`remoteWfdSource(isGroupOwner)` is shared by readiness and service admission. It selects only source-capable members of `clientList` for a local GO, or the remote `owner` for a local client; the local GO owner never satisfies peer readiness. These roles and membership follow [WifiP2pGroup](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pGroup). A peer discovered nearby is not evidence of association.

Fresh peer requests use `WifiP2pConfig.Builder.enablePersistentMode(false)` on
Android 13+, where MAC-only builder requests are supported. Android 10–12
builders require SSID/passphrase, so those requests cross the fixed root
`CONNECT` operation with validated generation, MAC address, and GO intent.
Only the exempt helper VM accesses the SDK-hidden public temporary `netId`
field; the ordinary application does not reflect into it. Missing support is
reported without substituting a persistent-group reinvoke.

An accepted persistent invitation may fall back to a fresh connection if native formation fails. The fallback permits up to 120 seconds for GO/WPS formation, separate from the six-second post-group IP/peer readiness window. A `CONNECTING` negotiation is no longer cancelled after six seconds; explicit native failure still ends the attempt. The two-minute bound follows [AOSP WifiP2pServiceImpl's GROUP_CREATING_WAIT_TIME_MS](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/master/service/java/com/android/server/wifi/p2p/WifiP2pServiceImpl.java). Invitation diagnostics retain quoted SSIDs containing spaces and the raw `persistent` value; a network ID such as `3` is not reported as a false Boolean.

`P2pBand` is captured once per session. Auto does not mutate channel policy.
An explicit forced band first establishes an acknowledged framework Auto
baseline, then the socket owner sends an exact allowlisted
`P2P_SET disallow_freq` range for 2.4 GHz or 5 GHz. This takes over an unknown
previous P2P channel filter; cleanup returns to Auto rather than claiming to
restore that unknowable filter. The socket owner alone owns the restriction
and clears it on EOF, including an uncertain/lost mutation reply. Framework
close does not issue a competing late channel reset. Country/regulatory and
persistent network settings remain unchanged.

Forced 5 GHz is rejected before root setup/retries when the application-side hardware query reports no support; Settings excludes that choice and requires correction of any legacy unsupported preference. The root bridge retains its authoritative hardware check. Before RTSP admission, the fresh group's frequency must match the forced band; an unknown frequency also fails. Auto preserves the existing admission path. This constrains P2P
negotiation, not source RTSP capabilities, and cannot make a sender support
another band. See [WifiP2pGroup.getFrequency](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pGroup#getFrequency())
and the [framework Auto-channel/disallowed-frequency implementation](https://github.com/LineageOS/android_frameworks_opt_net_wifi/blob/lineage-18.1/service/java/com/android/server/wifi/p2p/SupplicantP2pIfaceHal.java).

**Interface**:
```kotlin
interface P2pListener {
    fun onP2pGroupConnected(group: WifiP2pGroup, info: WifiP2pInfo, attemptId: Long)
    fun onP2pGroupDisconnected(attemptId: Long)
    fun onP2pAttemptFailed(attemptId: Long, reason: String) {}
    fun onP2pAttemptExhausted(attemptId: Long, reason: String) {}
    fun onP2pStartupFailed(generation: Long, reason: String)
    fun onDiscoveryStateChanged(active: Boolean)
}
```

`WifiP2pManager` is asynchronous: group and connection details are requested only
after a group indication, and RTSP is not started from the indication alone. This
matches the Android API contract: [WifiP2pManager](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager)
documents `requestGroupInfo()` followed by `requestConnectionInfo()`. If a future
implementation adds `ConnectivityManager.NetworkCallback`, it must register each
callback at most once and unregister it during cleanup, as required by
[NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback).

### `P2pReceiver.kt` — P2P Connection Broadcast Receiver

System connection/peer/state/discovery actions use a dedicated `RECEIVER_EXPORTED` instance so privileged framework senders are not rejected by the application's synthetic non-exported permission. Connection changes and enabled-state signals query authoritative framework group/connection info; peers-changed refreshes peer metadata. Vendor connection-request hooks use a distinct `RECEIVER_NOT_EXPORTED` instance. Only that private instance permits vendor auto-accept; the exported instance ignores authorization actions even if invoked directly. See [Android's broadcast receiver partitioning guidance](https://developer.android.com/develop/background-work/background-tasks/broadcasts).

### Root bridge and supplicant transport

`RootBridgeClient` owns private `su`/`app_process` stdio processes; there is no public RPC socket, boot service or arbitrary shell-command API. `RootFrameworkBridge` clears groups, changes GID/UID to 1000 and re-execs before creating its system context so Binder uses verified credentials. Its fixed operations remain preparation, WFD setup, listen and temporary peer connection, with six-second ActionListener deadlines. `RootStaIsolation` snapshots only the current connection through `RootStaPolicy` and optionally disconnects it in O(1). It never enumerates or changes saved networks or global autojoin. Cleanup requests reconnect only for an owned disconnect when no new active/connecting STA or radio-off state supersedes it. This is scoped preparation, not a promise of STA suppression throughout the session or reassociation to the original AP.

`RootCommandInbox` observes stdin EOF concurrently with command execution. Cancellation checkpoints remain before mutation and before Miracast mode. Ownership precedes disconnect dispatch so a rejected/lost reply cannot erase cleanup responsibility. A failed or unconfirmed reconnect request fails cleanup; an initially disconnected STA creates no reconnect obligation.

`RootFrameworkChannel` is the sole framework stdout reader. Completed START steps refresh a 12-second idle deadline within a separate 120-second total bound; duplicate progress cannot hide a stall. Ordinary commands retain their fixed deadline. `CLEANUP OK` follows successful restoration requests; `CLEANUP ERR` or EOF without proof fails the gate. Live restoration keeps reuse pending independently of `su` wrapper status. The wrapper is not killed on the old 30-second framework timer, and a bounded wait before new startup cannot bypass pending cleanup.

`RootPlayerBridge` is a separate one-shot UID-0 helper. Its private stdin accepts only generation/RTP/RTCP numbers and EOF, never an arbitrary intent or shell fragment. It invokes `/system/bin/cmd activity start-activity -W --user 0` for the fixed non-exported `PlayerActivity`. [Android 16 ActivityManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/am/ActivityManagerShellCommand.java) uses `startActivityAndWait(null, ...)`, avoiding an unregistered system-context `IApplicationThread`. Root UID 0 supplies the launch privilege without an overlay permission or exported player. Exit 0 alone is insufficient: status, target activity and completion must match. The command is bounded to eight seconds and the parent request to twelve.

`RootSocketBridge` stays UID 0 for DAC/SELinux-sensitive vendor socket and `/proc/net/arp` access. Its private session pipe owns a `RootSocketSession`, which records successful or uncertain driver changes and attempts every neutral restoration on EOF, including abrupt app-process death. ARP replies are single-line Base64 frames. Socket detection occurs in the root process using fixed directories and validated interface names; a known working control socket is preferred. `SupplicantWriter` allows only WFD subelements, extended-listen settings, the two fixed band-exclusion ranges and their neutral clear, and the exact MAC/PBC GO-negotiation command used by the sink. A send is not success: the datagram reply must be exactly `OK`. Reply endpoints use abstract AF_UNIX names and disappear on close; connected-peer filtering and pre-verification queue draining protect the replies. `SupplicantEventMonitor` requires an acknowledged `ATTACH` and exits when its owner pipe closes.

Readonly `PEER_INTERFACE <iface> <device-MAC>` is a validated private operation. P2P management context may expose the device report even if the group interface has no separate control endpoint; the application scopes the resulting MAC to its framework-selected group and attempt. Like ARP, this query creates no driver-restoration obligation. The peer report's observed address is authoritative for persistent invitations; see [Android-era p2p.c](https://github.com/LineageOS/android_external_wpa_supplicant_8/blob/lineage-18.1/src/p2p/p2p.c) (`p2p_add_device`, `p2p_get_peer_info_txt`).
Only a missing peer or typed temporary transport unavailability becomes a retryable lookup result. Root-owner EOF, malformed IPC/ARP/MAC replies and permanent RPC errors propagate to attempt failure instead of being retried as missing IP readiness. The service owns and interrupts address-resolution work during teardown; token checks still surround each blocking read and result publication.


Stopping closes and waits for the driver owner first, then the framework owner,
so supplicant restoration precedes WFD/mode/STA restoration. Responsibility is
recorded before mutation-capable writes; explicit rejection is distinguished
from a dispatched operation whose result is unknown. Successful neutral driver
commands release their obligation; an unacknowledged datagram does not.

`stopSink` joins an in-flight completion rather than issuing another teardown.
Its result awaits the retired clients' actual restoration and the global
older-owner barrier, then confirms the group is absent. A queued start is
serial-fenced against a later stop/dispose. Disposal closes the P2P channel
after the existing stop completes; it cannot issue a delayed extra removal
against a replacement service's group.


Each root client reserves its place in a chained `RootCleanupBarrier` before
launch. An unused/cancelled owner cannot clear another owner's pending or failed
restoration, and pre-mutation initialization failure does not poison the gate.
Genuine failed restoration remains blocking: `RootCleanupException` reports
previous cleanup separately from setup/authorization, not as a new root denial.
Restoring Wi-Fi state and restarting the app (rebooting if needed) is required
before reusing an actually failed owner; there is no automatic unsafe reset.

Rejection of an optional listen command does not close the WFD owner. Settings
or firmware may still reject hidden framework APIs, supplicant access, or UID
transitions under SELinux; the bridge does not weaken permissions or policy.
Physical-device behavior is not implied by successful JVM tests or simulated
host IPC peers.

Diagnostic stderr is not a control channel: expected pipe closure is contained; unexpected active-helper I/O failure is logged rather than crashing the ordinary APK. Monitor EOF from its owner is normal cancellation, while a receive failure before owner EOF exits with an error. Atomic connection close prevents the EOF owner and receive-loop finalizer from closing the same socket twice.

Media readiness belongs to a process-unique generation. Launch failure notifies waiters with the real cause; only matching bound ports authorize SETUP. Session teardown retires readiness, and an Activity rejects a late launch before receiver/surface work. Generations are process-wide because Activity callbacks can outlive a recreated Service.

Binder identity source: [Android 16 WifiP2pServiceImpl](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android16-release/service/java/com/android/server/wifi/p2p/WifiP2pServiceImpl.java), whose messenger validates caller attribution and whose WFD operations check the sending UID's `CONFIGURE_WIFI_DISPLAY` permission. This helper identity is separate from the ordinary APK UID.

Credential changes use the narrowly scoped `RootIdentity` JNI library, loaded explicitly
from the helper APK's uncompressed `!/lib/<process ABI>/libmiracast_identity.so`.
Android 16 has no `Process.setGroups`, nor a `setgroups` method on
[`libcore.io.Os`](https://android.googlesource.com/platform/libcore/+/refs/tags/android-16.0.0_r1/luni/src/main/java/libcore/io/Os.java)
or [`libcore.io.Linux`](https://android.googlesource.com/platform/libcore/+/refs/tags/android-16.0.0_r1/luni/src/main/java/libcore/io/Linux.java).
The helper therefore calls real bionic `setgroups(0, nullptr)` before `setgid(1000)`
and `setuid(1000)`, then verifies real/effective/saved UID and GID plus an empty
supplementary-group list. It then `execv`s `/system/bin/app_process` with the fixed
`--system-identity` second-stage argument, preserving `CLASSPATH` and session stdio.
The second VM rechecks every credential invariant through a verify-only JNI entry
point before hidden-API initialization, `SystemContext` creation or readiness.
An ordinary application UID cannot enter either stage.
This fresh VM is necessary because
[app_process `AppRuntime.onStarted`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/cmds/app_process/app_main.cpp)
opens Binder and starts its pool before calling Java `main`, while
[the Binder credential fix](https://android.googlesource.com/kernel/common/+/d492977395503ab74263affc531509f2865b681a)
captures sender credentials at initial Binder open. Changing only the first VM's
UID would otherwise leave its Binder identity tied to its original root credentials.
This follows the supplementary-group clearing primitive in
[AOSP Zygote `SetGids`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/jni/com_android_internal_os_Zygote.cpp).
Native loading, syscall, exec or credential-verification failures abort initialization;
the ordinary application never loads this library or changes its UID.
The ndk-build integration targets Android 29+ and all four standard ARM/x86 ABIs,
uses 16 KiB ELF page alignment and uncompressed AGP native-library packaging,
and resolves side-by-side SDK NDK `29.0.14206865` through `android.ndkVersion`.

`SupplicantSocketLabel` reads the server endpoint inode from `/proc/net/unix` and
the socket object's `security.selinux` through a matching `/proc/PID/fd/N` sockfs
descriptor. A filesystem `sock_file` label is not the peer socket SID. Process
names only prioritize bounded discovery; they do not authorize a label.
The client socket is created under per-thread `sockcreate` on a short-lived
thread, with restoration in `finally` and explicit verification of its actual SID.
A finite wait abandons late results safely; a late creator closes its own socket.
Reply addresses use the abstract namespace, so there is no separate filesystem inode whose label must permit daemon writes. The v1.12 server-label-copy approach was rejected by Cereus policy and is removed, including the `fscreate` scope and filesystem cleanup. The connected datagram socket pins the allowed sender to the selected daemon. A bounded queue drain before PING discards any data queued before connect; its short receive timeout is restored before normal transactions. This also avoids depending on a writable vendor reply directory.
No SELinux allow rule, global permissive mode or persistent boot policy is added.

Every connection requires exact raw `PING`/`PONG` and an actual `IFNAME` identity; global sockets additionally require `INTERFACES`, routed PONG and matching routed IFNAME before mutation or monitor ATTACH. Events before replies do not count as ACKs. Android's `@android:` init socket naming is not assumed to mean an abstract namespace.
ATTACH is handled by the Unix socket layer and must be sent without an interface-routing prefix on global sockets as well. See [global control receive](https://github.com/LineageOS/android_external_wpa_supplicant_8/blob/lineage-18.1/wpa_supplicant/ctrl_iface_unix.c#L1108) and Linux's [connected datagram peer filtering](https://github.com/torvalds/linux/blob/v4.9/net/unix/af_unix.c#L1728). Android's listening server endpoint still uses its discovered filesystem address; only the client reply endpoint is abstract.
Missing descriptor access, denied `setsockcreate`, wrong labels or a missing reply
fail before a mutating command. Successful ping is not a guarantee of a later ACK:
an uncertain mutation still retains its restoration obligation.
`CLEAR_EXT_LISTEN` is an owner operation: unused clients do not launch a helper,
ARP-only owners do not touch driver state, and acknowledged clear releases that
owner's EOF obligation so it cannot clear a later owner's state.
Optional monitoring is attempted once per generation/attempt, not an unbounded helper-respawn loop. Framework broadcasts remain active on monitor failure. Monitor idle receives periodically observe owner EOF, and teardown reaps helpers off the UI thread instead of joining for 500 ms.

Kernel source authority: [Linux SELinux socket/inode hooks](https://github.com/torvalds/linux/blob/v6.1/security/selinux/hooks.c)
and [libselinux per-thread proc attributes](https://github.com/SELinuxProject/selinux/blob/master/libselinux/src/procattr.c).
KernelSU must permit discovery and scoped socket creation; denial is explicit.
Host tests/IPC validate ownership and scope invariants, not actual vendor policy.
An enforcing-device run is still required to verify the complete framework,
supplicant and Miracast session on the reported POCO.


## Port Assignments

| Port | Purpose |
|---|---|
| 7236 | RTSP control (TCP) — advertised in WFD IE |
| 15550 | RTP video (UDP) |
| 15551 | Reserved adjacent RTCP port; optional RTCP is not requested without report support |
| 1902 | Source RTP server port (from SETUP response) |
