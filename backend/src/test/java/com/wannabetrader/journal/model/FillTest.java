package com.wannabetrader.journal.model;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a Fill saves and loads back exactly, links to its Trade,
 * and that the same Schwab fill can never be saved twice. Uses a throwaway in-memory database.
 */
@DataJpaTest
class FillTest {

    @Autowired
    TestEntityManager db;

    private static Fill buy(long activityId) {
        return new Fill(activityId, 1008423445413L, 1L, "6943", "YMAT", Fill.Action.BUY,
                new BigDecimal("0.9493"), new BigDecimal("2.015"), new BigDecimal("0.01"),
                Instant.parse("2026-10-23T19:44:52Z"));
    }

    @Test
    void fillSavesAndLoadsBackExactly() {
        Long id = db.persistAndFlush(buy(134431595712L)).getId();
        db.clear();
        Fill loaded = db.find(Fill.class, id);

        assertEquals(134431595712L, loaded.getActivityId());
        assertEquals(Fill.Action.BUY, loaded.getAction());
        assertEquals(0, new BigDecimal("0.9493").compareTo(loaded.getQuantity()));
        assertEquals(0, new BigDecimal("2.015").compareTo(loaded.getPrice()));
        assertEquals(Instant.parse("2026-10-23T19:44:52Z"), loaded.getExecutedAt());
        assertNull(loaded.getTrade());
    }

    @Test
    void fillLinksToItsTrade() {
        Trade trade = db.persist(new Trade(1L, "6943", "YMAT", Trade.Side.LONG, Instant.now()));
        Fill fill = buy(1L);
        fill.setTrade(trade);
        Long fillId = db.persistAndFlush(fill).getId();
        db.clear();

        assertEquals(trade.getId(), db.find(Fill.class, fillId).getTrade().getId());
    }

    @Test
    void sameSchwabFillCannotBeSavedTwice() {
        db.persistAndFlush(buy(555L));

        assertThrows(Exception.class, () -> db.persistAndFlush(buy(555L)));
    }
}
