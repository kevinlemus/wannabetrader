package com.wannabetrader.journal.service;

import com.wannabetrader.journal.dao.TradeDao;
import com.wannabetrader.journal.model.DailyStats;
import com.wannabetrader.journal.model.Fill;
import com.wannabetrader.journal.model.Trade;
import com.wannabetrader.journal.model.TradeStats;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the stats math and every breakdown, with hand-made trades whose answers are known.
 * Real (throwaway, in-memory) database - TradeService is faked, it isn't needed here.
 * Times are UTC: 13:30 UTC = 9:30 AM New York (October).
 */
@DataJpaTest
@Import({StatsService.class, TradeDao.class})
class StatsServiceTest {

    @Autowired
    StatsService stats;

    @Autowired
    TestEntityManager db;

    @MockitoBean
    TradeService tradeService;

    private long nextActivityId = 1;

    /** A closed long trade, saved so it has an id. */
    private Trade trade(String symbol, String opened, String closed, String shares, String avgEntry, String pnl) {
        Trade trade = new Trade(1L, "6943", symbol, Trade.Side.LONG, Instant.parse(opened));
        trade.setStatus(Trade.Status.CLOSED);
        trade.setClosedAt(Instant.parse(closed));
        trade.setQuantity(new BigDecimal(shares));
        trade.setAvgEntryPrice(new BigDecimal(avgEntry));
        trade.setPnl(new BigDecimal(pnl));
        trade.setFees(new BigDecimal("0.01"));
        return db.persist(trade);
    }

