package com.wannabetrader.journal.service;

import com.wannabetrader.core.account.model.SchwabAccount;
import com.wannabetrader.core.account.service.AccountService;
import com.wannabetrader.core.auth.service.SchwabAuthService;
import com.wannabetrader.journal.dao.SchwabTransactionDao;
import com.wannabetrader.journal.dao.TradeDao;
import com.wannabetrader.journal.model.Fill;
import com.wannabetrader.journal.model.ShareAdjustment;
import com.wannabetrader.journal.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The brain of the journal.
 *
 *   1. IMPORT  - get your fills AND your share changes (reverse splits) from Schwab,
 *                and save the new ones.
 *   2. GROUP   - walk through them in time order:
 *                  - a fill joins your open trade on that stock, or starts a new trade if you
 *                    were flat. The trade closes the moment you're flat again.
 *                  - a reverse split moves your open trade to the new ticker and converts its
 *                    shares (13,465 old -> 90 new). Same trade, same dollars.
 *   3. CALCULATE - shares, average prices, fees and P/L for every trade that changed.
 *   4. CHECK   - compare the trades we think are still open with what Schwab says you hold.
 *                If they don't match, flag the trade for review instead of showing a wrong P/L.
 *
 * It also answers "show me my trades for this day / week / month / year".
 */
@Service
public class TradeService {

    /** Writes log lines. Never log tokens or full account numbers. */
    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    /** Only one user for now (you). Replaced by the logged-in user once the app has accounts. */
    public static final Long USER_ID = 1L;

    /** Days, weeks and months follow New York time - the market's clock. */
    public static final ZoneId MARKET_TIME = ZoneId.of("America/New_York");

    /** Schwab's rule: one request can cover at most 1 year. */
    private static final Duration MAX_SCHWAB_RANGE = Duration.ofDays(365);

    /** Prices, shares and P/L are saved with 6 decimals - same as the database columns. */
    private static final int SCALE = 6;

    /** Extra decimals while converting shares in a split, so nothing is lost to rounding. */
    private static final int WORKING_SCALE = 12;

    /**
     * Share changes this close together are ONE event. Schwab doesn't always stamp both halves
     * in the same second (your SOUN margin/cash move: +25 at 07:30:45, -25 at 07:30:46).
     * Separate events (your QNTM split correction: 34 seconds apart) stay separate.
     */
    private static final Duration SAME_CHANGE_WITHIN = Duration.ofSeconds(10);

    /** Start of the review reason that step 4 writes - so step 4 only ever clears its own flag. */
    private static final String MISMATCH_REASON = "We count ";

    /** Which time range to show. */
    public enum Period { DAY, WEEK, MONTH, YEAR, ALL }

    /** What an import did - shown to you afterwards. */
    public record ImportResult(int newFills, int newTrades, int tradesNeedingReview) {
    }

    /** Everything that happened inside one trade: its fills and its share changes. */
    private record History(List<Fill> fills, List<ShareAdjustment> adjustments) {
    }

    private final SchwabAuthService authService;
    private final AccountService accountService;
    private final SchwabTransactionDao schwabTransactionDao;
    private final TradeDao tradeDao;

    public TradeService(SchwabAuthService authService,
                        AccountService accountService,
                        SchwabTransactionDao schwabTransactionDao,
                        TradeDao tradeDao) {
        this.authService = authService;
        this.accountService = accountService;
        this.schwabTransactionDao = schwabTransactionDao;
        this.tradeDao = tradeDao;
    }

    // =====================================================================
    //  1-4. Import -> group -> calculate -> check
    // =====================================================================

