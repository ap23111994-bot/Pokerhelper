package com.example.pokerhelper

import org.junit.Assert.assertEquals
import org.junit.Test

class SeatPositionCalculatorTest {
    @Test fun headsUpButtonIsSmallBlind() {
        assertEquals(mapOf(7 to "BTN/SB", 2 to "BB"), SeatPositionCalculator.positions(listOf(7, 2)))
    }

    @Test fun sixMaxPositions() {
        assertEquals(
            listOf("BTN", "SB", "BB", "UTG", "HJ", "CO"),
            SeatPositionCalculator.positions(listOf(0, 1, 2, 3, 4, 5)).values.toList()
        )
    }

    @Test fun nineMaxAddsEarlyPositions() {
        assertEquals(
            listOf("BTN", "SB", "BB", "UTG", "UTG+1", "UTG+2", "LJ", "HJ", "CO"),
            SeatPositionCalculator.positions((0..8).toList()).values.toList()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateSeatIdsRejected() {
        SeatPositionCalculator.positions(listOf(1, 2, 2, 3))
    }
}
