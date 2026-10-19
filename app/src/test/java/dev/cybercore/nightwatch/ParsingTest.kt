package dev.cybercore.nightwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsingTest {
    @Test
    fun plainHttpOnlyForTailnetNames() {
        assertEquals("http://blackbox.tail9311b5.ts.net:8769/", normalizeBaseUrl("blackbox.tail9311b5.ts.net:8769")?.toString())
        assertEquals("http://blackbox.tail9311b5.ts.net:8769/", normalizeBaseUrl(" http://blackbox.tail9311b5.ts.net:8769/v1/ ")?.toString())
        assertEquals("https://watch.example.com/", normalizeBaseUrl("https://watch.example.com")?.toString())
        assertNull(normalizeBaseUrl("http://blackbox:8769"))
        assertNull(normalizeBaseUrl("http://100.76.111.20:8769"))
        assertNull(normalizeBaseUrl("http://evil.example/ts.net"))
        assertNull(normalizeBaseUrl("https://user:pw@watch.example.com"))
        assertNull(normalizeBaseUrl("not a url"))
        assertNull(normalizeBaseUrl("http://10.0.2.2:8769", emulatorHost = false))
        assertEquals("http://10.0.2.2:8769/", normalizeBaseUrl("http://10.0.2.2:8769", emulatorHost = true)?.toString())
    }

    @Test
    fun sseLines() {
        assertEquals("hi", parseSseData("""data: {"ts":1,"prio":6,"ident":"a","msg":"hi"}""")?.msg)
        assertEquals("raw text", parseSseData("data: raw text")?.msg)
        assertNull(parseSseData(": keep-alive"))
        assertNull(parseSseData("data:"))
        assertNull(parseSseData(""))
    }

    @Test
    fun markdownKeepsTheText() {
        val md = "# CPU\n\n- **Model:** `Ryzen`\n```text\nArchitecture: x86_64\n```\n---"
        val out = renderMarkdown(md).text
        assertEquals("CPU\n\n• Model: Ryzen\n\nArchitecture: x86_64\n\n" + "─".repeat(24), out)
    }

    @Test
    fun unbalancedMarkersDontEatText() {
        assertEquals("a **b", renderMarkdown("a **b").text)
        assertEquals("x `y", renderMarkdown("x `y").text)
    }

    @Test
    fun pairingLinks() {
        val tok = "nw_2b0a7dc2_" + "a".repeat(64)
        val q = mapOf("url" to "http://blackbox.tail9311b5.ts.net:8769", "token" to tok, "name" to "blackbox<script>")
        val p = pairingFrom("nightwatch", "add", q::get)!!
        assertEquals("http://blackbox.tail9311b5.ts.net:8769/", p.url)
        assertEquals(tok, p.token)
        assertEquals("blackboxscript", p.name)
        assertNull(pairingFrom("https", "add", q::get))
        assertNull(pairingFrom("nightwatch", "add", (q + ("url" to "http://evil.example:8769"))::get))
        assertNull(pairingFrom("nightwatch", "add", (q + ("token" to "nw_x_y"))::get))
    }
}
