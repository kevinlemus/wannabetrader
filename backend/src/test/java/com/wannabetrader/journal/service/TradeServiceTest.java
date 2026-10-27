package com.wannabetrader.journal.service;

import com.wannabetrader.core.account.model.SchwabAccount;
import com.wannabetrader.core.account.service.AccountService;
import com.wannabetrader.core.auth.service.SchwabAuthService;
import com.wannabetrader.journal.dao.SchwabTransactionDao;
import com.wannabetrader.journal.dao.TradeDao;
import com.wannabetrader.journal.model.Fill;
import com.wannabetrader.journal.model.ShareAdjustment;
import com.wannabetrader.journal.model.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Checks the journal's brain: grouping fills into trades, P/L, review flags, and periods.
 * Real database (throwaway, in memory) + fake Schwab.
 */
@DataJpaTest
@Import({TradeService.class, TradeDao.class})
class TradeServiceTest {

    private static final SchwabAccount ACCOUNT = new SchwabAccount("12346943", "HASH-A");
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-25T00:00:00Z");

    @Autowired
    TradeService service;

    @Autowired
    TradeDao tradeDao;

    @MockitoBean
    SchwabAuthService authService;

    @MockitoBean
    AccountService accountService;

    @MockitoBean
    SchwabTransactionDao schwabTransactionDao;

    /** What fake Schwab will send on the next import. */
    private final List<Fill> schwabFills = new ArrayList<>();
    private final List<ShareAdjustment> schwabShareChanges = new ArrayList<>();
    private long nextActivityId = 1;

    @BeforeEach
    void setUp() {
        when(authService.accessToken()).thenReturn("pass");
        when(accountService.getDefaultAccount()).thenReturn(Optional.of("6943"));
        when(accountService.selectAccount("6943")).thenReturn(ACCOUNT);
        when(accountService.getPositions("6943")).thenReturn(Map.of());     // Schwab: you hold nothing
        // fresh copies each time, like a real download
        when(schwabTransactionDao.findFills(any(), any(), any(), any(), any()))
                .thenAnswer(call -> schwabFills.stream().map(TradeServiceTest::copy).toList());
        when(schwabTransactionDao.findShareAdjustments(any(), any(), any(), any(), any()))
                .thenAnswer(call -> schwabShareChanges.stream().map(TradeServiceTest::copy).toList());
    }

    private void schwabHas(String symbol, Fill.Action action, String shares, String price, String fee, String time) {
        schwabFills.add(new Fill(nextActivityId++, 1L, 1L, "6943", symbol, action,
                new BigDecimal(shares), new BigDecimal(price), new BigDecimal(fee), Instant.parse(time)));
    }

    private void schwabShareChange(String symbol, String shares, String time, String description) {
        schwabShareChanges.add(new ShareAdjustment(nextActivityId++, 1L, "6943", symbol,
                new BigDecimal(shares), Instant.parse(time), description));
    }

    private static Fill copy(Fill f) {
        return new Fill(f.getActivityId(), f.getOrderId(), f.getUserId(), f.getAccount(), f.getSymbol(),
                f.getAction(), f.getQuantity(), f.getPrice(), f.getDollars(), f.getFees(), f.getExecutedAt());
    }

    private static ShareAdjustment copy(ShareAdjustment a) {
        return new ShareAdjustment(a.getActivityId(), a.getUserId(), a.getAccount(), a.getSymbol(),
                a.getShares(), a.getExecutedAt(), a.getDescription());
    }

