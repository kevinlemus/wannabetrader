package com.wannabetrader.journal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One execution from Schwab: "bought 50 YMAT at $1.98 at 10:31".
 *
 * Fills are the raw material - the service groups them into Trades.
 * One order can fill in several pieces, so one trade usually has several fills.
 *
 * Saved in the database table "fill" so the same fill is never imported twice:
 * Schwab gives every fill a unique activityId, and the database refuses a duplicate.
 */
@Entity                         // Hibernate: "this class is a database table"
@Table(name = "fill", indexes = {
        @Index(name = "idx_fill_trade", columnList = "trade_id"),
        @Index(name = "idx_fill_user_account_time", columnList = "userId, account, executedAt")
})
public class Fill {

    /** BUY = shares came in. SELL = shares went out. */
    public enum Action { BUY, SELL }

    /** Money and shares: up to 19 digits, 6 after the dot - same as Trade. */
    private static final int PRECISION = 19;
    private static final int SCALE = 6;

    @Id                         // Hibernate: "this is the row's id"
    @GeneratedValue(strategy = GenerationType.IDENTITY)    // the database picks the next number
    private Long id;

    /** Schwab's own id for this fill. unique = the database refuses the same fill twice. */
    @Column(nullable = false, unique = true)
    private Long activityId;

    /** Schwab's order id. Several fills can share one order (partial fills). */
    private Long orderId;

    /** Whose fill. Always 1 for now (just you) - ready for more users later. */
    @Column(nullable = false)
    private Long userId;

    /** Last 4 digits of the Schwab account, e.g. "6943". */
    @Column(nullable = false, length = 4)
    private String account;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Enumerated(EnumType.STRING)        // saved as the word "BUY"/"SELL", not a number
    @Column(nullable = false, length = 4)
    private Action action;

    /** Number of shares. Always positive - action says which way. Can be fractional (0.9493). */
    @Column(nullable = false, precision = PRECISION, scale = SCALE)
    private BigDecimal quantity;

    /** Price per share. */
    @Column(nullable = false, precision = PRECISION, scale = SCALE)
    private BigDecimal price;

    /**
     * What the shares actually cost (buy) or brought in (sell), BEFORE fees - exactly as
     * Schwab charged it, rounded to the cent Schwab's way. Positive number.
     * Used for P/L instead of shares x price, so your P/L matches your account to the cent.
     */
    @Column(nullable = false, precision = PRECISION, scale = SCALE)
    private BigDecimal dollars;

    /** All fees on this fill added up (commission, SEC fee, TAF...). Positive number. */
    @Column(nullable = false, precision = PRECISION, scale = SCALE)
    private BigDecimal fees = BigDecimal.ZERO;

    /** When it executed. */
    @Column(nullable = false)
    private Instant executedAt;

    /**
     * The trade this fill belongs to. null until the service groups it.
     * Many fills -> one trade. LAZY = only load the trade from the database when it's asked for.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trade_id")
    private Trade trade;

    /** Hibernate needs an empty constructor to build the object when reading a row. */
    protected Fill() {
    }

    /** A fill with Schwab's exact dollar amount - this is what the import uses. */
    public Fill(Long activityId, Long orderId, Long userId, String account, String symbol, Action action,
                BigDecimal quantity, BigDecimal price, BigDecimal dollars, BigDecimal fees, Instant executedAt) {
        this.activityId = activityId;
        this.orderId = orderId;
        this.userId = userId;
        this.account = account;
        this.symbol = symbol;
        this.action = action;
        this.quantity = quantity;
        this.price = price;
        this.dollars = dollars;
        this.fees = fees;
        this.executedAt = executedAt;
    }

    /** A fill where dollars = shares x price (no rounding). Handy in tests. */
    public Fill(Long activityId, Long orderId, Long userId, String account, String symbol, Action action,
                BigDecimal quantity, BigDecimal price, BigDecimal fees, Instant executedAt) {
        this(activityId, orderId, userId, account, symbol, action, quantity, price,
                quantity.multiply(price), fees, executedAt);
    }

    // ---- Getters: read a value ----

    public Long getId() { return id; }
    public Long getActivityId() { return activityId; }
    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public String getAccount() { return account; }
    public String getSymbol() { return symbol; }
    public Action getAction() { return action; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getDollars() { return dollars; }
    public BigDecimal getFees() { return fees; }
    public Instant getExecutedAt() { return executedAt; }
    public Trade getTrade() { return trade; }

    // ---- Setter: the only thing that changes after import is which trade it belongs to ----

    public void setTrade(Trade trade) { this.trade = trade; }

    @Override
    public String toString() {
        return "Fill[" + action + " " + quantity + " " + symbol + " @ " + price + ", " + executedAt + "]";
    }
}
