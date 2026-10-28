package com.wannabetrader.journal.model;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * The numbers that tell you how your trading is going, for any group of CLOSED trades:
 * a day, a week, all of 2024, only your shorts, only trades taken 9:30-10:00...
 *
 * NOT a database table - it's worked out fresh from your trades every time you ask
 * (StatsService does the math), so it can never be out of date.
 *
 * A "record" because it never changes once made - it's just a bundle of results.
 *
 * Money is after fees. null = "can't be worked out" (e.g. average loss when you had no losses).
 *
 * @param trades         how many closed trades
 * @param wins           trades that made money
 * @param losses         trades that lost money
 * @param breakeven      trades that made exactly $0.00
 * @param winRate        wins / trades, as a percent (55.1 = 55.1%)
 *
 * @param totalPnl       all the P/L added up
 * @param grossProfit    all the winners added up (positive)
 * @param grossLoss      all the losers added up (negative)
 * @param totalFees      all the fees you paid
 *
 * @param avgWin         average winner
 * @param avgLoss        average loser (negative)
 * @param expectancy     average P/L per trade = what one more trade is "worth" on average.
 *                       Positive = your trading makes money over time. Negative = it loses.
 * @param profitFactor   grossProfit / |grossLoss|. Above 1 = you make more than you lose.
 *                       1.5+ is good. null if you had no losers.
 * @param winLossRatio   avgWin / |avgLoss|. Above 1 = your winners are bigger than your losers.
 *
 * @param largestWin     best trade
 * @param largestLoss    worst trade (negative)
 *
 * @param avgHoldWinners how long you held winners, on average
 * @param avgHoldLosers  how long you held losers, on average.
 *                       Much longer than winners = "cutting winners, letting losers run".
 *
 * @param maxWinStreak   most winners in a row
 * @param maxLossStreak  most losers in a row
 * @param maxDrawdown    the biggest drop from a high point of your running P/L to a later
 *                       low point (negative). "The worst stretch you had to sit through."
 */
public record TradeStats(
        int trades,
        int wins,
        int losses,
        int breakeven,
        BigDecimal winRate,

        BigDecimal totalPnl,
        BigDecimal grossProfit,
        BigDecimal grossLoss,
        BigDecimal totalFees,

        BigDecimal avgWin,
        BigDecimal avgLoss,
        BigDecimal expectancy,
        BigDecimal profitFactor,
        BigDecimal winLossRatio,

        BigDecimal largestWin,
        BigDecimal largestLoss,

        Duration avgHoldWinners,
        Duration avgHoldLosers,

        int maxWinStreak,
        int maxLossStreak,
        BigDecimal maxDrawdown
) {
}
