package com.billbook.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnownNamesTest {

    private val saved = listOf(
        "Pizza Hut # 127",
        "Department for Registration of Persons",
        "Keells Super",
    )

    @Test
    fun misreadWordStillMatchesSavedName() =
        assertEquals(
            "Department for Registration of Persons",
            KnownNames.bestMatch(listOf("DEPARTMENT OF REGISTRATION OF PERSONS"), saved),
        )

    @Test
    fun pieceOfTheNameMatchesSavedName() =
        assertEquals(
            "Department for Registration of Persons",
            KnownNames.bestMatch(listOf("Department of Reg", "Registration of Persons"), saved),
        )

    @Test
    fun smallOcrSlipsMatch() =
        assertEquals("Keells Super", KnownNames.bestMatch(listOf("KEELLS SUPFR"), saved))

    @Test
    fun unrelatedNamesDoNotMatch() {
        assertNull(KnownNames.bestMatch(listOf("Cargills Food City"), saved))
        assertNull(KnownNames.bestMatch(listOf("Persons"), saved))        // too short to be sure
        assertNull(KnownNames.bestMatch(listOf("anything"), emptyList()))
    }
}
