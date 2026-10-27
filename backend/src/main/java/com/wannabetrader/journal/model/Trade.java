package com.wannabetrader.journal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One trade in your journal = one ROUND TRIP on one stock.
 *
 * It starts with your first fill (e.g. buy 100 YMAT) and ends when you're flat again
 * (all 100 sold). Every partial buy and sell in between belongs to this same trade.
 *
 * Schwab sends fills, not trades - the service groups the fills into trades.
 * Saved in the database table "trade".
 *
 * Money and share amounts use BigDecimal, not double:
 *   - double can't store 0.1 exactly, so money math slowly drifts (0.1 + 0.2 = 0.30000000000000004)
 *   - your fills include fractional shares (e.g. 0.9493), so shares can't be whole numbers either
 */
@Entity                         // Hibernate: "this class is a database table"
@Table(name = "trade", indexes = {
        @Index(name = "idx_trade_user_account_opened", columnList = "userId, account, openedAt")
})
public class Trade {

    /** LONG = bought first, sold to close. SHORT = sold first, bought to close. */
    public enum Side { LONG, SHORT }

    /** OPEN = you still hold shares. CLOSED = you're flat, P/L is final. */
    public enum Status { OPEN, CLOSED }

    /** Money and shares: up to 19 digits, 6 after the dot (e.g. 2.015000, 0.949300). */
    private static final int PRECISION = 19;
    private static final int SCALE = 6;

    @Id                         // Hibernate: "this is the row's id"
    @GeneratedValue(strategy = GenerationType.IDENTITY)    // the database picks the next number: 1, 2, 3...
    private Long id;

    /** Whose trade. Always 1 for now (just you) - ready for more users later. */
    @Column(nullable = false)
    private Long userId;

    /** Last 4 digits of the Schwab account, e.g. "6943". */
    @Column(nullable = false, length = 4)
    private String account;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Enumerated(EnumType.STRING)        // saved as the word "LONG"/"SHORT", not a number
    @Column(nullable = false, length = 5)
    private Side side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Status status = Status.OPEN;

    /** Time of the first fill. */
    @Column(nullable = false)
    private Instant openedAt;

    /** Time of the fill that made you flat. null while the trade is still open. */
    private Instant closedAt;

    /** Total shares opened (all entry fills added up). */
    @Column(precision = PRECISION, scale = SCALE)
    private BigDecimal quantity = BigDecimal.ZERO;

    /** Shares you still hold in this trade. 0 once it's closed. */
    @Column(precision = PRECISION, scale = SCALE)
    private BigDecimal openQuantity = BigDecimal.ZERO;

    /** Average price of the entry fills. */
    @Column(precision = PRECISION, scale = SCALE)
    private BigDecimal avgEntryPrice;

    /** Average price of the exit fills. null until something is closed. */
    @Column(precision = PRECISION, scale = SCALE)
    private BigDecimal avgExitPrice;

    /** All fees on this trade (commission, SEC fee, TAF...). Shown as a positive number. */
    @Column(precision = PRECISION, scale = SCALE)
    private BigDecimal fees = BigDecimal.ZERO;

    /** Profit or loss in dollars, AFTER fees. Negative = loss. Final once the trade is closed. */
    @Column(precision = PRECISION, scale = SCALE)
    private BigDecimal pnl = BigDecimal.ZERO;

    /** Your setup name, e.g. "gap and go", "micro pullback". Optional. */
    @Column(length = 64)
    private String setup;

    /** Your notes: why you took it, what went right or wrong. Optional, no length limit. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * true = the app isn't sure this trade is right - check it yourself.
     * Example: a reverse split changed your shares outside of a fill, so the numbers don't add up.
     */
    @Column(nullable = false)
    private boolean needsReview = false;

    /** Why it needs review, in plain words. null when it doesn't. */
    @Column(length = 255)
    private String reviewReason;

    /** Hibernate needs an empty constructor to build the object when reading a row. */
    protected Trade() {
    }

    /** A new, open trade - created from its first fill. */
    public Trade(Long userId, String account, String symbol, Side side, Instant openedAt) {
        this.userId = userId;
        this.account = account;
        this.symbol = symbol;
        this.side = side;
        this.openedAt = openedAt;
    }

    // ---- Getters: read a value ----

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getAccount() { return account; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public Status getStatus() { return status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosedAt() { return closedAt; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getOpenQuantity() { return openQuantity; }
    public BigDecimal getAvgEntryPrice() { return avgEntryPrice; }
    public BigDecimal getAvgExitPrice() { return avgExitPrice; }
    public BigDecimal getFees() { return fees; }
    public BigDecimal getPnl() { return pnl; }
    public String getSetup() { return setup; }
    public String getNotes() { return notes; }
    public boolean isNeedsReview() { return needsReview; }
    public String getReviewReason() { return reviewReason; }

    // ---- Setters: change a value (the service fills these in as fills come in) ----

    /** Only changes when a reverse split moves the trade to a new ticker. */
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public void setStatus(Status status) { this.status = status; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public void setOpenQuantity(BigDecimal openQuantity) { this.openQuantity = openQuantity; }
    public void setAvgEntryPrice(BigDecimal avgEntryPrice) { this.avgEntryPrice = avgEntryPrice; }
    public void setAvgExitPrice(BigDecimal avgExitPrice) { this.avgExitPrice = avgExitPrice; }
    public void setFees(BigDecimal fees) { this.fees = fees; }
    public void setPnl(BigDecimal pnl) { this.pnl = pnl; }
    public void setSetup(String setup) { this.setup = setup; }
    public void setNotes(String notes) { this.notes = notes; }

    /** Flags the trade for review with a reason, or clears the flag (reason = null). */
    public void setReview(String reason) {
        this.needsReview = reason != null;
        this.reviewReason = reason;
    }

    @Override
    public String toString() {
        return "Trade[id=" + id + ", " + side + " " + symbol + ", " + status + ", pnl=" + pnl + "]";
    }
}
