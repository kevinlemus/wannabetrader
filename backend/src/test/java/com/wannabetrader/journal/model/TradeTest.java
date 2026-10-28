package com.wannabetrader.journal.model;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a Trade saves to the database and comes back exactly the same -
 * including fractional shares and exact money. Uses a throwaway in-memory database.
 */
@DataJpaTest
class TradeTest {

    @Autowired
    TestEntityManager db;

    @Test
    void holdTimeIsFromFirstFillToFlatAndEmptyWhileOpen() {
        Trade trade = new Trade(1L, "6943", "YMAT", Trade.Side.LONG, Instant.parse("2026-10-23T14:30:00Z"));
        assertNull(trade.getHoldTime(), "still open - no hold time yet");

        trade.setClosedAt(Instant.parse("2026-10-23T14:34:30Z"));

        assertEquals(java.time.Duration.ofSeconds(270), trade.getHoldTime());     // 4m 30s
    }

    @Test
    void newTradeStartsOpenWithZeroPnl() {
        Trade trade = new Trade(1L, "6943", "YMAT", Trade.Side.LONG, Instant.parse("2026-10-23T14:30:00Z"));

        assertEquals(Trade.Status.OPEN, trade.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(trade.getPnl()));
        assertNull(trade.getClosedAt());
    }

    @Test
    void closedTradeSavesAndLoadsBackExactly() {
        Trade trade = new Trade(1L, "6943", "YMAT", Trade.Side.LONG, Instant.parse("2026-10-23T14:30:00Z"));
        trade.setQuantity(new BigDecimal("100.9493"));
        trade.setAvgEntryPrice(new BigDecimal("1.985"));
        trade.setAvgExitPrice(new BigDecimal("2.015"));
        trade.setFees(new BigDecimal("0.01"));
        trade.setPnl(new BigDecimal("3.018479"));
        trade.setStatus(Trade.Status.CLOSED);
        trade.setClosedAt(Instant.parse("2026-10-23T19:44:52Z"));
        trade.setSetup("gap and go");
        trade.setNotes("Waited for the pullback. Good entry.");

        Long id = db.persistAndFlush(trade).getId();
        db.clear();
        Trade loaded = db.find(Trade.class, id);

        assertNotNull(id);
        assertEquals("YMAT", loaded.getSymbol());
        assertEquals(Trade.Side.LONG, loaded.getSide());
        assertEquals(Trade.Status.CLOSED, loaded.getStatus());
        assertEquals(0, new BigDecimal("100.9493").compareTo(loaded.getQuantity()));
        assertEquals(0, new BigDecimal("3.018479").compareTo(loaded.getPnl()));
        assertEquals(Instant.parse("2026-10-23T19:44:52Z"), loaded.getClosedAt());
        assertEquals("gap and go", loaded.getSetup());
        assertEquals("Waited for the pullback. Good entry.", loaded.getNotes());
    }

    @Test
    void eachTradeGetsItsOwnId() {
        Long first = db.persistAndFlush(new Trade(1L, "6943", "YMAT", Trade.Side.LONG, Instant.now())).getId();
        Long second = db.persistAndFlush(new Trade(1L, "6943", "ABCD", Trade.Side.SHORT, Instant.now())).getId();

        assertNotEquals(first, second);
    }
}
