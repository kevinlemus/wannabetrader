package com.wannabetrader.journal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Shares that changed WITHOUT a fill - mostly reverse splits.
 * Schwab calls these "Receive and Deliver".
 *
 * A reverse split comes as TWO of these, in the same second:
 *   old shares OUT:  228339305  -13,465   ("CROWN ELECTROKINETICS ... REVERSE SPLIT")
 *   new shares IN:   CRKN           +90
 * The service sees the pair and keeps it as ONE trade: new ticker, converted shares,
 * same dollars - so the P/L stays right.
 *
 * Saved in the database table "share_adjustment". Like Fill, each one has Schwab's unique
 * activityId, so it's never imported twice.
 */
@Entity                         // Hibernate: "this class is a database table"
@Table(name = "share_adjustment", indexes = {
        @Index(name = "idx_share_adjustment_trade", columnList = "trade_id"),
        @Index(name = "idx_share_adjustment_user_account_time", columnList = "userId, account, executedAt")
})
public class ShareAdjustment {

    /** Shares: up to 19 digits, 6 after the dot - same as Fill. */
    private static final int PRECISION = 19;
    private static final int SCALE = 6;

    @Id                         // Hibernate: "this is the row's id"
    @GeneratedValue(strategy = GenerationType.IDENTITY)    // the database picks the next number
    private Long id;

    /** Schwab's own id for this record. unique = the database refuses the same one twice. */
    @Column(nullable = false, unique = true)
    private Long activityId;

    /** Whose record. Always 1 for now (just you) - ready for more users later. */
    @Column(nullable = false)
    private Long userId;

    /** Last 4 digits of the Schwab account, e.g. "6943". */
    @Column(nullable = false, length = 4)
    private String account;

    /** The stock (or the old stock's code, like "228339305", after Schwab renamed it). */
    @Column(nullable = false, length = 16)
    private String symbol;

    /** Shares that came in (positive) or went out (negative). */
    @Column(nullable = false, precision = PRECISION, scale = SCALE)
    private BigDecimal shares;

    /** When it happened. Both halves of a split have the same time. */
    @Column(nullable = false)
    private Instant executedAt;

    /** Schwab's description, e.g. "CROWN ELECTROKINETICSXXXREVERSE SPLIT EFF". */
    @Column(length = 255)
    private String description;

    /** The trade it changed. null if it didn't touch any of your trades. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trade_id")
    private Trade trade;

    /**
     * true once the service has looked at it - whether it changed a trade or not.
     * Without this, a split that touched nothing would wait around and could be applied
     * LATER to a newer trade on the same ticker - out of order and wrong.
     */
    @Column(nullable = false)
    private boolean handled = false;

    /** Hibernate needs an empty constructor to build the object when reading a row. */
    protected ShareAdjustment() {
    }

    public ShareAdjustment(Long activityId, Long userId, String account, String symbol,
                           BigDecimal shares, Instant executedAt, String description) {
        this.activityId = activityId;
        this.userId = userId;
        this.account = account;
        this.symbol = symbol;
        this.shares = shares;
        this.executedAt = executedAt;
        this.description = description;
    }

    // ---- Getters: read a value ----

    public Long getId() { return id; }
    public Long getActivityId() { return activityId; }
    public Long getUserId() { return userId; }
    public String getAccount() { return account; }
    public String getSymbol() { return symbol; }
    public BigDecimal getShares() { return shares; }
    public Instant getExecutedAt() { return executedAt; }
    public String getDescription() { return description; }
    public Trade getTrade() { return trade; }
    public boolean isHandled() { return handled; }

    // ---- Setters: after import, only which trade it belongs to and "handled" change ----

    public void setTrade(Trade trade) { this.trade = trade; }
    public void markHandled() { this.handled = true; }

    @Override
    public String toString() {
        return "ShareAdjustment[" + symbol + " " + shares + ", " + executedAt + ", " + description + "]";
    }
}
