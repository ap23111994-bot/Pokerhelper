package com.example.pokerhelper

import kotlin.math.max

data class PlayingCard(val rank: Int, val suit: Char) {
    init { require(rank in 2..14); require(suit in "cdhs") }
    val label: String get() = (when (rank) { 14 -> "A"; 13 -> "K"; 12 -> "Q"; 11 -> "J"; 10 -> "T"; else -> rank.toString() }) +
        when (suit) { 'c' -> "♣"; 'd' -> "♦"; 'h' -> "♥"; else -> "♠" }
    companion object {
        val deck = (2..14).flatMap { r -> "cdhs".map { s -> PlayingCard(r, s) } }
    }
}
data class HandValue(val category: Int, val kickers: List<Int>) : Comparable<HandValue> {
    override fun compareTo(other: HandValue): Int {
        if (category != other.category) return category.compareTo(other.category)
        for (i in 0 until max(kickers.size, other.kickers.size)) {
            val a = kickers.getOrElse(i) { 0 }; val b = other.kickers.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }
    val name get() = listOf("High card", "One pair", "Two pair", "Three of a kind", "Straight", "Flush", "Full house", "Four of a kind", "Straight flush")[category]
}
data class HandAnalysis(val equity: Double, val potOdds: Double, val expectedValue: Double, val outs: Int, val handName: String?, val iterations: Int)

object PokerEngine {
    fun evaluateFive(cards: List<PlayingCard>): HandValue {
        require(cards.size == 5 && cards.distinct().size == 5)
        val ranks = cards.map { it.rank }.sortedDescending()
        val groups = ranks.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
        val flush = cards.all { it.suit == cards.first().suit }
        val unique = ranks.toSet()
        val straight = (14 downTo 5).firstOrNull { hi -> (hi - 4..hi).all { it in unique } }
            ?: if (listOf(14, 2, 3, 4, 5).all { it in unique }) 5 else null
        if (flush && straight != null) return HandValue(8, listOf(straight))
        if (groups[0].value == 4) return HandValue(7, listOf(groups[0].key, groups.first { it.value == 1 }.key))
        if (groups[0].value == 3 && groups[1].value == 2) return HandValue(6, listOf(groups[0].key, groups[1].key))
        if (flush) return HandValue(5, ranks)
        if (straight != null) return HandValue(4, listOf(straight))
        if (groups[0].value == 3) return HandValue(3, listOf(groups[0].key) + groups.filter { it.value == 1 }.map { it.key }.sortedDescending())
        val pairs = groups.filter { it.value == 2 }.map { it.key }.sortedDescending()
        if (pairs.size == 2) return HandValue(2, pairs + groups.first { it.value == 1 }.key)
        if (pairs.size == 1) return HandValue(1, listOf(pairs[0]) + groups.filter { it.value == 1 }.map { it.key }.sortedDescending())
        return HandValue(0, ranks)
    }

    fun bestHand(cards: List<PlayingCard>): HandValue {
        require(cards.size in 5..7 && cards.distinct().size == cards.size)
        var best: HandValue? = null
        fun choose(start: Int, selected: MutableList<PlayingCard>) {
            if (selected.size == 5) {
                val v = evaluateFive(selected)
                if (best == null || v > best!!) best = v
                return
            }
            for (i in start..cards.size - (5 - selected.size)) {
                selected.add(cards[i]); choose(i + 1, selected); selected.removeAt(selected.lastIndex)
            }
        }
        choose(0, mutableListOf())
        return requireNotNull(best)
    }

    fun analyze(hero: List<PlayingCard>, board: List<PlayingCard>, pot: Double, callAmount: Double, iterations: Int = 1200, randomSeed: Long? = null): HandAnalysis {
        require(hero.size == 2 && board.size <= 5)
        require((hero + board).distinct().size == hero.size + board.size)
        require(pot >= 0 && callAmount >= 0 && iterations > 0)
        val odds = if (pot + callAmount > 0) callAmount / (pot + callAmount) else 0.0
        val deck = PlayingCard.deck.filterNot { it in hero || it in board }
        val random = randomSeed?.let { kotlin.random.Random(it.toInt()) } ?: kotlin.random.Random.Default
        var wins = 0; var ties = 0
        repeat(iterations) {
            val shuffled = deck.shuffled(random)
            val opponent = shuffled.take(2)
            val finalBoard = board + shuffled.drop(2).take(5 - board.size)
            val hv = bestHand(hero + finalBoard)
            val ov = bestHand(opponent + finalBoard)
            when { hv > ov -> wins++; hv == ov -> ties++ }
        }
        val equity = (wins + ties * 0.5) / iterations.toDouble()
        val outs = if (board.size in 3..4) {
            val current = bestHand(hero + board)
            val known = (hero + board).toSet()
            PlayingCard.deck.filterNot { it in known }.count { bestHand(hero + board + it) > current }
        } else 0
        return HandAnalysis(equity, odds, equity * (pot + callAmount) - callAmount, outs,
            if (board.size >= 3) bestHand(hero + board).name else null, iterations)
    }
}
