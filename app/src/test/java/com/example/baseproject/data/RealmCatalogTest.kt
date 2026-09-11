package com.pixlory.color.by.number.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealmCatalogTest {

    @Test
    fun dashAndUnderscoreIdsFindTheSameRealm() {
        val realm = RealmCatalog.findById("sakura-haven")

        assertEquals("sakura_haven", realm?.id)
    }

    @Test
    fun dashAndUnderscoreIdsCompareAsEqual() {
        assertTrue(RealmCatalog.idsMatch("sakura-haven", "sakura_haven"))
    }
}