    private void buy(Trade trade, String shares, String price, String time) {
        Fill fill = new Fill(nextActivityId++, 1L, 1L, "6943", trade.getSymbol(), Fill.Action.BUY,
                new BigDecimal(shares), new BigDecimal(price), BigDecimal.ZERO, Instant.parse(time));
        fill.setTrade(trade);
        db.persist(fill);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    // ---- The main numbers ----

    @Test
    void summarizeWorksOutEveryNumber() {
        // In the order they closed: +10, -5, +20, -15, 0
        List<Trade> trades = List.of(
                trade("A", "2026-10-20T13:30:00Z", "2026-10-20T13:32:00Z", "100", "2", "10"),     // win, held 2m
                trade("B", "2026-10-20T13:40:00Z", "2026-10-20T13:50:00Z", "100", "2", "-5"),     // loss, held 10m
                trade("C", "2026-10-20T14:00:00Z", "2026-10-20T14:04:00Z", "100", "2", "20"),     // win, held 4m
                trade("D", "2026-10-20T14:10:00Z", "2026-10-20T14:40:00Z", "100", "2", "-15"),    // loss, held 30m
                trade("E", "2026-10-20T15:00:00Z", "2026-10-20T15:01:00Z", "100", "2", "0.004")); // breakeven

        TradeStats s = stats.summarize(trades);

        assertEquals(5, s.trades());
        assertEquals(2, s.wins());
        assertEquals(2, s.losses());
        assertEquals(1, s.breakeven(), "under half a cent counts as breakeven");
        assertMoney("40.0", s.winRate());
        assertMoney("10.00", s.totalPnl());
        assertMoney("30.00", s.grossProfit());
        assertMoney("-20.00", s.grossLoss());
        assertMoney("0.05", s.totalFees());
        assertMoney("15.00", s.avgWin());
        assertMoney("-10.00", s.avgLoss());
        assertMoney("2.00", s.expectancy());            // $10 over 5 trades
        assertMoney("1.50", s.profitFactor());          // 30 / 20
        assertMoney("1.50", s.winLossRatio());          // 15 / 10
        assertMoney("20.00", s.largestWin());
        assertMoney("-15.00", s.largestLoss());
        assertEquals(Duration.ofMinutes(3), s.avgHoldWinners());   // (2m + 4m) / 2
        assertEquals(Duration.ofMinutes(20), s.avgHoldLosers());   // (10m + 30m) / 2
        assertEquals(1, s.maxWinStreak());
        assertEquals(1, s.maxLossStreak());
        assertMoney("-15.00", s.maxDrawdown());         // running 10, 5, 25, 10 -> fell 15 from the 25 peak
    }

    @Test
    void streaksCountInARow() {
        List<Trade> trades = List.of(
                trade("A", "2026-10-20T13:30:00Z", "2026-10-20T13:31:00Z", "1", "1", "-1"),
                trade("B", "2026-10-20T13:32:00Z", "2026-10-20T13:33:00Z", "1", "1", "-1"),
                trade("C", "2026-10-20T13:34:00Z", "2026-10-20T13:35:00Z", "1", "1", "-1"),
                trade("D", "2026-10-20T13:36:00Z", "2026-10-20T13:37:00Z", "1", "1", "2"),
                trade("E", "2026-10-20T13:38:00Z", "2026-10-20T13:39:00Z", "1", "1", "2"));

        TradeStats s = stats.summarize(trades);

        assertEquals(3, s.maxLossStreak());
        assertEquals(2, s.maxWinStreak());
        assertMoney("-3.00", s.maxDrawdown());          // started at 0, fell to -3
    }

    @Test
    void noTradesGivesZerosAndBlanksNotErrors() {
        TradeStats s = stats.summarize(List.of());

        assertEquals(0, s.trades());
        assertNull(s.winRate());
        assertNull(s.avgWin());
        assertNull(s.profitFactor());
        assertMoney("0", s.totalPnl());
    }

    @Test
    void openTradesAreLeftOut() {
        Trade open = new Trade(1L, "6943", "OPEN", Trade.Side.LONG, Instant.parse("2026-10-20T13:30:00Z"));
        Trade closed = trade("A", "2026-10-20T13:30:00Z", "2026-10-20T13:32:00Z", "100", "2", "10");

        assertEquals(1, stats.summarize(List.of(open, closed)).trades());
    }

    // ---- Breakdowns ----

    @Test
    void byTimeOfDayUsesNewYorkTimeInOrder() {
        List<Trade> trades = List.of(
                trade("LATE", "2026-10-20T15:30:00Z", "2026-10-20T15:31:00Z", "1", "1", "-1"),   // 11:30 NY
                trade("EARLY", "2026-10-20T13:45:00Z", "2026-10-20T13:46:00Z", "1", "1", "3"),  // 9:45 NY
                trade("PRE", "2026-10-20T12:00:00Z", "2026-10-20T12:01:00Z", "1", "1", "1"));   // 8:00 NY

        Map<String, TradeStats> groups = stats.breakdown(trades, StatsService.Breakdown.TIME_OF_DAY);

        assertEquals(List.of("Premarket (before 9:30)", "9:30-10:00", "11:00-12:00"), List.copyOf(groups.keySet()));
        assertMoney("3.00", groups.get("9:30-10:00").totalPnl());
    }

    @Test
    void byHoldTimeIncludingOvernight() {
        List<Trade> trades = List.of(
                trade("A", "2026-10-20T13:30:00Z", "2026-10-20T13:30:40Z", "1", "1", "1"),     // 40 s
                trade("B", "2026-10-20T13:30:00Z", "2026-10-20T13:37:00Z", "1", "1", "1"),     // 7 min
                trade("C", "2026-10-20T19:00:00Z", "2026-10-21T14:00:00Z", "1", "1", "1"));    // next day

        assertEquals(List.of("Under 1 min", "5-15 min", "Held overnight"),
                List.copyOf(stats.breakdown(trades, StatsService.Breakdown.HOLD_TIME).keySet()));
    }

    @Test
    void byPriceUsesAverageEntry() {
        List<Trade> trades = List.of(
                trade("A", "2026-10-20T13:30:00Z", "2026-10-20T13:31:00Z", "1", "0.50", "1"),
                trade("B", "2026-10-20T13:32:00Z", "2026-10-20T13:33:00Z", "1", "3.10", "1"),
                trade("C", "2026-10-20T13:34:00Z", "2026-10-20T13:35:00Z", "1", "12.00", "1"));

        assertEquals(List.of("Under $1", "$2-$5", "$10-$20"),
                List.copyOf(stats.breakdown(trades, StatsService.Breakdown.PRICE).keySet()));
    }

    @Test
    void behaviourBreakdownsLookAtTheRestOfTheDay() {
        // One day, in order:
        //   T1 YMAT  9:31-9:33  LOSS  $200 in
        //   T2 YMAT  9:35-9:40  win   $400 in   <- 2 min after a loss, 2nd try on YMAT, bigger size
        //   T3 ABCD 10:00-10:05 win   $100 in   <- after a win, 1st try on ABCD
        //   T4 YMAT 10:30-10:31 loss  $100 in   <- after a win, 3rd try on YMAT
        //   Next day: T5 YMAT - a fresh start
        trade("YMAT", "2026-10-20T13:31:00Z", "2026-10-20T13:33:00Z", "100", "2", "-5");
        trade("YMAT", "2026-10-20T13:35:00Z", "2026-10-20T13:40:00Z", "200", "2", "8");
        trade("ABCD", "2026-10-20T14:00:00Z", "2026-10-20T14:05:00Z", "50", "2", "3");
        trade("YMAT", "2026-10-20T14:30:00Z", "2026-10-20T14:31:00Z", "50", "2", "-2");
        trade("YMAT", "2026-10-21T13:31:00Z", "2026-10-21T13:33:00Z", "100", "2", "1");
        db.flush();
        List<Trade> all = db.getEntityManager().createQuery("select t from Trade t", Trade.class).getResultList();

        Map<String, TradeStats> number = stats.breakdown(all, StatsService.Breakdown.TRADE_NUMBER_OF_DAY);
        assertEquals(2, number.get("1st trade").trades(), "the 1st trade of each day");
        assertEquals(1, number.get("4th-5th trade").trades());

        Map<String, TradeStats> afterLoss = stats.breakdown(all, StatsService.Breakdown.AFTER_A_LOSS);
        assertEquals(2, afterLoss.get("First trade of the day").trades());
        assertEquals(1, afterLoss.get("Within 5 min of a loss").trades());
        assertMoney("8.00", afterLoss.get("Within 5 min of a loss").totalPnl());
        assertEquals(2, afterLoss.get("After a win").trades());

        Map<String, TradeStats> tries = stats.breakdown(all, StatsService.Breakdown.ATTEMPT_ON_TICKER);
        assertEquals(3, tries.get("1st try").trades(), "YMAT day 1, ABCD, YMAT day 2");
        assertEquals(1, tries.get("2nd try").trades());
        assertEquals(1, tries.get("3rd try").trades());

        Map<String, TradeStats> size = stats.breakdown(all, StatsService.Breakdown.SIZE_AFTER_A_LOSS);
        assertEquals(1, size.get("Bigger size after a loss").trades(), "$400 after the $200 loser");
        assertEquals(4, size.get("Not after a loss").trades());
    }

    @Test
    void addedToALoserLooksAtTheFills() {
        Trade averagedDown = trade("AAA", "2026-10-20T13:30:00Z", "2026-10-20T13:40:00Z", "200", "1.95", "-20");
        buy(averagedDown, "100", "2.00", "2026-10-20T13:30:00Z");
        buy(averagedDown, "100", "1.90", "2026-10-20T13:35:00Z");   // below the $2.00 average -> adding to a loser

        Trade addedToWinner = trade("BBB", "2026-10-20T14:00:00Z", "2026-10-20T14:10:00Z", "200", "2.05", "15");
        buy(addedToWinner, "100", "2.00", "2026-10-20T14:00:00Z");
        buy(addedToWinner, "100", "2.10", "2026-10-20T14:05:00Z");  // above the average -> fine
        db.flush();

        Map<String, TradeStats> groups = stats.breakdown(List.of(averagedDown, addedToWinner),
                StatsService.Breakdown.ADDED_TO_LOSER);

        assertMoney("-20.00", groups.get("Added to a loser").totalPnl());
        assertMoney("15.00", groups.get("Didn't add to a loser").totalPnl());
    }

    @Test
    void bySideAndDayOfWeek() {
        Trade shortTrade = new Trade(1L, "6943", "XYZ", Trade.Side.SHORT, Instant.parse("2026-10-23T13:30:00Z"));
        shortTrade.setStatus(Trade.Status.CLOSED);
        shortTrade.setClosedAt(Instant.parse("2026-10-23T13:40:00Z"));
        shortTrade.setPnl(new BigDecimal("15"));
        db.persist(shortTrade);
        Trade longTrade = trade("YMAT", "2026-10-20T13:30:00Z", "2026-10-20T13:31:00Z", "1", "1", "-1");

        assertEquals(List.of("Long", "Short"),
                List.copyOf(stats.breakdown(List.of(shortTrade, longTrade), StatsService.Breakdown.SIDE).keySet()));
        assertEquals(List.of("Tuesday", "Friday"),                  // Oct 20, 2026 = Tuesday, Oct 23 = Friday
                List.copyOf(stats.breakdown(List.of(shortTrade, longTrade), StatsService.Breakdown.DAY_OF_WEEK).keySet()));
    }

    // ---- Day by day ----

    @Test
    void dailyStatsCountEachTradeOnTheDayItClosed() {
        // Tue Oct 20: +10, -5         -> +5   green
        // Wed Oct 21: -30, +10        -> -20  red, and the -30 trade alone lost more than the day
        // Thu Oct 22: -3, -2          -> -5   red, spread out (no single trade caused it)
        // Fri Oct 23: +8, held overnight from Thu - counts on Fri, when it closed
        List<Trade> trades = List.of(
                trade("A", "2026-10-20T13:30:00Z", "2026-10-20T13:35:00Z", "1", "1", "10"),
                trade("B", "2026-10-20T14:00:00Z", "2026-10-20T14:05:00Z", "1", "1", "-5"),
                trade("C", "2026-10-21T13:30:00Z", "2026-10-21T13:35:00Z", "1", "1", "-30"),
                trade("D", "2026-10-21T14:00:00Z", "2026-10-21T14:05:00Z", "1", "1", "10"),
                trade("E", "2026-10-22T13:30:00Z", "2026-10-22T13:35:00Z", "1", "1", "-3"),
                trade("F", "2026-10-22T14:00:00Z", "2026-10-22T14:05:00Z", "1", "1", "-2"),
                trade("G", "2026-10-22T19:30:00Z", "2026-10-23T13:35:00Z", "1", "1", "8"));

        DailyStats d = stats.daily(trades);

        assertEquals(4, d.days());
        assertEquals(2, d.greenDays());
        assertEquals(2, d.redDays());
        assertEquals(0, d.flatDays());
        assertMoney("50.0", d.greenDayRate());
        assertMoney("6.50", d.avgGreenDay());           // (5 + 8) / 2
        assertMoney("-12.50", d.avgRedDay());           // (-20 + -5) / 2
        assertMoney("1.8", d.avgTradesPerDay());        // 7 trades / 4 days
        assertEquals(LocalDate.parse("2026-10-23"), d.bestDay().date());
        assertMoney("8.00", d.bestDay().pnl());
        assertEquals(LocalDate.parse("2026-10-21"), d.worstDay().date());
        assertMoney("-20.00", d.worstDay().pnl());
        assertMoney("-30.00", d.worstDay().worstTrade());
        assertEquals(1, d.maxGreenStreak());
        assertEquals(2, d.maxRedStreak());              // Wed + Thu
        assertEquals(1, d.redDaysFromOneTrade(), "only Wednesday - one trade lost more than the day");
        assertEquals(List.of("2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23"),
                d.byDay().stream().map(day -> day.date().toString()).toList());
        assertEquals(1, d.byDay().get(3).trades(), "Friday only has the overnight trade");
    }

    @Test
    void dailyStatsWithNoTradesGivesZerosAndBlanks() {
        DailyStats d = stats.daily(List.of());

        assertEquals(0, d.days());
        assertNull(d.greenDayRate());
        assertNull(d.bestDay());
        assertTrue(d.byDay().isEmpty());
    }
}
