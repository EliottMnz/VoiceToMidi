package com.example.voicetomidi.notes

import org.junit.Assert.*
import org.junit.Test

class TapTempoTest {
    @Test fun `deux taps donnent le tempo et les suivants stabilisent les hesitations`() {
        val tap = TapTempo()
        assertNull(tap.tap(0))
        assertEquals(120, tap.tap(500))
        tap.tap(1010)
        tap.tap(1500)
        assertEquals(120.0, tap.tap(2500)!!.toDouble(), 1.0)
    }

    @Test fun `un double tap accidentel ne decale pas la pulsation`() {
        val tap = TapTempo()
        tap.tap(0)
        assertNull(tap.tap(60))
        assertEquals(120, tap.tap(500))
    }

    @Test fun `une pause ou une correction manuelle remet le compteur a zero`() {
        val tap = TapTempo()
        tap.tap(0); tap.tap(500)
        assertNull(tap.tap(3000))
        assertEquals(60, tap.tap(4000))
        tap.reset()
        assertNull(tap.tap(4500))
        assertEquals(240, tap.tap(4750))
    }

    @Test fun `les bornes de tempo sont respectees`() {
        val fast = TapTempo(); fast.tap(0)
        assertEquals(300, fast.tap(200))
        val slow = TapTempo(); slow.tap(0)
        assertEquals(40, slow.tap(1500))
    }
}
