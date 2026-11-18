package foxlost.miracast.sink.p2p

import android.os.Process
import android.system.Os
import java.io.File

/** Used only by the root app_process entry point, never by the ordinary APK process. */
internal object RootIdentity {
    fun becomeSystem() {
        check(Os.getuid() == 0 && Os.geteuid() == 0) { "Root identity required" }
        loadPrimitive()
        try {
            adoptSystemIdentity()
        } catch (e: LinkageError) {
            throw IllegalStateException("Root identity primitive unavailable", e)
        }
    }

    fun requireSystem() {
        check(Os.getuid() == 1000 && Os.geteuid() == 1000 &&
            Os.getgid() == 1000 && Os.getegid() == 1000) { "System identity required" }
        loadPrimitive()
        try {
            verifySystemIdentity()
        } catch (e: LinkageError) {
            throw IllegalStateException("System identity verification unavailable", e)
        }
    }

    internal fun loadPrimitive() {
        // app_process's library search path does not include the APK's native libraries.
        val apk = checkNotNull(System.getenv("CLASSPATH")) { "Missing helper APK path" }
        check(File(apk).isAbsolute && apk.endsWith(".apk") && ':' !in apk) {
            "Expected one absolute helper APK path"
        }
        val abi = when (Os.uname().machine) {
            "aarch64", "arm64" -> if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
            "arm", "armv7l", "armv8l" -> "armeabi-v7a"
            "x86_64", "amd64" -> if (Process.is64Bit()) "x86_64" else "x86"
            "i386", "i486", "i586", "i686", "x86" -> "x86"
            else -> error("Unsupported helper architecture: ${Os.uname().machine}")
        }
        try {
            System.load("$apk!/lib/$abi/libmiracast_identity.so")
        } catch (e: LinkageError) {
            throw IllegalStateException("Cannot load root identity primitive for $abi", e)
        }
    }

    private external fun adoptSystemIdentity()
    private external fun verifySystemIdentity()
}