    /**
     * Gets your history from Schwab between two moments and turns it into trades.
     * Safe to run as often as you like - anything already imported is skipped,
     * and trades you already wrote notes on are only updated, never replaced.
     *
     * "@Transactional" = all of it is one safe step: if anything fails, nothing is half-saved.
     *
     * @param last4 which account, e.g. "6943" - or null for your default account
     */
    @Transactional
    public ImportResult importFills(String last4, Instant from, Instant to) {
        SchwabAccount account = accountService.selectAccount(resolveAccount(last4));
        String pass = authService.accessToken();

        // 1. IMPORT - Schwab allows 1 year per request, so ask year by year
        List<Fill> fills = new ArrayList<>();
        List<ShareAdjustment> adjustments = new ArrayList<>();
        for (Instant start = from; start.isBefore(to); start = start.plus(MAX_SCHWAB_RANGE)) {
            Instant end = start.plus(MAX_SCHWAB_RANGE).isBefore(to) ? start.plus(MAX_SCHWAB_RANGE) : to;
            fills.addAll(schwabTransactionDao.findFills(pass, account, USER_ID, start, end));
            adjustments.addAll(schwabTransactionDao.findShareAdjustments(pass, account, USER_ID, start, end));
        }
        List<Fill> newFills = tradeDao.saveNewFills(fills);
        tradeDao.saveNewShareAdjustments(adjustments);

        // 2 + 3. GROUP and CALCULATE
        int newTrades = groupIntoTrades(account.last4());

        // 4. CHECK against what Schwab says you hold
        int needingReview = checkOpenTradesAgainstSchwab(account.last4());

        log.info("Imported {}: {} new fills, {} new trades, {} trades need review",
                account.maskedNumber(), newFills.size(), newTrades, needingReview);
        return new ImportResult(newFills.size(), newTrades, needingReview);
    }

    /**
     * Step 2: walks through every fill and share change that isn't in a trade yet,
     * OLDEST FIRST, so a split is applied before the sells that come after it.
     *
     * Share changes come in "groups" - records a few seconds apart belong together
     * (see SAME_CHANGE_WITHIN).
     *
     * @return how many new trades were started
     */
    private int groupIntoTrades(String account) {
        List<Fill> fills = tradeDao.findFillsNotInATradeYet(USER_ID, account);
        List<List<ShareAdjustment>> changeGroups =
                groupShareChanges(tradeDao.findShareAdjustmentsNotHandledYet(USER_ID, account));

        Map<Long, History> histories = new HashMap<>();      // each trade's history, loaded once
        int newTrades = 0;
        int nextFill = 0;
        Iterator<List<ShareAdjustment>> changes = changeGroups.iterator();
        List<ShareAdjustment> nextChange = changes.hasNext() ? changes.next() : null;

        while (nextFill < fills.size() || nextChange != null) {
            boolean fillComesFirst = nextChange == null || (nextFill < fills.size()
                    && !fills.get(nextFill).getExecutedAt().isAfter(nextChange.get(0).getExecutedAt()));
            if (fillComesFirst) {
                if (addFill(account, fills.get(nextFill++), histories)) {
                    newTrades++;
                }
            } else {
                applyShareChange(account, nextChange, histories);
                nextChange = changes.hasNext() ? changes.next() : null;
            }
        }
        return newTrades;
    }

    /**
     * A fill joins your open trade on that stock, or starts a new trade if you were flat.
     *
     * @return true if it started a new trade
     */
    private boolean addFill(String account, Fill fill, Map<Long, History> histories) {
        Trade trade = tradeDao.findOpenTrade(USER_ID, account, fill.getSymbol()).orElse(null);
        boolean isNew = trade == null;
        if (isNew) {
            Trade.Side side = fill.getAction() == Fill.Action.BUY ? Trade.Side.LONG : Trade.Side.SHORT;
            trade = tradeDao.saveTrade(new Trade(USER_ID, account, fill.getSymbol(), side, fill.getExecutedAt()));
        }
        History history = historyOf(trade, histories);
        fill.setTrade(trade);
        history.fills().add(fill);
        calculate(trade, history);
        return isNew;
    }

