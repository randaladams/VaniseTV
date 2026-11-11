package com.vanisetv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snapshots from 09/11/2026: iptv-org br.m3u (402 channels) and famelack br.json (132 entries). */
class SourcesTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val m3u = res("br.m3u")
    private val famelack = res("famelack-br.json")

    @Test fun famelackPageResolvesToDataFile() {
        assertEquals(
            "https://raw.githubusercontent.com/famelack/famelack-data/main/tv/countries/br.json",
            Sources.resolve("https://famelack.com/tv/br")
        )
        assertEquals(Sources.DEFAULT_1, Sources.resolve(Sources.DEFAULT_1))
    }

    @Test fun eitherFormatIsDetectedFromContent() {
        assertEquals(402, Sources.parse(m3u).channels.size)
        val f = Sources.parse(famelack)
        assertEquals(132, f.channels.size + f.skippedYoutube)
        assertTrue(f.skippedYoutube > 0)
    }

    @Test fun mergeKeepsSourceOneAndAddsOnlyNewChannels() {
        val merged = Sources.merge(listOf(Sources.parse(m3u), Sources.parse(famelack)))
        assertEquals(402, merged.fromSource1)
        assertTrue("expected a handful of new channels, got ${merged.newFromSource2}", merged.newFromSource2 in 3..20)
        assertEquals(merged.fromSource1 + merged.newFromSource2, merged.channels.size)
        assertTrue(merged.channels.take(402).all { it.source == 1 })
        assertTrue(merged.channels.drop(402).all { it.source == 2 })
        // No URL appears twice across sources
        val s2urls = merged.channels.filter { it.source == 2 }.map { it.url }.toSet()
        assertTrue(merged.channels.filter { it.source == 1 }.none { it.url in s2urls })
    }

    @Test fun sourcesCanBeSwapped() {
        val merged = Sources.merge(listOf(Sources.parse(famelack), Sources.parse(m3u)))
        assertTrue(merged.channels.first().source == 1)
        assertTrue(merged.fromSource1 > 100)
        assertTrue(merged.newFromSource2 > 250)
    }

    @Test fun emptySlotIsIgnored() {
        val merged = Sources.merge(listOf(null, Sources.parse(m3u)))
        assertEquals(0, merged.fromSource1)
        assertEquals(402, merged.newFromSource2)
    }

    @Test fun sourceMarkerSurvivesTheLocalFile() {
        val merged = Sources.merge(listOf(Sources.parse(m3u), Sources.parse(famelack)))
        val text = M3u.write(merged.channels)
        assertTrue(text.contains("vanise-source=\"2\""))
        assertEquals(merged.channels.map { it.source }, M3u.parse(text).map { it.source })
    }

    @Test fun nameMatchingIgnoresAccentsAndPunctuation() {
        assertEquals(Sources.normalize("TV Câmara"), Sources.normalize("tv camara"))
        assertEquals(Sources.normalize("+MaisUm"), Sources.normalize("Mais Um"))
    }
}
