package com.pixlory.color.by.number.activities

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseLocalizedStringsTest {
    @Test
    fun percentageFormatterProducesTheStringPassedToLocalizedResources() {
        assertEquals(
            "92.35%",
            TimelapsePercentageFormatter.format(92.35f, Locale.US)
        )
    }

    @Test
    fun everyLocalizedTimelapsePercentageMessageAcceptsTheFormattedPercentageString() {
        val valuesDirectories = File("src/main/res")
            .listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            .orEmpty()
        val percentage = "12.34%"

        valuesDirectories.forEach { valuesDirectory ->
            val stringsFile = File(valuesDirectory, "strings.xml")
            if (!stringsFile.isFile) return@forEach

            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(stringsFile)
            listOf(
                "timelapse_one_more_format",
                "timelapse_surpassed_format"
            ).forEach { resourceName ->
                val format = document.getElementsByTagName("string")
                    .let { strings ->
                        (0 until strings.length)
                            .map(strings::item)
                            .first { it.attributes.getNamedItem("name").nodeValue == resourceName }
                            .textContent
                    }

                assertTrue(
                    "${valuesDirectory.name}/$resourceName must accept the String passed by getHighlightedPercentageText()",
                    format.contains("%1$" + "s")
                )
                assertTrue(
                    "${valuesDirectory.name}/$resourceName must display the supplied percentage",
                    String.format(format, percentage).contains(percentage)
                )
            }
        }
    }
}
