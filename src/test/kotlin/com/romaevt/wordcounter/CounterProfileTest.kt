package com.romaevt.wordcounter

import org.junit.Assert.assertEquals
import org.junit.Test

class CounterProfileTest {
    @Test fun `memory reports use decimal units and preserve mean bytes`() {
        assertEquals("0.0 bytes", CounterProfile.formatBytes(0.0))
        assertEquals("999.5 bytes", CounterProfile.formatBytes(999.5))
        assertEquals("1.00 KB (1000.0 bytes)", CounterProfile.formatBytes(1_000.0))
        assertEquals("1.02 KB (1024.5 bytes)", CounterProfile.formatBytes(1_024.5))
        assertEquals("1.00 MB (1000000.0 bytes)", CounterProfile.formatBytes(1_000_000.0))
    }
}
