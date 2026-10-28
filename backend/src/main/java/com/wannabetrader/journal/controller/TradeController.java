package com.wannabetrader.journal.controller;

import com.wannabetrader.journal.model.Fill;
import com.wannabetrader.journal.model.ShareAdjustment;
import com.wannabetrader.journal.model.Trade;
import com.wannabetrader.journal.service.TradeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Your journal in the browser.
 *
 *   POST /api/journal/import                       - pull your last 7 days from Schwab
 *   POST /api/journal/import?from=2021-01-01       - pull everything since a date (all your history)
 *   GET  /api/journal/trades                       - today's trades, newest first
 *   GET  /api/journal/trades?period=WEEK           - this week   (DAY, WEEK, MONTH, YEAR, ALL)
 *   GET  /api/journal/trades?period=MONTH&day=2026-09-15   - the month of Sep 15
 *   GET  /api/journal/trades/42                    - one trade with all its fills and share changes
 *   PUT  /api/journal/trades/42/notes              - save your setup and notes
 *
 * Every address also takes ?account=6943. Without it, your default account is used.
 *
 * This class has no logic. It only passes things to TradeService, and turns the answers
 * into the shapes at the bottom (TradeView, FillView...) so the browser gets clean data.
 */
@RestController
@RequestMapping("/api/journal")
public class TradeController {

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    /** Pulls your fills and share changes from Schwab and turns them into trades. Safe to repeat. */
    @PostMapping("/import")
    public TradeService.ImportResult importFromSchwab(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(TradeService.MARKET_TIME);
        LocalDate start = from != null ? from : today.minusDays(7);
        LocalDate end = to != null ? to : today;
        return tradeService.importFills(account,
                start.atStartOfDay(TradeService.MARKET_TIME).toInstant(),
                end.plusDays(1).atStartOfDay(TradeService.MARKET_TIME).toInstant());   // "to" day included
    }

    /** Your trades for a day, week, month, year, or all time - newest first. */
    @GetMapping("/trades")
    public List<TradeView> trades(
            @RequestParam(required = false) String account,
            @RequestParam(defaultValue = "DAY") TradeService.Period period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        LocalDate inside = day != null ? day : LocalDate.now(TradeService.MARKET_TIME);
        return tradeService.findTrades(account, period, inside).stream().map(TradeView::of).toList();
    }

    /** One trade, with every fill and share change inside it. */
    @GetMapping("/trades/{id}")
    public TradeDetail trade(@PathVariable Long id) {
        return new TradeDetail(
                TradeView.of(tradeService.getTrade(id)),
                tradeService.getFills(id).stream().map(FillView::of).toList(),
                tradeService.getShareAdjustments(id).stream().map(ShareChangeView::of).toList());
    }

    /** Saves your setup name and notes. Leave one out to keep it as it is. */
    @PutMapping("/trades/{id}/notes")
    public TradeView saveNotes(@PathVariable Long id, @RequestBody NotesRequest body) {
        return TradeView.of(tradeService.updateNotes(id, body.setup(), body.notes()));
    }

    /** Not logged in to Schwab (or the 7 days are up)? Answer "401 Unauthorized" with the plain message. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> notLoggedIn(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
    }

    /** Unknown trade or account, or a bad date? Answer "400 Bad Request" with the plain message. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    // ---- The shapes the browser gets. Only this controller uses them, so they live here. ----

    /**
     * One row of your journal.
     * holdSeconds = how long you were in the trade, in seconds (null while it's open).
     * Seconds, because every screen can show that the way it likes ("4m 30s", "2h 05m").
     */
    record TradeView(Long id, String account, String symbol, Trade.Side side, Trade.Status status,
                     Instant openedAt, Instant closedAt, Long holdSeconds, BigDecimal quantity, BigDecimal openQuantity,
                     BigDecimal avgEntryPrice, BigDecimal avgExitPrice, BigDecimal fees, BigDecimal pnl,
                     String setup, String notes, boolean needsReview, String reviewReason) {

        static TradeView of(Trade t) {
            Long holdSeconds = t.getHoldTime() == null ? null : t.getHoldTime().toSeconds();
            return new TradeView(t.getId(), t.getAccount(), t.getSymbol(), t.getSide(), t.getStatus(),
                    t.getOpenedAt(), t.getClosedAt(), holdSeconds, t.getQuantity(), t.getOpenQuantity(),
                    t.getAvgEntryPrice(), t.getAvgExitPrice(), t.getFees(), t.getPnl(),
                    t.getSetup(), t.getNotes(), t.isNeedsReview(), t.getReviewReason());
        }
    }

    /** One fill inside a trade. */
    record FillView(Long activityId, Long orderId, String symbol, Fill.Action action,
                    BigDecimal quantity, BigDecimal price, BigDecimal dollars, BigDecimal fees, Instant executedAt) {

        static FillView of(Fill f) {
            return new FillView(f.getActivityId(), f.getOrderId(), f.getSymbol(), f.getAction(),
                    f.getQuantity(), f.getPrice(), f.getDollars(), f.getFees(), f.getExecutedAt());
        }
    }

    /** One share change (reverse split etc.) inside a trade. */
    record ShareChangeView(String symbol, BigDecimal shares, Instant executedAt, String description) {

        static ShareChangeView of(ShareAdjustment a) {
            return new ShareChangeView(a.getSymbol(), a.getShares(), a.getExecutedAt(), a.getDescription());
        }
    }

    /** One trade opened up: the row + everything inside it. */
    record TradeDetail(TradeView trade, List<FillView> fills, List<ShareChangeView> shareChanges) {
    }

    /** What the browser sends to save notes: { "setup": "gap and go", "notes": "..." } */
    record NotesRequest(String setup, String notes) {
    }
}
