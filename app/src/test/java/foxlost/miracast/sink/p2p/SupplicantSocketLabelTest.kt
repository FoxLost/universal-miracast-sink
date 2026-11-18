package foxlost.miracast.sink.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupplicantSocketLabelTest {
    private val context = "u:r:vendor_supplicant:s0"

    @Test fun discoversDatagramSockfsInodeNotFilesystemMetadata() {
        val table = """
            Num       RefCount Protocol Flags    Type St Inode Path
            00000000: 00000002 00000000 00000000 0002 01 786 /data/vendor/wifi/p2p0
            00000001: 00000002 00000000 00000000 0001 01 900 /data/vendor/wifi/wlan0
        """.trimIndent()
        assertEquals(786L, SupplicantSocketLabel.socketInode(table, "/data/vendor/wifi/p2p0"))
        assertThrows(IllegalStateException::class.java) {
            SupplicantSocketLabel.socketInode(table, "/data/vendor/wifi/wlan0")
        }
    }

    @Test fun rejectsChangedOrAmbiguousEndpointIdentity() {
        val before = "000: 2 0 0 0002 01 786 /dev/socket/wpa_wlan0"
        val after = before.replace("786", "787")
        assertEquals(786L, SupplicantSocketLabel.socketInode(before, "/dev/socket/wpa_wlan0"))
        assertEquals(787L, SupplicantSocketLabel.socketInode(after, "/dev/socket/wpa_wlan0"))
        assertThrows(IllegalStateException::class.java) {
            SupplicantSocketLabel.socketInode(before + "\n" + after, "/dev/socket/wpa_wlan0")
        }
        assertThrows(IllegalStateException::class.java) {
            SupplicantSocketLabel.socketInode(before.replace("wpa_wlan0", "wpa_wlan1"), "/dev/socket/wpa_wlan0")
        }
    }

    @Test fun acceptsOnlyOneCompleteKernelContext() {
        for (valid in listOf(context, "$context\u0000", "$context:c0.c1023", "$context:c1,c3-s1:c2")) {
            assertEquals(valid.removeSuffix("\u0000"),
                SupplicantSocketLabel.validatedContext(valid.toByteArray()))
        }
        for (invalid in listOf("", "u:r:supplicant", "$context\n", "$context\u0000\u0000",
            "$context\u0000u:r:other:s0", "$context u:r:other:s0", "$context:c", "$context:c1..c3",
            "u:r:supplicant:s0:c1,2", "u:r:supplicant:s0:c1-s", "u:r:supplicant:s0\u0001")) {
            assertThrows(IllegalStateException::class.java) {
                SupplicantSocketLabel.validatedContext(invalid.toByteArray())
            }
        }
    }

    @Test fun malformedContextNeverWritesThreadAttribute() {
        var writes = 0
        assertThrows(IllegalStateException::class.java) {
            SupplicantSocketLabel.withCreationContext("$context\n", { byteArrayOf() },
                { writes++ }) { error("must not create") }
        }
        assertEquals(0, writes)
    }

    @Test fun onlyExactNeutralOperationsBypassFailureCache() {
        for (command in listOf("P2P_SET disallow_freq ", "P2P_EXT_LISTEN 0 0",
            "WFD_SUBELEM_SET 0 ", "WFD_SUBELEM_SET 7 ")) {
            assertEquals(true, SupplicantWriter.isNeutral(command))
        }
        for (command in listOf("P2P_SET disallow_freq 0-2400,2500-7125",
            "P2P_SET disallow_freq", "P2P_EXT_LISTEN 500 500", "WFD_SUBELEM_SET 7 00020001")) {
            assertEquals(false, SupplicantWriter.isNeutral(command))
        }
    }

    @Test fun authoritativePeerSidAvoidsProcessDescriptorDiscovery() {
        assertEquals(context, SupplicantSocketLabel.discoverVerifiedContext(
            { context.toByteArray() }, { error("Must not scan /proc when peer SID is available") }))
    }

    @Test fun unavailablePeerSidFallsBackToAuthoritativeSockfsLabel() {
        assertEquals(context, SupplicantSocketLabel.discoverVerifiedContext(
            { throw java.io.IOException("SCM_SECURITY unavailable") }, { context.toByteArray() }))
    }

    @Test fun malformedPeerSidDoesNotFallBackToAnotherIdentity() {
        assertThrows(IllegalStateException::class.java) {
            SupplicantSocketLabel.discoverVerifiedContext(
                { "$context\u0000$context".toByteArray() }, { error("Must reject malformed peer label") })
        }
    }

    @Test fun failedPeerAndSockfsDiscoveryExposeBothReasons() {
        val failure = assertThrows(java.io.IOException::class.java) {
            SupplicantSocketLabel.discoverVerifiedContext(
                { throw java.io.IOException("recvmsg errno=11") },
                { throw java.io.IOException("pids=123 fds=5000 matching=1 label_errno=13") })
        }
        assertEquals(true, failure.message.orEmpty().contains("recvmsg errno=11"))
        assertEquals(true, failure.message.orEmpty().contains("label_errno=13"))
        assertEquals(1, failure.cause?.suppressed?.size)
    }

    @Test fun clearsInitiallyUnsetContextBeforeReturningSocket() {
        var current = byteArrayOf()
        SupplicantSocketLabel.withCreationContext(context, { current }, {
            current = it
        }) {
            assertEquals(context + '\u0000', current.toString(Charsets.UTF_8))
        }
        assertArrayEquals(byteArrayOf(), current)
    }

    @Test fun restoresExistingContextWhenCreationFails() {
        val previous = "u:r:previous:s0\u0000".toByteArray()
        var current = previous
        assertThrows(IllegalArgumentException::class.java) {
            SupplicantSocketLabel.withCreationContext(context, { current }, { current = it }) {
                throw IllegalArgumentException("socket creation denied")
            }
        }
        assertArrayEquals(previous, current)
    }

    @Test fun neverCreatesIfContextWriteIsNotApplied() {
        var creates = 0
        assertThrows(IllegalStateException::class.java) {
            SupplicantSocketLabel.withCreationContext(context, { byteArrayOf() }, { }) { creates++ }
        }
        assertEquals(0, creates)
    }

    @Test fun restorationFailureDoesNotReturnCreatedSocket() {
        var current = byteArrayOf()
        var created = false
        assertThrows(IllegalStateException::class.java) {
            SupplicantSocketLabel.withCreationContext(context, { current }, {
                if (it.isNotEmpty()) current = it
            }) { created = true }
        }
        assertEquals(true, created)
    }

    @Test fun failedSetStillAttemptsRestore() {
        val previous = byteArrayOf()
        var current = previous
        assertThrows(SecurityException::class.java) {
            SupplicantSocketLabel.withCreationContext(context, { current }, {
                current = it
                if (it.isNotEmpty()) throw SecurityException("setsockcreate denied after changing context")
            }) { error("must not create") }
        }
        assertArrayEquals(previous, current)
    }
}
