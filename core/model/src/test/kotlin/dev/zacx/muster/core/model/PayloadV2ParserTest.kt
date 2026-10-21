package dev.zacx.muster.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadV2ParserTest {
    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/payload/$name")) { "missing fixture $name" }.readBytes()

    private fun parse(s: String) = PayloadV2Parser.parse(s.encodeToByteArray())

    private fun rejected(s: String) = parse(s) is Push.Rejected

    /** muster's own example (docs/push.md, "Paired phones (payload v2)"). */
    @Test
    fun mustersExampleParses() {
        val p = parse("""{"v":2,"type":"review","id":"42","tag":"task-42","title":"Agent ada","body":"Task 42 in muster","url":"/tasks/42"}""")
        assertEquals(
            Push.Show(PayloadV2("review", "42", "task-42", "Agent ada", "Task 42 in muster", "/tasks/42")),
            p,
        )
    }

    /**
     * 🔴 THE CAP. muster pads every phone push to one 4096-byte aes128gcm
     * record, so the largest plaintext is 4096 − 86 − 16 − 1 = 3993 bytes
     * (muster internal/push NativeMaxPlaintext). The fixture is exactly that
     * size, with the longest title and body muster sends (100 and 200
     * characters, astral emoji included) and a field this phase does not know.
     */
    @Test
    fun aPayloadAtTheCapParses() {
        val bytes = fixture("at-cap.json")
        assertEquals(3993, bytes.size)
        assertEquals(3993, PayloadV2.MAX_PLAINTEXT)
        val p = PayloadV2Parser.parse(bytes)
        assertTrue("got $p", p is Push.Show)
        p as Push.Show
        assertEquals("attention", p.payload.type)
        assertEquals("attention-9001", p.payload.tag)
        assertEquals("/attention?item=9001", p.payload.path)
        // At the limit, nothing is cut.
        assertEquals(100, p.payload.title.codePointCount(0, p.payload.title.length))
        assertEquals(200, p.payload.body.codePointCount(0, p.payload.body.length))
        assertTrue(p.payload.title.endsWith("🤖")) // 🤖, not an ellipsis
    }

    @Test
    fun oneByteOverTheCapIsRefused() {
        val bytes = fixture("over-cap.json")
        assertEquals(3994, bytes.size)
        assertEquals(Push.Rejected("over 3993 bytes (3994)"), PayloadV2Parser.parse(bytes))
    }

    @Test
    fun resolvedWithdrawsByTagAndShowsNothing() {
        assertEquals(Push.Withdraw("task-42"), parse("""{"v":2,"type":"resolved","id":"42","tag":"task-42"}"""))
        // A withdrawal with no tag has nothing to cancel.
        assertTrue(rejected("""{"v":2,"type":"resolved","id":"42"}"""))
    }

    /** muster's registration probe (internal/api/devices.go devicePushProbe). */
    @Test
    fun theRegistrationTestPushDrawsNothing() {
        assertEquals(Push.Probe, parse("""{"v":2,"type":"test"}"""))
    }

    @Test
    fun malformedPayloadsAreRefused() {
        assertTrue(rejected("""{"v":1,"type":"review","title":"x"}"""))
        assertTrue(rejected("""{"v":"2","type":"review","title":"x"}"""))
        assertTrue(rejected("""{"type":"review","title":"x"}"""))
        assertTrue(rejected("""{"v":2,"title":"x"}"""))
        assertTrue(rejected("""{"v":2,"type":"","title":"x"}"""))
        assertTrue(rejected("""{"v":2,"type":7,"title":"x"}"""))
        assertTrue(rejected("""{"v":2,"type":"review","title":["x"]}"""))
        assertTrue(rejected("""{"v":2,"type":"review","title":"x","url":{"a":1}}"""))
        assertTrue(rejected("""{"v":2,"type":"review","title":"   "}"""))
        assertTrue(rejected("""[{"v":2}]"""))
        assertTrue(rejected("""not json"""))
        assertTrue(rejected(""))
        assertTrue(PayloadV2Parser.parse(byteArrayOf(0x7b, 0xff.toByte(), 0x7d)) is Push.Rejected) // not UTF-8
    }

    /** A tap target is a path on the muster origin, never another origin. */
    @Test
    fun onlyAMusterPathIsATapTarget() {
        fun path(u: String) = (parse("""{"v":2,"type":"review","title":"x","url":${kotlinx.serialization.json.JsonPrimitive(u)}}""") as Push.Show).payload.path
        assertEquals("/tasks/42", path("/tasks/42"))
        assertEquals("/attention?x=1#a", path("/attention?x=1#a"))
        assertNull(path("https://evil.example.test/"))
        assertNull(path("//evil.example.test/x"))
        assertNull(path("/\\evil.example.test"))
        assertNull(path("javascript:alert(1)"))
        assertNull(path("tasks/42"))
        assertNull(path("/tasks/42\n"))
        assertNull(path(""))
    }

    /** muster already cuts; the app cuts again and never splits a surrogate pair. */
    @Test
    fun overlongLinesAreCutOnACodePointBoundary() {
        val title = "🤖".repeat(150) // 150 code points, 300 chars
        val p = parse("""{"v":2,"type":"review","title":"$title"}""") as Push.Show
        assertEquals(100, p.payload.title.codePointCount(0, p.payload.title.length))
        assertTrue(p.payload.title.endsWith("…"))
        assertTrue(Character.isHighSurrogate(p.payload.title[p.payload.title.length - 3]))
    }
}
