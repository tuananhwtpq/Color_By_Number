package com.pixlory.color.by.number.activities

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseLocalizedStringsTest {
    @Test
    fun hindiPercentageMessagesFormatTheSuppliedPercentage() {
        val stringsFile = File("src/main/res/values-hi/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
        val percentage = "12.34%"

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
            val formatted = String.format(Locale.forLanguageTag("hi"), format, percentage)

            assertTrue(
                "$resourceName must include the supplied percentage",
                formatted.contains(percentage)
            )
        }
    }
}
