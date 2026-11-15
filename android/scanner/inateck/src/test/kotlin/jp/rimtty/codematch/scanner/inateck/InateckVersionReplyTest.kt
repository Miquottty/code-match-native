package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckVersionReplyTest {
    // The reply a BCST-36 sent on 2026-11-15 (26 bytes): F2, length 0x17,
    // address 7F70, "BCST-36 V2.6.16 AI JP", checksum.
    private val frame = hex("f2177f70424353542d33362056322e362e3136204149204a50bf")

    @Test fun replySplitAtTheDefaultMtuIsPutBackTogether() {
        val reply = InateckVersionReply()
        assertTrue(reply.offer(frame.copyOfRange(0, 20)))
        assertNull(reply.version())
        assertTrue(reply.offer(frame.copyOfRange(20, frame.size)))
        assertEquals("BCST-36 V2.6.16 AI JP", reply.version())
    }

    @Test fun replyInOnePieceIsAccepted() {
        val reply = InateckVersionReply()
        assertTrue(reply.offer(frame))
        assertEquals("BCST-36 V2.6.16 AI JP", reply.version())
    }

    @Test fun nothingIsTakenBeforeTheFrameStartsOrAfterItIsComplete() {
        val reply = InateckVersionReply()
        // A settings reply, an auth reply and scan data are not this frame.
        assertFalse(reply.offer(hex("f22c7f80030a0402")))
        assertFalse(reply.offer(hex("f1000100f2")))
        assertFalse(reply.offer(hex("c14344")))
        assertFalse(reply.offer(ByteArray(0)))
        assertNull(reply.version())

        assertTrue(reply.offer(frame))
        assertFalse(reply.offer(hex("c14344")))
        assertEquals("BCST-36 V2.6.16 AI JP", reply.version())
    }

    @Test fun emptyOrDamagedReplyGivesNoVersion() {
        // A scanner that is not ready answers the read with no data.
        val empty = InateckVersionReply()
        assertFalse(empty.offer(hex("f2027f70e3")))
        assertNull(empty.version())

        val badChecksum = InateckVersionReply()
        val damaged = frame.clone().also { it[it.size - 1] = 0x00 }
        assertTrue(badChecksum.offer(damaged))
        assertNull(badChecksum.version())

        val tooLong = InateckVersionReply()
        assertTrue(tooLong.offer(frame + byteArrayOf(0x01)))
        assertNull(tooLong.version())
    }

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
