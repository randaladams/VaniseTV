package com.vanisetv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every screen must work in English and Portuguese: each English string needs a Portuguese
 * one, and placeholders (%1$s, %2$d …) must match so the text formats the same in both.
 */
class TranslationsTest {
    private val nameRegex = Regex("""<string name="([^"]+)"(?: translatable="false")?>(.*?)</string>""")
    private val placeholder = Regex("""%\d+\$[sd]""")

    private fun load(dir: String): Map<String, String> {
        val f = File("src/main/res/$dir/strings.xml")
        assertTrue("Missing ${f.path}", f.exists())
        return nameRegex.findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun everyEnglishStringHasPortuguese() {
        val en = load("values").filterKeys { it != "app_name" }
        val pt = load("values-pt")
        val missing = en.keys - pt.keys
        assertTrue("Missing Portuguese for: $missing", missing.isEmpty())
        val extra = pt.keys - en.keys
        assertTrue("Portuguese strings with no English: $extra", extra.isEmpty())
    }

    @Test fun placeholdersMatch() {
        val en = load("values")
        val pt = load("values-pt")
        for ((k, v) in en) {
            val p = pt[k] ?: continue
            assertEquals("Placeholders differ in '$k'",
                placeholder.findAll(v).map { it.value }.toSortedSet(),
                placeholder.findAll(p).map { it.value }.toSortedSet())
        }
    }

    @Test fun noHardCodedTextInLayouts() {
        val bad = File("src/main/res/layout").listFiles().orEmpty().flatMap { f ->
            Regex("""android:(text|hint)="([^@"][^"]*)"""").findAll(f.readText()).map { "${f.name}: ${it.value}" }.toList()
        }
        assertTrue("Layouts must use @string/ for visible text: $bad", bad.isEmpty())
    }
}
