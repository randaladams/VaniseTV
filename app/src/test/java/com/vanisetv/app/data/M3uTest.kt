package com.vanisetv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a snapshot of https://iptv-org.github.io/iptv/countries/br.m3u (09/11/2026). */
class M3uTest {
    private val text = javaClass.classLoader!!.getResource("br.m3u")!!.readText()

    @Test fun parsesEveryChannel() {
        val list = M3u.parse(text)
        assertEquals(402, list.size)
        assertTrue(list.all { it.url.startsWith("http") && it.name.isNotBlank() })
    }

    @Test fun stripsQualityAndTagsFromNames() {
        val list = M3u.parse(text)
        val rio = list.first { it.name == "RecordTV Rio" }
        assertEquals("720p", rio.quality)
        assertTrue(rio.geoBlocked)
        val agro = list.first { it.name == "AgroBrasil TV" }
        assertTrue(agro.not247)
        assertFalse(list.any { it.name.contains("[") || it.name.contains("(720p)") })
    }

    @Test fun findsGeoBlockedAndNovelas() {
        val list = M3u.parse(text)
        assertEquals(13, list.count { it.geoBlocked })
        assertEquals(setOf("Globoplay Novelas", "Pluto TV Novelas BR", "TNT Novelas Brazil"),
            list.filter { it.isNovela }.map { it.name }.toSet())
    }

    @Test fun splitsMultipleGroups() {
        val maisUm = M3u.parse(text).first { it.name == "+MaisUm" }
        assertEquals(listOf("Entertainment", "General", "Movies", "Music"), maisUm.groups)
    }

    @Test fun hiddenFlagSurvivesWriteAndRead() {
        val list = M3u.parse(text)
        list.forEachIndexed { i, c -> c.hidden = i % 3 == 0 }
        val again = M3u.parse(M3u.write(list))
        assertEquals(list.size, again.size)
        assertEquals(list.map { it.hidden }, again.map { it.hidden })
        assertEquals(list.map { it.key }, again.map { it.key })
        assertEquals(list.map { it.url }, again.map { it.url })
        // Un-hiding removes the attribute again
        again.forEach { it.hidden = false }
        assertFalse(M3u.write(again).contains("vanise-hidden"))
    }
}
