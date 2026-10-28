package io.github.daranna.sqlitebrowser.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbTest {

    // Format of `adb shell cat /proc/net/tcp6 /proc/net/tcp` on an API 32 emulator.
    private val procNetTcp =
        """
          sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 00000000000000000000000000000000:1F90 00000000000000000000000000000000:0000 0A 00000000:00000020 00:00000000 00000000 10199        0 607888 33 0000000000000000 100 0 0 10 0
           1: 00000000000000000000000000000000:1F91 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10200        0 655376 1 0000000000000000 100 0 0 10 0
           2: 0100007F:1F92 0100007F:A1B2 01 00000000:00000000 00:00000000 00000000 10300        0 1 1 0000000000000000 20 4 30 10 -1
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:13AD 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1234 1 0000000000000000 100 0 0 10 0
        """.trimIndent()

    @Test
    fun `listeningUid finds the uid listening on a port`() {
        assertEquals("10199", Adb.listeningUid(procNetTcp, 8080))
        assertEquals("10200", Adb.listeningUid(procNetTcp, 8081))
        assertEquals("1000", Adb.listeningUid(procNetTcp, 5037))
    }

    @Test
    fun `listeningUid ignores connections that are not listening`() {
        // Port 8082 (1F92) only appears as an established connection (state 01).
        assertNull(Adb.listeningUid(procNetTcp, 8082))
        assertNull(Adb.listeningUid(procNetTcp, 9999))
    }

    @Test
    fun `packageForUid maps a uid to its package`() {
        val pmList =
            """
            package:com.android.cts.priv.ctsshim uid:10146
            package:io.github.daranna.sqlitebrowser.sample uid:10200
            package:com.example.shared uid:10300,10301
            """.trimIndent()

        assertEquals("io.github.daranna.sqlitebrowser.sample", Adb.packageForUid(pmList, "10200"))
        assertEquals("com.example.shared", Adb.packageForUid(pmList, "10301"))
        assertNull(Adb.packageForUid(pmList, "10146" + "9"))
    }
}
