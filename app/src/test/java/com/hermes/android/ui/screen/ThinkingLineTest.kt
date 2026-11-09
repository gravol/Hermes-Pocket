package com.hermes.android.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The thinking line: which status is Hermes' kaomoji, and which kind of work a tool is. */
class ThinkingLineTest {

    @Test
    fun `reasoning rate is explicitly estimated from character throughput`() {
        assertEquals(10.0, estimatedThinkingTokensPerSecond(40.0), 0.0001)
        assertEquals(0.0, estimatedThinkingTokensPerSecond(0.0), 0.0001)
    }

    @Test
    fun `live preview retains at most four recent lines and caps text`() {
        assertEquals("two\nthree\nfour\nfive", liveThinkingPreview("one\ntwo\nthree\nfour\nfive\n"))
        assertEquals("short", liveThinkingPreview("short"))
        assertEquals(1200, liveThinkingPreview("a".repeat(1500)).length)
    }

    @Test
    fun `kaomoji status splits into face and verb`() {
        // The exact shape agent/turn_iteration_prep.py sends: f"{face} {verb}..."
        assertEquals(KawaiiStatus("(◕‿◕✿)", "pondering"), parseKawaiiStatus("(◕‿◕✿) pondering..."))
        assertEquals(KawaiiStatus("( •_•)>⌐■-■", "musing"), parseKawaiiStatus("( •_•)>⌐■-■ musing..."))
        assertEquals(KawaiiStatus("( ˘▽˘)っ", "mulling"), parseKawaiiStatus("( ˘▽˘)っ mulling..."))
        assertEquals(KawaiiStatus("ಠ_ಠ", "reasoning"), parseKawaiiStatus("ಠ_ಠ reasoning..."))
        // A localized verb list (locales/ar.yaml) can hold a two-word verb.
        assertEquals(KawaiiStatus("(⊙_⊙)", "يزن الأمور"), parseKawaiiStatus("(⊙_⊙) يزن الأمور..."))
    }

    @Test
    fun `a real notice is not mistaken for a kaomoji`() {
        assertNull(parseKawaiiStatus("Waiting for the provider..."))
        assertNull(parseKawaiiStatus("Still waiting 30s..."))
        assertNull(parseKawaiiStatus("Retrying (2/3)"))
        assertNull(parseKawaiiStatus(""))
    }

    @Test
    fun `tools map onto a few kinds of work`() {
        assertEquals(HxTraceActivity.Terminal, HxTraceActivity.of("terminal"))
        assertEquals(HxTraceActivity.Terminal, HxTraceActivity.of("execute_code"))
        assertEquals(HxTraceActivity.Web, HxTraceActivity.of("web_search"))
        assertEquals(HxTraceActivity.Web, HxTraceActivity.of("web_extract"))
        assertEquals(HxTraceActivity.Web, HxTraceActivity.of("browser_cdp"))
        assertEquals(HxTraceActivity.FileRead, HxTraceActivity.of("read_file"))
        assertEquals(HxTraceActivity.FileRead, HxTraceActivity.of("search_files"))
        assertEquals(HxTraceActivity.FileWrite, HxTraceActivity.of("write_file"))
        assertEquals(HxTraceActivity.FileWrite, HxTraceActivity.of("patch"))
        assertEquals(HxTraceActivity.Other, HxTraceActivity.of("memory"))
        assertEquals(HxTraceActivity.Other, HxTraceActivity.of("delegate_task"))
    }
}
