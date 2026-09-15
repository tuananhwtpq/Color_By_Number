package com.pixlory.color.by.number.activities

import java.util.Locale

internal object TimelapsePercentageFormatter {
    fun format(percentage: Float, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.2f%%", percentage)
}
