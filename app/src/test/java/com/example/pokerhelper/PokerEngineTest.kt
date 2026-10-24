package com.example.pokerhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerEngineTest {
    private fun c(rank: Int, suit: Char = 's') = PlayingCard(rank, suit)
    @Test fun wheelStraightIsFiveHigh() {
        val v = PokerEngine.evaluateFive(listOf(c(14), c(2, 'h'), c(3, 'd'), c(4, 'c'), c(5)))
        assertEquals(4, v.category); assertEquals(listOf(5), v.kickers)
    }
    @Test fun fullHouseBeatsFlush() {
        val fh = PokerEngine.evaluateFive(listOf(c(10, 's'), c(10, 'h'), c(10, 'd'), c(8, 's'), c(8, 'c')))
        val fl = PokerEngine.evaluateFive(listOf(c(14, 'h'), c(11, 'h'), c(8, 'h'), c(5, 'h'), c(2, 'h')))
        assertTrue(fh > fl)
    }
    @Test fun kickerBreaksPairTie() {
        val a = PokerEngine.evaluateFive(listOf(c(14, 's'), c(14, 'h'), c(13, 'd'), c(9, 'c'), c(4, 's')))
        val b = PokerEngine.evaluateFive(listOf(c(14, 'd'), c(14, 'c'), c(12, 'h'), c(9, 's'), c(4, 'd')))
        assertTrue(a > b)
    }
    @Test fun sevenCardsFindStraightFlush() {
        val v = PokerEngine.bestHand(listOf(c(14, 's'), c(13, 's'), c(12, 's'), c(11, 's'), c(10, 's'), c(2, 'd'), c(3, 'c')))
        assertEquals(8, v.category); assertEquals(listOf(14), v.kickers)
    }
    @Test fun potOddsAndEquityAreValid() {
        val r = PokerEngine.analyze(listOf(c(14, 's'), c(14, 'h')), listOf(c(2, 'd'), c(7, 'c'), c(9, 's')), 100.0, 25.0, 100, 42L)
        assertEquals(0.2, r.potOdds, 0.0001); assertTrue(r.equity in 0.0..1.0)
    }
    @Test fun recommendationFoldsWhenEquityBelowPotOdds() {
        assertEquals(PokerAction.FOLD, PokerEngine.recommendAction(0.15, 0.30, 100.0, 30.0))
    }

    @Test fun recommendationCallsWithEnoughEquityButNoRaiseEdge() {
        assertEquals(PokerAction.CALL, PokerEngine.recommendAction(0.42, 0.30, 100.0, 30.0))
    }

    @Test fun recommendationRaisesWithStrongEquityAndSmallCall() {
        assertEquals(PokerAction.RAISE, PokerEngine.recommendAction(0.82, 0.20, 100.0, 20.0))
    }

    @Test fun noCallAmountProducesCheckOrBet() {
        assertEquals(PokerAction.CHECK, PokerEngine.recommendAction(0.35, 0.0, 100.0, 0.0))
        assertEquals(PokerAction.BET, PokerEngine.recommendAction(0.75, 0.0, 100.0, 0.0))
    }

}