    /**
     * Applies one group of share changes (all from the same second).
     *
     *   One stock DOWN + another stock UP  -> reverse split / ticker change:
     *                                         your open trade moves to the new ticker.
     *   Anything else (e.g. shares removed with nothing back = cash paid instead)
     *                                      -> the shares of that stock's open trade change.
     *   Same stock out and back in (moved between margin and cash) -> nets to 0, nothing happens.
     *   No open trade on the stock (e.g. shares bought before your history) -> nothing happens.
     */
    private void applyShareChange(String account, List<ShareAdjustment> group, Map<Long, History> histories) {
        group.forEach(ShareAdjustment::markHandled);        // looked at once, at its own moment - never again
        Map<String, BigDecimal> netShares = netSharesBySymbol(group);
        List<String> down = netShares.entrySet().stream().filter(e -> e.getValue().signum() < 0).map(Map.Entry::getKey).toList();
        List<String> up = netShares.entrySet().stream().filter(e -> e.getValue().signum() > 0).map(Map.Entry::getKey).toList();

        if (down.size() == 1 && up.size() == 1) {
            String oldSymbol = down.get(0);
            String newSymbol = up.get(0);
            Optional<Trade> open = tradeDao.findOpenTrade(USER_ID, account, oldSymbol);
            if (open.isEmpty()) {
                log.info("Split {} -> {} on {}: no open trade on {}, nothing to change",
                        oldSymbol, newSymbol, group.get(0).getExecutedAt(), oldSymbol);
                return;
            }
            Trade trade = open.get();
            attach(trade, group.stream().filter(a -> a.getSymbol().equals(oldSymbol) || a.getSymbol().equals(newSymbol)).toList(), histories);
            trade.setSymbol(newSymbol);
            log.info("Split {} -> {}: trade {} moved to the new ticker", oldSymbol, newSymbol, trade.getId());
            return;
        }

        for (String symbol : netShares.keySet()) {
            Optional<Trade> open = tradeDao.findOpenTrade(USER_ID, account, symbol);
            if (open.isPresent()) {
                attach(open.get(), group.stream().filter(a -> a.getSymbol().equals(symbol)).toList(), histories);
            } else {
                log.info("Share change on {} at {}: no open trade, nothing to change", symbol, group.get(0).getExecutedAt());
            }
        }
    }

    /** Links share changes to a trade and recalculates it. */
    private void attach(Trade trade, List<ShareAdjustment> adjustments, Map<Long, History> histories) {
        History history = historyOf(trade, histories);
        for (ShareAdjustment adjustment : adjustments) {
            adjustment.setTrade(trade);
            history.adjustments().add(adjustment);
        }
        calculate(trade, history);
    }

    /** The trade's history - loaded from the database the first time, then kept in memory. */
    private History historyOf(Trade trade, Map<Long, History> histories) {
        return histories.computeIfAbsent(trade.getId(), tradeId -> new History(
                new ArrayList<>(tradeDao.findFillsOfTrade(tradeId)),
                new ArrayList<>(tradeDao.findShareAdjustmentsOfTrade(tradeId))));
    }

    /**
     * Splits share changes (oldest first) into events: each change joins the group before it
     * if it's within SAME_CHANGE_WITHIN of that group's last change.
     */
    private static List<List<ShareAdjustment>> groupShareChanges(List<ShareAdjustment> changes) {
        List<ShareAdjustment> sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparing(ShareAdjustment::getExecutedAt).thenComparing(ShareAdjustment::getActivityId));