    private List<Trade> allTrades() {
        return service.findTrades(null, TradeService.Period.ALL, LocalDate.now());
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void simpleLongRoundTrip() {
        schwabHas("YMAT", Fill.Action.BUY, "100", "2.00", "0", "2026-10-23T14:30:00Z");
        schwabHas("YMAT", Fill.Action.SELL, "100", "2.10", "0.01", "2026-10-23T14:35:00Z");

        TradeService.ImportResult result = service.importFills(null, FROM, TO);

        assertEquals(new TradeService.ImportResult(2, 1, 0), result);
        Trade trade = allTrades().get(0);
        assertEquals(Trade.Side.LONG, trade.getSide());
        assertEquals(Trade.Status.CLOSED, trade.getStatus());
        assertEquals(Instant.parse("2026-10-23T14:35:00Z"), trade.getClosedAt());
        assertMoney("100", trade.getQuantity());
        assertMoney("0", trade.getOpenQuantity());
        assertMoney("2.00", trade.getAvgEntryPrice());
        assertMoney("2.10", trade.getAvgExitPrice());
        assertMoney("9.99", trade.getPnl());          // 100 x 0.10 - 0.01 fee
        assertFalse(trade.isNeedsReview());
    }

    @Test
    void scalingInAndOutUsesAverageEntry() {
        schwabHas("YMAT", Fill.Action.BUY, "100", "2.00", "0", "2026-10-23T14:30:00Z");
        schwabHas("YMAT", Fill.Action.BUY, "100", "2.20", "0", "2026-10-23T14:31:00Z");   // avg entry 2.10
        schwabHas("YMAT", Fill.Action.SELL, "150", "2.30", "0", "2026-10-23T14:32:00Z");
        schwabHas("YMAT", Fill.Action.SELL, "50", "2.00", "0", "2026-10-23T14:33:00Z");

        service.importFills(null, FROM, TO);

        Trade trade = allTrades().get(0);
        assertEquals(1, allTrades().size(), "all 4 fills are one trade");
        assertMoney("2.10", trade.getAvgEntryPrice());
        assertMoney("2.225", trade.getAvgExitPrice());   // (345 + 100) / 200
        assertMoney("25", trade.getPnl());               // 445 - 420
        assertEquals(4, service.getFills(trade.getId()).size());
    }

    @Test
    void goingFlatStartsANewTradeAndStocksStayApart() {
        schwabHas("YMAT", Fill.Action.BUY, "100", "2.00", "0", "2026-10-23T13:31:00Z");
        schwabHas("ABCD", Fill.Action.BUY, "50", "4.00", "0", "2026-10-23T13:32:00Z");
        schwabHas("YMAT", Fill.Action.SELL, "100", "2.02", "0", "2026-10-23T13:33:00Z");
        schwabHas("YMAT", Fill.Action.BUY, "200", "1.98", "0", "2026-10-23T14:15:00Z");
        schwabHas("ABCD", Fill.Action.SELL, "50", "3.90", "0", "2026-10-23T14:16:00Z");
        schwabHas("YMAT", Fill.Action.SELL, "200", "2.02", "0", "2026-10-23T14:22:00Z");

        service.importFills(null, FROM, TO);

        List<Trade> trades = allTrades();                // newest first
        assertEquals(List.of("YMAT", "ABCD", "YMAT"), trades.stream().map(Trade::getSymbol).toList());
        assertMoney("8", trades.get(0).getPnl());       // 200 x 0.04
        assertMoney("-5", trades.get(1).getPnl());      // 50 x -0.10
        assertMoney("2", trades.get(2).getPnl());       // 100 x 0.02
    }

    @Test
    void shortTrade() {
        schwabHas("XYZ", Fill.Action.SELL, "50", "6.20", "0", "2026-10-22T14:00:00Z");
        schwabHas("XYZ", Fill.Action.BUY, "50", "5.90", "0", "2026-10-22T14:30:00Z");

        service.importFills(null, FROM, TO);

        Trade trade = allTrades().get(0);
        assertEquals(Trade.Side.SHORT, trade.getSide());
        assertEquals(Trade.Status.CLOSED, trade.getStatus());
        assertMoney("15", trade.getPnl());              // 50 x 0.30
    }

    @Test
    void stillHoldingStaysOpenWithPnlOnSoldSharesOnly() {
        schwabHas("YMAT", Fill.Action.BUY, "100", "2.00", "0", "2026-10-23T14:30:00Z");
        schwabHas("YMAT", Fill.Action.SELL, "40", "2.50", "0", "2026-10-23T14:35:00Z");
        when(accountService.getPositions("6943")).thenReturn(Map.of("YMAT", new BigDecimal("60")));

        service.importFills(null, FROM, TO);

        Trade trade = allTrades().get(0);
        assertEquals(Trade.Status.OPEN, trade.getStatus());
        assertNull(trade.getClosedAt());
        assertMoney("60", trade.getOpenQuantity());
        assertMoney("20", trade.getPnl());              // 40 x 0.50
        assertFalse(trade.isNeedsReview(), "Schwab agrees: 60 shares");
    }

    @Test
    void importingAgainSkipsOldFillsKeepsNotesAndClosesTheTrade() {
        schwabHas("YMAT", Fill.Action.BUY, "100", "2.00", "0", "2026-10-23T14:30:00Z");
        when(accountService.getPositions("6943")).thenReturn(Map.of("YMAT", new BigDecimal("100")));
        service.importFills(null, FROM, TO);
        Trade trade = allTrades().get(0);
        service.updateNotes(trade.getId(), "gap and go", "waited for the pullback");

        schwabHas("YMAT", Fill.Action.SELL, "100", "2.10", "0", "2026-10-23T15:00:00Z");   // the next day's import
        when(accountService.getPositions("6943")).thenReturn(Map.of());
        TradeService.ImportResult result = service.importFills(null, FROM, TO);

        assertEquals(new TradeService.ImportResult(1, 0, 0), result, "only the sell is new");
        Trade same = allTrades().get(0);
        assertEquals(1, allTrades().size());
        assertEquals(trade.getId(), same.getId());
        assertEquals(Trade.Status.CLOSED, same.getStatus());
        assertMoney("10", same.getPnl());
        assertEquals("gap and go", same.getSetup());
        assertEquals("waited for the pullback", same.getNotes());
    }

    @Test
    void withoutTheSplitRecordBothHalvesAreFlaggedForReview() {
        // Shares changed but Schwab sent no split record - we can't follow it, so flag it
        schwabHas("228339305", Fill.Action.BUY, "13465", "0.01", "0", "2024-06-01T14:00:00Z");
        schwabHas("CRKN", Fill.Action.SELL, "90", "1.50", "0", "2024-06-26T14:00:00Z");

        TradeService.ImportResult result = service.importFills(null, FROM, TO);

        assertEquals(2, result.tradesNeedingReview(), "Schwab holds nothing, we count 2 open trades");
        for (Trade trade : allTrades()) {
            assertTrue(trade.isNeedsReview());
            assertTrue(trade.getReviewReason().contains("reverse split"));
        }
    }

    // ---- Reverse splits, built from your real history ----

    @Test
    void reverseSplitKeepsOneTradeWithNewTickerAndRightPnl() {
        // Your real CRKN: bought 13,465 (Schwab now lists them as 228339305) -> 1-for-~150 -> sold 90 CRKN
        schwabHas("228339305", Fill.Action.BUY, "13465", "0.0111", "0", "2024-06-01T14:00:00Z");
        schwabShareChange("228339305", "-13465", "2024-06-25T18:17:08Z", "CROWN ELECTROKINETICSXXXREVERSE SPLIT EFF");
        schwabShareChange("CRKN", "90", "2024-06-25T18:17:08Z", "CROWN ELECTROKINETICS CO");
        schwabHas("CRKN", Fill.Action.SELL, "90", "1.50", "0", "2024-06-26T14:00:00Z");

        TradeService.ImportResult result = service.importFills(null, FROM, TO);

        assertEquals(1, allTrades().size(), "one trade, not two broken halves");
        Trade trade = allTrades().get(0);
        assertEquals("CRKN", trade.getSymbol());
        assertEquals(Trade.Status.CLOSED, trade.getStatus());
        assertMoney("90", trade.getQuantity());                // converted to new shares
        assertMoney("1.660683", trade.getAvgEntryPrice());     // $149.4615 / 90 = split-adjusted price
        assertMoney("1.50", trade.getAvgExitPrice());
        assertMoney("-14.4615", trade.getPnl());               // $135 back - $149.4615 paid
        assertFalse(trade.isNeedsReview());
        assertEquals(0, result.tradesNeedingReview());
        assertEquals(2, service.getShareAdjustments(trade.getId()).size());
        assertEquals(2, service.getFills(trade.getId()).size());
    }

    @Test
    void splitThatSchwabUndidAndRedidStillAddsUp() {
        // Your real QNTM: 6,908 FSD -> 107 QNTM, then Schwab undid it and redid it as 106 QNTM
        schwabHas("35954B404", Fill.Action.BUY, "6908", "0.10", "0", "2024-08-01T14:00:00Z");
        schwabShareChange("35954B404", "-6908", "2024-08-15T06:10:15Z", "FSD PHARMA INC XXXREVERSE SPLIT EFF");
        schwabShareChange("QNTM", "107", "2024-08-15T06:10:15Z", "QUANTUM BIOPHARMA LTD F");
        schwabShareChange("QNTM", "-107", "2024-09-06T15:20:55Z", "QUANTUM BIOPHARMA LTD FCLASS B");
        schwabShareChange("35954B404", "6908", "2024-09-06T15:20:55Z", "FSD PHARMA INC XXXREVERSE SPLIT EFF");
        schwabShareChange("QNTM", "106", "2024-09-06T15:21:29Z", "QUANTUM BIOPHARMA LTD FCLASS B");
        schwabShareChange("35954B404", "-6908", "2024-09-06T15:21:29Z", "FSD PHARMA INC XXXREVERSE SPLIT EFF");
        schwabHas("QNTM", Fill.Action.SELL, "106", "6.00", "0", "2024-09-10T14:00:00Z");

        service.importFills(null, FROM, TO);

        assertEquals(1, allTrades().size());
        Trade trade = allTrades().get(0);
        assertEquals("QNTM", trade.getSymbol());
        assertEquals(Trade.Status.CLOSED, trade.getStatus());
        assertMoney("106", trade.getQuantity());
        assertMoney("-54.8", trade.getPnl());                  // $636 back - $690.80 paid
        assertFalse(trade.isNeedsReview());
    }

    @Test
    void sharesRemovedWithNothingBackClosesTheTradeAndFlagsIt() {
        // Your real MNMD: 9 shares removed by the split, no new shares - Schwab paid cash instead
        schwabHas("MNMD", Fill.Action.BUY, "9", "1.00", "0", "2022-08-01T14:00:00Z");
        schwabShareChange("MNMD", "-9", "2022-08-29T09:34:46Z", "Mandatory Reverse Split");

        TradeService.ImportResult result = service.importFills(null, FROM, TO);

        Trade trade = allTrades().get(0);
        assertEquals(Trade.Status.CLOSED, trade.getStatus());
        assertMoney("-9", trade.getPnl());                     // the cash paid isn't known
        assertTrue(trade.isNeedsReview());
        assertTrue(trade.getReviewReason().contains("cash in lieu"));
        assertEquals(1, result.newTrades());
    }

    @Test
    void movingSharesBetweenMarginAndCashChangesNothing() {
        // Your real SOUN: 25 shares moved from cash to margin inside the account -
        // Schwab stamped the two halves ONE SECOND apart
        schwabHas("SOUN", Fill.Action.BUY, "25", "10.00", "0", "2025-10-16T14:00:00Z");
        schwabShareChange("SOUN", "25", "2025-10-17T07:30:45Z", "SOUNDHOUND AI INC CLASS A");
        schwabShareChange("SOUN", "-25", "2025-10-17T07:30:46Z", "SOUNDHOUND AI INC CLASS A");
        when(accountService.getPositions("6943")).thenReturn(Map.of("SOUN", new BigDecimal("25")));

        service.importFills(null, FROM, TO);

        Trade trade = allTrades().get(0);
        assertEquals(Trade.Status.OPEN, trade.getStatus());
        assertMoney("25", trade.getOpenQuantity());
        assertFalse(trade.isNeedsReview());
    }

    @Test
    void sellingSomeBeforeTheSplitConvertsEverything() {
        schwabHas("OLD", Fill.Action.BUY, "1000", "0.10", "0", "2024-01-02T14:00:00Z");    // $100
        schwabHas("OLD", Fill.Action.SELL, "400", "0.12", "0", "2024-01-03T14:00:00Z");    // $48
        schwabShareChange("OLD", "-600", "2024-01-05T07:00:00Z", "OLD CO REVERSE SPLIT EFF");
        schwabShareChange("NEW", "60", "2024-01-05T07:00:00Z", "NEW CO");                   // 1-for-10
        schwabHas("NEW", Fill.Action.SELL, "60", "1.50", "0", "2024-01-08T14:00:00Z");     // $90

        service.importFills(null, FROM, TO);

        Trade trade = allTrades().get(0);
        assertEquals(1, allTrades().size());
        assertEquals(Trade.Status.CLOSED, trade.getStatus());
        assertMoney("100", trade.getQuantity());               // 1,000 old = 100 new
        assertMoney("1.00", trade.getAvgEntryPrice());         // $0.10 old = $1.00 new
        assertMoney("38", trade.getPnl());                     // $48 + $90 - $100
    }

    @Test
    void oldSplitThatTouchedNothingIsNeverAppliedToALaterTrade() {
        // Your exact real QNTM history: sold all 107 BEFORE Schwab corrected the split to 106,
        // then bought 1 share months later. The correction must not move that later trade.
        schwabHas("35954B404", Fill.Action.BUY, "6908", "0.2613", "0", "2024-08-09T18:07:37Z");
        schwabShareChange("35954B404", "-6908", "2024-08-15T06:10:15Z", "FSD PHARMA INC XXXREVERSE SPLIT EFF");
        schwabShareChange("QNTM", "107", "2024-08-15T06:10:15Z", "QUANTUM BIOPHARMA LTD F");
        schwabHas("QNTM", Fill.Action.SELL, "100", "4.2804", "0", "2024-08-15T15:04:25Z");
        schwabHas("QNTM", Fill.Action.SELL, "7", "4.29", "0", "2024-08-15T15:04:25Z");
        schwabShareChange("QNTM", "-107", "2024-09-06T15:20:55Z", "QUANTUM BIOPHARMA LTD FCLASS B");
        schwabShareChange("35954B404", "6908", "2024-09-06T15:20:55Z", "FSD PHARMA INC XXXREVERSE SPLIT EFF");
        schwabShareChange("QNTM", "106", "2024-09-06T15:21:29Z", "QUANTUM BIOPHARMA LTD FCLASS B");
        schwabShareChange("35954B404", "-6908", "2024-09-06T15:21:29Z", "FSD PHARMA INC XXXREVERSE SPLIT EFF");
        service.importFills(null, FROM, TO);

        schwabHas("QNTM", Fill.Action.BUY, "1", "3.80", "0", "2025-01-08T14:30:10Z");      // the next import
        service.importFills(null, FROM, TO);
        service.importFills(null, FROM, TO);                                                // and once more

        List<Trade> trades = allTrades();                                                   // newest first
        assertEquals(2, trades.size());

        Trade later = trades.get(0);
        assertEquals("QNTM", later.getSymbol(), "the old correction must not rename it");
        assertTrue(service.getShareAdjustments(later.getId()).isEmpty());
        assertTrue(later.isNeedsReview(), "Schwab holds 0, we count 1 - you should look at it");

        Trade first = trades.get(1);
        assertEquals("QNTM", first.getSymbol());
        assertEquals(Trade.Status.CLOSED, first.getStatus());
        assertMoney("-1346.9904", first.getPnl());          // $458.07 back - $1,805.0604 paid (no fees here)
    }

    @Test
    void splitOnAStockWithNoOpenTradeIsIgnored() {
        schwabShareChange("OLD", "-600", "2024-01-05T07:00:00Z", "OLD CO REVERSE SPLIT EFF");
        schwabShareChange("NEW", "60", "2024-01-05T07:00:00Z", "NEW CO");

        TradeService.ImportResult result = service.importFills(null, FROM, TO);

        assertEquals(0, result.newTrades());
        assertTrue(allTrades().isEmpty());
    }

    @Test
    void periodsFollowNewYorkTime() {
        // 11:30 PM in New York on Oct 23 = 03:30 on Oct 24 in UTC. It's an Oct 23 trade.
        schwabHas("LATE", Fill.Action.BUY, "1", "1", "0", "2026-10-24T03:30:00Z");
        schwabHas("LATE", Fill.Action.SELL, "1", "1", "0", "2026-10-24T03:31:00Z");
        schwabHas("NEXT", Fill.Action.BUY, "1", "1", "0", "2026-10-24T14:00:00Z");
        schwabHas("NEXT", Fill.Action.SELL, "1", "1", "0", "2026-10-24T14:01:00Z");
        service.importFills(null, FROM, TO);

        assertEquals(List.of("LATE"), symbols(TradeService.Period.DAY, "2026-10-23"));
        assertEquals(List.of("NEXT"), symbols(TradeService.Period.DAY, "2026-10-24"));
        assertEquals(List.of("NEXT", "LATE"), symbols(TradeService.Period.WEEK, "2026-10-22"));  // Mon 20 - Sun 26
        assertEquals(List.of("NEXT", "LATE"), symbols(TradeService.Period.MONTH, "2026-10-01"));
        assertEquals(List.of(), symbols(TradeService.Period.MONTH, "2026-09-15"));
    }

    private List<String> symbols(TradeService.Period period, String day) {
        return service.findTrades(null, period, LocalDate.parse(day)).stream().map(Trade::getSymbol).toList();
    }

    @Test
    void longRangesAreAskedOneYearAtATime() {
        service.importFills(null, Instant.parse("2021-06-01T00:00:00Z"), Instant.parse("2026-10-25T00:00:00Z"));

        // 5 years + 5 months = 6 requests, none longer than 365 days
        verify(schwabTransactionDao, times(6)).findFills(eq("pass"), eq(ACCOUNT), eq(1L), any(), any());
    }

    @Test
    void unknownTradeGivesClearError() {
        var error = assertThrows(IllegalArgumentException.class, () -> service.getFills(999L));
        assertEquals("No trade with id 999", error.getMessage());
    }
}
