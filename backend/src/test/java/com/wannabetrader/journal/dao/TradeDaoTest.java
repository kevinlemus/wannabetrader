package com.wannabetrader.journal.dao;

import com.wannabetrader.journal.model.Fill;
import com.wannabetrader.journal.model.Trade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Checks saving and finding fills and trades. Uses a throwaway in-memory database. */
@DataJpaTest
@Import(TradeDao.class)
class TradeDaoTest {

    @Autowired
    TradeDao dao;

    @Autowired
    TestEntityManager db;

    private static Fill fill(long activityId, Fill.Action action, String time) {
        return new Fill(activityId, activityId * 10, 1L, "6943", "YMAT", action,
                new BigDecimal("100"), new BigDecimal("2.00"), BigDecimal.ZERO, Instant.parse(time));
    }

    private static Trade trade(String symbol, String openedAt) {
        return new Trade(1L, "6943", symbol, Trade.Side.LONG, Instant.parse(openedAt));
    }

    /** Push writes to the database and forget memory, so the next read really hits the database. */
    private void flushAndForget() {
        db.flush();
        db.clear();
    }

    @Test
    void savingSameFillsTwiceOnlySavesThemOnce() {
        List<Fill> firstImport = List.of(fill(1, Fill.Action.BUY, "2026-10-23T14:30:00Z"),
                fill(2, Fill.Action.SELL, "2026-10-23T14:35:00Z"));
        assertEquals(2, dao.saveNewFills(firstImport).size());
        flushAndForget();

        List<Fill> secondImport = List.of(fill(1, Fill.Action.BUY, "2026-10-23T14:30:00Z"),
                fill(2, Fill.Action.SELL, "2026-10-23T14:35:00Z"),
                fill(3, Fill.Action.BUY, "2026-10-23T15:00:00Z"));
        List<Fill> saved = dao.saveNewFills(secondImport);

        assertEquals(1, saved.size(), "only fill 3 is new");
        assertEquals(3L, saved.get(0).getActivityId());
    }

    @Test
    void fillsNotInATradeYetComeOldestFirst() {
        Trade trade = dao.saveTrade(trade("YMAT", "2026-10-23T14:00:00Z"));
        Fill grouped = fill(1, Fill.Action.BUY, "2026-10-23T14:00:00Z");
        grouped.setTrade(trade);
        dao.saveNewFills(List.of(grouped,
                fill(3, Fill.Action.SELL, "2026-10-23T16:00:00Z"),
                fill(2, Fill.Action.BUY, "2026-10-23T15:00:00Z")));
        flushAndForget();

        List<Fill> waiting = dao.findFillsNotInATradeYet(1L, "6943");

        assertEquals(List.of(2L, 3L), waiting.stream().map(Fill::getActivityId).toList());
    }

    @Test
    void fillsOfTradeComeOldestFirst() {
        Trade trade = dao.saveTrade(trade("YMAT", "2026-10-23T14:00:00Z"));
        Fill sell = fill(2, Fill.Action.SELL, "2026-10-23T15:00:00Z");
        Fill buy = fill(1, Fill.Action.BUY, "2026-10-23T14:00:00Z");
        sell.setTrade(trade);
        buy.setTrade(trade);
        dao.saveNewFills(List.of(sell, buy));
        flushAndForget();

        assertEquals(List.of(1L, 2L),
                dao.findFillsOfTrade(trade.getId()).stream().map(Fill::getActivityId).toList());
    }

    @Test
    void findsOnlyTheOpenTradeOnThatStock() {
        Trade closed = trade("YMAT", "2026-10-23T14:00:00Z");
        closed.setStatus(Trade.Status.CLOSED);
        dao.saveTrade(closed);
        Trade open = dao.saveTrade(trade("YMAT", "2026-10-23T15:00:00Z"));
        dao.saveTrade(trade("ABCD", "2026-10-23T15:30:00Z"));
        flushAndForget();

        assertEquals(open.getId(), dao.findOpenTrade(1L, "6943", "YMAT").orElseThrow().getId());
        assertTrue(dao.findOpenTrade(1L, "6943", "ZZZZ").isEmpty());
    }

    @Test
    void tradesInRangeComeNewestFirst() {
        dao.saveTrade(trade("OLD", "2026-10-22T14:00:00Z"));       // day before - left out
        dao.saveTrade(trade("AAA", "2026-10-23T14:00:00Z"));
        dao.saveTrade(trade("BBB", "2026-10-23T18:00:00Z"));
        dao.saveTrade(trade("NEXT", "2026-10-24T00:00:00Z"));     // exactly 'to' - left out
        flushAndForget();

        List<Trade> trades = dao.findTrades(1L, "6943",
                Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-24T00:00:00Z"));

        assertEquals(List.of("BBB", "AAA"), trades.stream().map(Trade::getSymbol).toList());
    }

    @Test
    void savingAnExistingTradeUpdatesIt() {
        Trade trade = dao.saveTrade(trade("YMAT", "2026-10-23T14:00:00Z"));
        flushAndForget();

        Trade loaded = dao.findTrade(trade.getId()).orElseThrow();
        loaded.setNotes("chased it");
        dao.saveTrade(loaded);
        flushAndForget();

        assertEquals("chased it", dao.findTrade(trade.getId()).orElseThrow().getNotes());
    }
}