        List<List<ShareAdjustment>> groups = new ArrayList<>();
        List<ShareAdjustment> current = null;
        for (ShareAdjustment change : sorted) {
            boolean joinsCurrent = current != null && !change.getExecutedAt()
                    .isAfter(current.get(current.size() - 1).getExecutedAt().plus(SAME_CHANGE_WITHIN));
            if (!joinsCurrent) {
                current = new ArrayList<>();
                groups.add(current);
            }
            current.add(change);
        }
        return groups;
    }

    /** Adds up the shares per stock. Stocks that net to 0 are left out. Keeps Schwab's order. */
    private static Map<String, BigDecimal> netSharesBySymbol(List<ShareAdjustment> group) {
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        for (ShareAdjustment adjustment : group) {
            net.merge(adjustment.getSymbol(), adjustment.getShares(), BigDecimal::add);
        }
        net.values().removeIf(shares -> shares.signum() == 0);
        return net;
    }

    /**
     * Step 3: works out every number on a trade from its whole history, oldest first.
     *
     * Entry fills = the ones that build the position (BUY for a long, SELL for a short).
     * Exit fills  = the ones that shrink it.
     *
     * Reverse split: every share count so far is converted to new shares
     * (x 90 / 13,465). Dollars don't change - so the average price becomes the
     * split-adjusted price and the P/L stays right.
     *
     * P/L uses the AVERAGE entry price - the standard way when you scale in:
     *   long : money from exits - what those exited shares cost on average - fees
     *   short: what those shares were sold for on average - money spent covering - fees
     * While the trade is still open, P/L only counts the shares you've already exited.
     */
    private void calculate(Trade trade, History history) {
        Totals totals = new Totals(trade.getSide());

        // Everything in time order: fills one by one, share changes as whole groups
        // (a group sits at the time of its first change)
        TreeMap<Instant, List<Object>> timeline = new TreeMap<>();
        history.fills().forEach(fill -> timeline.computeIfAbsent(fill.getExecutedAt(), t -> new ArrayList<>()).add(fill));
        groupShareChanges(history.adjustments()).forEach(group ->
                timeline.computeIfAbsent(group.get(0).getExecutedAt(), t -> new ArrayList<>()).add(group));

        for (List<Object> moment : timeline.values()) {
            for (Object event : moment) {
                if (event instanceof Fill fill) {
                    totals.add(fill);
                } else {
                    @SuppressWarnings("unchecked")
                    List<ShareAdjustment> group = (List<ShareAdjustment>) event;
                    totals.apply(group);
                }
            }
        }

        BigDecimal openShares = totals.entryShares.subtract(totals.exitShares).setScale(SCALE, RoundingMode.HALF_UP);

        // What the exited shares cost on average: entry dollars x (shares exited / shares entered)
        BigDecimal costOfExited = totals.entryShares.signum() == 0 ? BigDecimal.ZERO
                : totals.entryDollars.multiply(totals.exitShares).divide(totals.entryShares, WORKING_SCALE, RoundingMode.HALF_UP);
        BigDecimal gross = trade.getSide() == Trade.Side.LONG
                ? totals.exitDollars.subtract(costOfExited)
                : costOfExited.subtract(totals.exitDollars);

        trade.setQuantity(totals.entryShares.setScale(SCALE, RoundingMode.HALF_UP));
        trade.setOpenQuantity(openShares.max(BigDecimal.ZERO));
        trade.setAvgEntryPrice(average(totals.entryDollars, totals.entryShares));
        trade.setAvgExitPrice(average(totals.exitDollars, totals.exitShares));
        trade.setFees(totals.fees);
        trade.setPnl(gross.subtract(totals.fees).setScale(SCALE, RoundingMode.HALF_UP));

        if (openShares.signum() <= 0) {
            trade.setStatus(Trade.Status.CLOSED);
            trade.setClosedAt(timeline.lastKey());
        } else {
            trade.setStatus(Trade.Status.OPEN);
            trade.setClosedAt(null);
        }

        String review = totals.review;
        if (openShares.signum() < 0) {
            review = "Exited " + plain(totals.exitShares) + " shares but only entered " + plain(totals.entryShares)
                    + ". Shares changed outside a fill - usually a reverse split or ticker change.";
        }
        trade.setReview(review);
    }

    /**
     * The running totals while calculate() walks through a trade's history.
     * Entry = what builds the position, exit = what shrinks it. Dollars are what you paid / got.
     */
    private static final class Totals {
        private final Trade.Side side;
        private final Fill.Action entryAction;
        private BigDecimal entryShares = BigDecimal.ZERO;
        private BigDecimal entryDollars = BigDecimal.ZERO;
        private BigDecimal exitShares = BigDecimal.ZERO;
        private BigDecimal exitDollars = BigDecimal.ZERO;
        private BigDecimal fees = BigDecimal.ZERO;
        private String review;

        private Totals(Trade.Side side) {
            this.side = side;
            this.entryAction = side == Trade.Side.LONG ? Fill.Action.BUY : Fill.Action.SELL;
        }

        /** A fill: shares and dollars (Schwab's exact amount) go to entry or exit, fees add up. */
        private void add(Fill fill) {
            BigDecimal dollars = fill.getDollars();
            if (fill.getAction() == entryAction) {
                entryShares = entryShares.add(fill.getQuantity());
                entryDollars = entryDollars.add(dollars);
            } else {
                exitShares = exitShares.add(fill.getQuantity());
                exitDollars = exitDollars.add(dollars);
            }
            fees = fees.add(fill.getFees());
        }

        /**
         * A group of share changes:
         *   one stock out + another in = reverse split -> convert all shares (x new / old), dollars stay
         *   anything else -> shares added or removed with no money we know of -> flag for review
         */
        private void apply(List<ShareAdjustment> group) {
            Map<String, BigDecimal> netShares = netSharesBySymbol(group);
            BigDecimal sharesOut = netShares.values().stream().filter(s -> s.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add).abs();
            BigDecimal sharesIn = netShares.values().stream().filter(s -> s.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);

            boolean isSplit = netShares.size() == 2 && sharesOut.signum() > 0 && sharesIn.signum() > 0;
            if (isSplit) {
                entryShares = entryShares.multiply(sharesIn).divide(sharesOut, WORKING_SCALE, RoundingMode.HALF_UP);
                exitShares = exitShares.multiply(sharesIn).divide(sharesOut, WORKING_SCALE, RoundingMode.HALF_UP);
                return;
            }

            BigDecimal change = sharesIn.subtract(sharesOut);
            BigDecimal held = side == Trade.Side.LONG ? change : change.negate();
            String description = group.get(0).getDescription();
            if (held.signum() < 0) {
                exitShares = exitShares.add(held.abs());
                review = plain(held.abs()) + " shares were removed without a sale (\"" + description
                        + "\"). If Schwab paid cash instead (cash in lieu), it isn't in this P/L.";
            } else if (held.signum() > 0) {
                entryShares = entryShares.add(held);
                review = plain(held) + " shares were added without a buy (\"" + description
                        + "\"). Their cost isn't in this P/L.";
            }
            // held = 0: shares just moved inside the account (margin <-> cash) - nothing to do
        }
    }

    /** A share count written simply: 13465.000000 -> "13465". */
    private static String plain(BigDecimal shares) {
        return shares.setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** dollars / shares, or null if there are no shares. */
    private static BigDecimal average(BigDecimal dollars, BigDecimal shares) {
        return shares.signum() == 0 ? null : dollars.divide(shares, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Step 4: for every trade we think is still open, compare our share count with
     * what Schwab says you hold. A mismatch means shares changed in a way we couldn't follow -
     * flag it rather than trust the numbers.
     *
     * @return how many open trades need review
     */
    private int checkOpenTradesAgainstSchwab(String account) {
        List<Trade> openTrades = tradeDao.findOpenTrades(USER_ID, account);
        if (openTrades.isEmpty()) {
            return 0;
        }
        Map<String, BigDecimal> schwabSays = accountService.getPositions(account);

        int needingReview = 0;
        for (Trade trade : openTrades) {
            BigDecimal ours = trade.getSide() == Trade.Side.LONG
                    ? trade.getOpenQuantity() : trade.getOpenQuantity().negate();
            BigDecimal schwabs = schwabSays.getOrDefault(trade.getSymbol(), BigDecimal.ZERO);

            if (ours.compareTo(schwabs) != 0) {
                trade.setReview(MISMATCH_REASON + ours.stripTrailingZeros().toPlainString()
                        + " shares still open, Schwab says you hold " + schwabs.stripTrailingZeros().toPlainString()
                        + ". Usually a reverse split or ticker change.");
            } else if (trade.getReviewReason() != null && trade.getReviewReason().startsWith(MISMATCH_REASON)) {
                trade.setReview(null);                  // it matches now - clear our own old flag
            }
            if (trade.isNeedsReview()) {
                needingReview++;
            }
        }
        return needingReview;
    }

    // =====================================================================
    //  Reading your journal
    // =====================================================================

    /**
     * Your trades for a day, week, month or year - newest first.
     *
     * @param last4  which account, or null for your default account
     * @param period DAY, WEEK (Monday to Sunday), MONTH, YEAR, or ALL
     * @param day    any day inside the period you want, e.g. 2026-10-23 for "the week of Oct 23"
     */
    public List<Trade> findTrades(String last4, Period period, LocalDate day) {
        LocalDate start = switch (period) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
            case YEAR -> day.withDayOfYear(1);
            case ALL -> LocalDate.of(1970, 1, 1);
        };
        LocalDate end = switch (period) {
            case DAY -> start.plusDays(1);
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
            case YEAR -> start.plusYears(1);
            case ALL -> LocalDate.of(9999, 1, 1);
        };
        return tradeDao.findTrades(USER_ID, resolveAccount(last4),
                start.atStartOfDay(MARKET_TIME).toInstant(),
                end.atStartOfDay(MARKET_TIME).toInstant());
    }

    /**
     * One trade.
     *
     * @throws IllegalArgumentException if there's no such trade
     */
    public Trade getTrade(Long tradeId) {
        return findTradeOrFail(tradeId);
    }

    /**
     * One trade's fills, oldest first - what you see when you open a trade.
     *
     * @throws IllegalArgumentException if there's no such trade
     */
    public List<Fill> getFills(Long tradeId) {
        findTradeOrFail(tradeId);
        return tradeDao.findFillsOfTrade(tradeId);
    }

    /**
     * One trade's share changes (reverse splits etc.), oldest first.
     *
     * @throws IllegalArgumentException if there's no such trade
     */
    public List<ShareAdjustment> getShareAdjustments(Long tradeId) {
        findTradeOrFail(tradeId);
        return tradeDao.findShareAdjustmentsOfTrade(tradeId);
    }

    /**
     * Saves your setup name and notes on a trade. Pass null to leave one as it is.
     *
     * @throws IllegalArgumentException if there's no such trade
     */
    @Transactional
    public Trade updateNotes(Long tradeId, String setup, String notes) {
        Trade trade = findTradeOrFail(tradeId);
        if (setup != null) {
            trade.setSetup(setup.isBlank() ? null : setup.strip());
        }
        if (notes != null) {
            trade.setNotes(notes.isBlank() ? null : notes);
        }
        return tradeDao.saveTrade(trade);
    }

    // =====================================================================
    //  Small helpers
    // =====================================================================

    private Trade findTradeOrFail(Long tradeId) {
        return tradeDao.findTrade(tradeId)
                .filter(trade -> trade.getUserId().equals(USER_ID))
                .orElseThrow(() -> new IllegalArgumentException("No trade with id " + tradeId));
    }

    /** The account to use: the one you picked, or your default. */
    private String resolveAccount(String last4) {
        if (last4 != null && !last4.isBlank()) {
            return last4;
        }
        return accountService.getDefaultAccount().orElseThrow(() -> new IllegalArgumentException(
                "No account picked and no default set. Set one with PUT /api/accounts/default/{last4}"));
    }
}
