package com.wannabetrader.journal.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Your trading DAY by day - consistency, not single trades.
 *
 * A trade counts on the day it CLOSED (New York time) - the day the money actually
 * landed in your account. So an overnight hold counts on the next day.
 *
 * NOT a database table - worked out fresh from your trades every time (StatsService).
 * Money is after fees. null = "can't be worked out" (e.g. average red day with no red days).
 *
 * @param days                    trading days with at least one closed trade
 * @param greenDays               days that ended up
 * @param redDays                 days that ended down
 * @param flatDays                days that ended at exactly $0.00
 * @param greenDayRate            greenDays / days, as a percent
 *
 * @param avgGreenDay             average green day
 * @param avgRedDay               average red day (negative)
 * @param avgTradesPerDay         how many trades you take on a normal day
 *
 * @param bestDay                 your best day
 * @param worstDay                your worst day
 *
 * @param maxGreenStreak          most green days in a row
 * @param maxRedStreak            most red days in a row
 *
 * @param redDaysFromOneTrade     red days where ONE trade lost as much as (or more than) the whole
 *                                day - without that trade the day would have been flat or green.
 *                                High = "one bad trade keeps ruining my days".
 *
 * @param byDay                   every day, oldest first - for the calendar on the dashboard
 */
public record DailyStats(
        int days,
        int greenDays,
        int redDays,
        int flatDays,
        BigDecimal greenDayRate,

        BigDecimal avgGreenDay,
        BigDecimal avgRedDay,
        BigDecimal avgTradesPerDay,

        Day bestDay,
        Day worstDay,

        int maxGreenStreak,
        int maxRedStreak,

        int redDaysFromOneTrade,

        List<Day> byDay
) {

    /**
     * One trading day.
     *
     * @param date       the day (New York)
     * @param trades     trades closed that day
     * @param wins       winners that day
     * @param losses     losers that day
     * @param pnl        the day's total P/L
     * @param worstTrade the day's worst single trade
     */
    public record Day(
            LocalDate date,
            int trades,
            int wins,
            int losses,
            BigDecimal pnl,
            BigDecimal worstTrade
    ) {
    }
}
