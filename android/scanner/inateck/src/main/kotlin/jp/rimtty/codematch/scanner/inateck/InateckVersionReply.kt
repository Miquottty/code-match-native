package jp.rimtty.codematch.scanner.inateck

/**
 * Collects the scanner's reply to the software-version read so the
 * diagnostics can show which model and firmware a log came from.
 *
 * The reply is one frame — `F2`, length, `7F 70`, the version text, checksum —
 * and it is longer than one notification: a BCST-36 answers with 26 bytes
 * (`BCST-36 V2.6.16 AI JP`), which Android's default ATT MTU delivers as 20 + 6.
 * SDK 2.0.0 parses only the first notification of this read (its settings
 * read accumulates, its version read does not), so `getVersion` always failed
 * on Android while the same scanner answered on iOS, where the reply arrives
 * in one piece. The gateway feeds every notification of the device-info read
 * through [offer] and uses [version] when the SDK reports a failure.
 *
 * Only this one frame is consumed: bytes that do not start it are left to
 * their normal owner, and nothing is taken after it is complete.
 */
internal class InateckVersionReply {
    private val buffer = ArrayList<Byte>(MAX_FRAME_LENGTH)
    private var complete = false

    /** Returns true when [chunk] was taken as part of the version frame. */
    fun offer(chunk: ByteArray): Boolean {
        if (complete || chunk.isEmpty()) return false
        if (buffer.isEmpty() && !startsFrame(chunk)) return false
        for (byte in chunk) {
            if (buffer.size >= MAX_FRAME_LENGTH) break
            buffer += byte
        }
        val expected = expectedLength()
        when {
            expected == null -> Unit
            expected > MAX_FRAME_LENGTH || buffer.size > expected -> buffer.clear()
            buffer.size == expected -> complete = true
        }
        return true
    }

    /** The version text, or null until a whole frame with a valid checksum arrived. */
    fun version(): String? {
        if (!complete) return null
        val frame = buffer.toByteArray()
        val checksum = frame.dropLast(1).sumOf { it.toInt() and 0xFF } and 0xFF
        if (checksum != (frame.last().toInt() and 0xFF)) return null
        val text = frame.copyOfRange(HEADER_LENGTH, frame.size - 1)
            .toString(Charsets.US_ASCII)
            .filter { it in ' '..'~' }
            .trim()
        return text.takeIf { it.isNotEmpty() }
    }

    private fun startsFrame(chunk: ByteArray): Boolean =
        chunk.size >= HEADER_LENGTH &&
            chunk[0] == READ_REPLY &&
            chunk[2] == ADDRESS_HIGH &&
            chunk[3] == ADDRESS_LOW &&
            (chunk[1].toInt() and 0xFF) > ADDRESS_LENGTH

    private fun expectedLength(): Int? =
        buffer.getOrNull(1)?.let { (it.toInt() and 0xFF) + FRAME_OVERHEAD }

    private companion object {
        const val READ_REPLY: Byte = 0xF2.toByte()
        const val ADDRESS_HIGH: Byte = 0x7F
        const val ADDRESS_LOW: Byte = 0x70
        const val ADDRESS_LENGTH = 2
        const val HEADER_LENGTH = 4
        /** Opcode, length byte and checksum are not counted by the length byte. */
        const val FRAME_OVERHEAD = 3
        const val MAX_FRAME_LENGTH = 96
    }
}
