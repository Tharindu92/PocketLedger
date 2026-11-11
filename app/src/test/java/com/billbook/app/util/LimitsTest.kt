package com.billbook.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LimitsTest {

    @Test
    fun copiesUpToTheLimit() {
        val out = ByteArrayOutputStream()
        val copied = ByteArrayInputStream(ByteArray(100_000)).copyToLimited(out, 100_000)
        assertEquals(100_000L, copied)
        assertEquals(100_000, out.size())
    }

    @Test
    fun stopsOneByteOverTheLimit() {
        val e = assertThrows(TooLargeException::class.java) {
            ByteArrayInputStream(ByteArray(100_001)).copyToLimited(ByteArrayOutputStream(), 100_000)
        }
        assertEquals(100_000L, e.limit)
    }

    @Test
    fun readsTextWithinLimit() {
        assertEquals("""{"eur":{"lkr":350.5}}""",
            ByteArrayInputStream("""{"eur":{"lkr":350.5}}""".toByteArray()).readTextLimited(1024))
    }
}
