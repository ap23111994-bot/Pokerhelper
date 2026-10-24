package com.example.pokerhelper

/**
 * Maps detected occupied seats to standard Hold'em positions.
 *
 * [clockwiseFromButton] must be ordered around the table starting with the
 * seat carrying the dealer button, followed by the next occupied seat clockwise.
 * Visual seat/button detection is a separate computer-vision stage.
 */
object SeatPositionCalculator {
    fun positions(clockwiseFromButton: List<Int>): Map<Int, String> {
        require(clockwiseFromButton.size in 2..10) { "A table must have 2 to 10 occupied seats" }
        require(clockwiseFromButton.distinct().size == clockwiseFromButton.size) { "Seat IDs must be unique" }
        val n = clockwiseFromButton.size
        val labels = when (n) {
            2 -> listOf("BTN/SB", "BB")
            3 -> listOf("BTN", "SB", "BB")
            4 -> listOf("BTN", "SB", "BB", "UTG")
            5 -> listOf("BTN", "SB", "BB", "UTG", "CO")
            6 -> listOf("BTN", "SB", "BB", "UTG", "HJ", "CO")
            else -> {
                val earlyPositions = (1 until (n - 6)).map { "UTG+$it" }
                listOf("BTN", "SB", "BB", "UTG") + earlyPositions + listOf("LJ", "HJ", "CO")
            }
        }
        return clockwiseFromButton.zip(labels).toMap()
    }
}
