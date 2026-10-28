package com.wannabetrader.journal.dao;

import com.wannabetrader.journal.model.Fill;
import com.wannabetrader.journal.model.ShareAdjustment;
import com.wannabetrader.journal.model.Trade;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Saves and loads your journal (tables "trade", "fill" and "share_adjustment") in the database.
 *
 * Like every dao, it only reads and writes - it never decides which fills
 * make a trade or what the P/L is. That's TradeService's job.
 *
 * The queries are written in JPQL: like SQL, but it talks about our classes
 * (Trade, Fill, their fields) instead of tables and columns.
 */
@Repository
public class TradeDao {

    private final EntityManager db;

    /** @param db Hibernate's handle on the database - reads and writes rows for us */
    public TradeDao(EntityManager db) {
        this.db = db;
    }

    // ---- Fills ----

    /**
     * Saves the fills we don't have yet. Fills already in the database
     * (same Schwab activityId) are skipped - so importing the same day twice is harmless.
     *
     * @return only the fills that were new
     */
    @Transactional
    public List<Fill> saveNewFills(List<Fill> fills) {
        if (fills.isEmpty()) {
            return List.of();
        }
        Set<Long> alreadySaved = new HashSet<>(db.createQuery(
                        "select f.activityId from Fill f where f.activityId in :ids", Long.class)
                .setParameter("ids", fills.stream().map(Fill::getActivityId).toList())
                .getResultList());

        List<Fill> newFills = fills.stream()
                .filter(fill -> !alreadySaved.contains(fill.getActivityId()))
                .toList();
        newFills.forEach(db::persist);          // persist = insert a new row
        return newFills;
    }

    /** Fills that don't belong to a trade yet, oldest first - the service groups these. */
    @Transactional(readOnly = true)
    public List<Fill> findFillsNotInATradeYet(Long userId, String account) {
        return db.createQuery("""
                        select f from Fill f
                        where f.userId = :userId and f.account = :account and f.trade is null
                        order by f.executedAt, f.activityId""", Fill.class)
                .setParameter("userId", userId)
                .setParameter("account", account)
                .getResultList();
    }

    /** All fills of one trade, oldest first - what you see when you open a trade. */
    @Transactional(readOnly = true)
    public List<Fill> findFillsOfTrade(Long tradeId) {
        return db.createQuery("""
                        select f from Fill f
                        where f.trade.id = :tradeId
                        order by f.executedAt, f.activityId""", Fill.class)
                .setParameter("tradeId", tradeId)
                .getResultList();
    }

    /**
     * The fills of many trades at once, oldest first - one trip to the database instead of
     * one per trade. Used by the stats.
     */
    @Transactional(readOnly = true)
    public List<Fill> findFillsOfTrades(Collection<Long> tradeIds) {
        if (tradeIds.isEmpty()) {
            return List.of();
        }
        return db.createQuery("""
                        select f from Fill f
                        where f.trade.id in :tradeIds
                        order by f.executedAt, f.activityId""", Fill.class)
                .setParameter("tradeIds", tradeIds)
                .getResultList();
    }

    // ---- Share adjustments (reverse splits etc.) - same idea as fills ----

    /** Saves the share adjustments we don't have yet (same Schwab activityId = skipped). */
    @Transactional
    public List<ShareAdjustment> saveNewShareAdjustments(List<ShareAdjustment> adjustments) {
        if (adjustments.isEmpty()) {
            return List.of();
        }
        Set<Long> alreadySaved = new HashSet<>(db.createQuery(
                        "select a.activityId from ShareAdjustment a where a.activityId in :ids", Long.class)
                .setParameter("ids", adjustments.stream().map(ShareAdjustment::getActivityId).toList())
                .getResultList());

        List<ShareAdjustment> newOnes = adjustments.stream()
                .filter(adjustment -> !alreadySaved.contains(adjustment.getActivityId()))
                .toList();
        newOnes.forEach(db::persist);
        return newOnes;
    }

    /** Share adjustments the service hasn't looked at yet, oldest first. */
    @Transactional(readOnly = true)
    public List<ShareAdjustment> findShareAdjustmentsNotHandledYet(Long userId, String account) {
        return db.createQuery("""
                        select a from ShareAdjustment a
                        where a.userId = :userId and a.account = :account and a.handled = false
                        order by a.executedAt, a.activityId""", ShareAdjustment.class)
                .setParameter("userId", userId)
                .setParameter("account", account)
                .getResultList();
    }

    /** All share adjustments of one trade, oldest first. */
    @Transactional(readOnly = true)
    public List<ShareAdjustment> findShareAdjustmentsOfTrade(Long tradeId) {
        return db.createQuery("""
                        select a from ShareAdjustment a
                        where a.trade.id = :tradeId
                        order by a.executedAt, a.activityId""", ShareAdjustment.class)
                .setParameter("tradeId", tradeId)
                .getResultList();
    }

    // ---- Trades ----

    /**
     * Saves a trade: inserts it if it's new, updates it if it already exists.
     *
     * @return the saved trade (a new trade gets its id here)
     */
    @Transactional
    public Trade saveTrade(Trade trade) {
        if (trade.getId() == null) {
            db.persist(trade);
            return trade;
        }
        return db.merge(trade);
    }

    /** One trade by its id, or empty if there's no such trade. */
    @Transactional(readOnly = true)
    public Optional<Trade> findTrade(Long tradeId) {
        return Optional.ofNullable(db.find(Trade.class, tradeId));
    }

    /**
     * The trade you're still in on this stock, if any.
     * There can only be one: a new trade on the same stock only starts once you're flat.
     */
    @Transactional(readOnly = true)
    public Optional<Trade> findOpenTrade(Long userId, String account, String symbol) {
        return db.createQuery("""
                        select t from Trade t
                        where t.userId = :userId and t.account = :account
                          and t.symbol = :symbol and t.status = :open""", Trade.class)
                .setParameter("userId", userId)
                .setParameter("account", account)
                .setParameter("symbol", symbol)
                .setParameter("open", Trade.Status.OPEN)
                .getResultStream()
                .findFirst();
    }

    /** Every trade you're still in, on any stock - used to double-check against Schwab. */
    @Transactional(readOnly = true)
    public List<Trade> findOpenTrades(Long userId, String account) {
        return db.createQuery("""
                        select t from Trade t
                        where t.userId = :userId and t.account = :account and t.status = :open
                        order by t.openedAt""", Trade.class)
                .setParameter("userId", userId)
                .setParameter("account", account)
                .setParameter("open", Trade.Status.OPEN)
                .getResultList();
    }

    /**
     * Trades opened between two moments, NEWEST first - your journal list.
     * The service picks from/to for "today", "this week", "this month", "this year"...
     *
     * @param from start, included
     * @param to   end, not included
     */
    @Transactional(readOnly = true)
    public List<Trade> findTrades(Long userId, String account, Instant from, Instant to) {
        return db.createQuery("""
                        select t from Trade t
                        where t.userId = :userId and t.account = :account
                          and t.openedAt >= :from and t.openedAt < :to
                        order by t.openedAt desc, t.id desc""", Trade.class)
                .setParameter("userId", userId)
                .setParameter("account", account)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }
}
