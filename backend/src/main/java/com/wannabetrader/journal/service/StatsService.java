package com.wannabetrader.journal.service;

import com.wannabetrader.journal.dao.TradeDao;
import com.wannabetrader.journal.model.DailyStats;
import com.wannabetrader.journal.model.Fill;
import com.wannabetrader.journal.model.Trade;
import com.wannabetrader.journal.model.TradeStats;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Works out your statistics from your CLOSED trades.
 *
 *   getStats      -> one TradeStats for a day / week / month / year / all time
 *   getDailyStats -> the same, day by day: green days, red days, best and worst day...
 *   getBreakdown  -> the same trades split into groups, one TradeStats per group, e.g.
 *                    by time of day:  "9:30-10:00" -> stats,  "10:00-11:00" -> stats, ...
 *
 * The breakdowns are where the answers are: "I lose money after 11", "my 2nd try on a
 * ticker is worse than my 1st", "I add to losers and it costs me".
 *
 * Nothing is saved - everything is worked out fresh from your trades each time.
 * Times follow New York (the market's clock).
 */
@Service
public class StatsService {

    /** The ways your trades can be split into groups. Adding one = add it here + in label(). */
    public enum Breakdown {
        SIDE,                   // long vs short
        TIME_OF_DAY,            // when you opened the trade
        DAY_OF_WEEK,
        HOLD_TIME,              // how long you held it
        PRICE,                  // your average entry price
        TRADE_NUMBER_OF_DAY,    // 1st trade of the day, 2nd, 3rd...  -> overtrading
        AFTER_A_LOSS,           // taken right after a losing trade?  -> revenge trading
        ATTEMPT_ON_TICKER,      // 1st, 2nd, 3rd try on the same stock that day
        SIZE_AFTER_A_LOSS,      // bigger or smaller size after a loss?  -> tilt
        ADDED_TO_LOSER          // did you add shares while the trade was against you?
    }

    /** A trade opened this soon after a losing trade closed counts as "right after a loss". */
    private static final Duration RIGHT_AFTER = Duration.ofMinutes(5);

    /** More than 10% bigger / smaller than the trade before counts as a different size. */
    private static final BigDecimal BIGGER = new BigDecimal("1.10");
    private static final BigDecimal SMALLER = new BigDecimal("0.90");

    /** Money in the results is rounded to cents, percents to 1 decimal. */
    private static final int CENTS = 2;

    private final TradeService tradeService;
    private final TradeDao tradeDao;

    public StatsService(TradeService tradeService, TradeDao tradeDao) {
        this.tradeService = tradeService;
        this.tradeDao = tradeDao;
    }

    // =====================================================================
    //  The two things you can ask for
    // =====================================================================

    /** Your stats for a day, week, month, year or all time. Same parameters as TradeService.findTrades. */
    public TradeStats getStats(String last4, TradeService.Period period, LocalDate day) {
        return summarize(closedTrades(last4, period, day));
    }

    /**
     * Your trades for the period, split into groups, with stats for each group.
     * Groups come back in a sensible order (morning before afternoon, 1st try before 2nd...).
     * Groups with no trades are left out.
     */
    public Map<String, TradeStats> getBreakdown(String last4, TradeService.Period period, LocalDate day,
                                                Breakdown by) {
        return breakdown(closedTrades(last4, period, day), by);
    }

    /** Your stats day by day (green days, red days, best and worst day...). Same parameters as getStats. */
    public DailyStats getDailyStats(String last4, TradeService.Period period, LocalDate day) {
        return daily(closedTrades(last4, period, day));
    }

    // =====================================================================
    //  The math
    // =====================================================================

    /** Works out every number in TradeStats for a list of trades. Open trades are ignored. */
    public TradeStats summarize(List<Trade> trades) {
        List<Trade> closed = trades.stream()
                .filter(t -> t.getStatus() == Trade.Status.CLOSED)
                .sorted(Comparator.comparing(Trade::getClosedAt))          // in the order they finished
                .toList();

        int wins = 0, losses = 0, breakeven = 0;
        BigDecimal total = BigDecimal.ZERO, grossProfit = BigDecimal.ZERO, grossLoss = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO, largestWin = null, largestLoss = null;
        long winSeconds = 0, lossSeconds = 0;
        int streak = 0, maxWinStreak = 0, maxLossStreak = 0;
        BigDecimal running = BigDecimal.ZERO, peak = BigDecimal.ZERO, maxDrawdown = BigDecimal.ZERO;

        for (Trade trade : closed) {
            BigDecimal pnl = trade.getPnl();
            int result = pnl.setScale(CENTS, RoundingMode.HALF_UP).signum();   // under half a cent = breakeven
            total = total.add(pnl);
            fees = fees.add(trade.getFees());

            if (result > 0) {
                wins++;
                grossProfit = grossProfit.add(pnl);
                largestWin = largestWin == null ? pnl : largestWin.max(pnl);
                winSeconds += trade.getHoldTime().toSeconds();
                streak = streak > 0 ? streak + 1 : 1;
            } else if (result < 0) {
                losses++;
                grossLoss = grossLoss.add(pnl);
                largestLoss = largestLoss == null ? pnl : largestLoss.min(pnl);
                lossSeconds += trade.getHoldTime().toSeconds();
                streak = streak < 0 ? streak - 1 : -1;
            } else {
                breakeven++;
                streak = 0;
            }
            maxWinStreak = Math.max(maxWinStreak, streak);
            maxLossStreak = Math.max(maxLossStreak, -streak);

            // Drawdown: how far the running total has fallen below its best point so far
            running = running.add(pnl);
            peak = peak.max(running);
            maxDrawdown = maxDrawdown.min(running.subtract(peak));
        }

        int count = closed.size();
        BigDecimal avgWin = wins == 0 ? null : divide(grossProfit, wins);
        BigDecimal avgLoss = losses == 0 ? null : divide(grossLoss, losses);

        return new TradeStats(
                count, wins, losses, breakeven,
                count == 0 ? null : BigDecimal.valueOf(wins * 100.0 / count).setScale(1, RoundingMode.HALF_UP),

                money(total), money(grossProfit), money(grossLoss), money(fees),

                avgWin, avgLoss,
                count == 0 ? null : divide(total, count),
                grossLoss.signum() == 0 ? null : grossProfit.divide(grossLoss.abs(), CENTS, RoundingMode.HALF_UP),
                avgWin == null || avgLoss == null ? null : avgWin.divide(avgLoss.abs(), CENTS, RoundingMode.HALF_UP),

                largestWin == null ? null : money(largestWin),
                largestLoss == null ? null : money(largestLoss),

                wins == 0 ? null : Duration.ofSeconds(winSeconds / wins),
                losses == 0 ? null : Duration.ofSeconds(lossSeconds / losses),

                maxWinStreak, maxLossStreak, money(maxDrawdown));
    }

    /**
     * Groups closed trades by the day they CLOSED (New York) and works out the day-by-day numbers.
     * Open trades are ignored.
     */
    public DailyStats daily(List<Trade> trades) {
        // Each day's trades, oldest day first
        Map<LocalDate, List<Trade>> tradesByDay = new TreeMap<>();
        trades.stream()
                .filter(t -> t.getStatus() == Trade.Status.CLOSED)
                .forEach(t -> tradesByDay.computeIfAbsent(nyTime(t.getClosedAt()).toLocalDate(), d -> new ArrayList<>()).add(t));

        List<DailyStats.Day> days = new ArrayList<>();
        int green = 0, red = 0, flat = 0, fromOneTrade = 0, streak = 0, maxGreenStreak = 0, maxRedStreak = 0;
        BigDecimal greenTotal = BigDecimal.ZERO, redTotal = BigDecimal.ZERO;
        DailyStats.Day best = null, worst = null;

        for (Map.Entry<LocalDate, List<Trade>> entry : tradesByDay.entrySet()) {
            List<Trade> dayTrades = entry.getValue();
            BigDecimal pnl = dayTrades.stream().map(Trade::getPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal worstTrade = dayTrades.stream().map(Trade::getPnl).min(BigDecimal::compareTo).orElseThrow();
            int wins = (int) dayTrades.stream().filter(t -> t.getPnl().setScale(CENTS, RoundingMode.HALF_UP).signum() > 0).count();
            int losses = (int) dayTrades.stream().filter(t -> t.getPnl().setScale(CENTS, RoundingMode.HALF_UP).signum() < 0).count();

            DailyStats.Day day = new DailyStats.Day(entry.getKey(), dayTrades.size(), wins, losses, money(pnl), money(worstTrade));
            days.add(day);

            int result = day.pnl().signum();
            if (result > 0) {
                green++;
                greenTotal = greenTotal.add(pnl);
                streak = streak > 0 ? streak + 1 : 1;
            } else if (result < 0) {
                red++;
                redTotal = redTotal.add(pnl);
                streak = streak < 0 ? streak - 1 : -1;
                if (worstTrade.compareTo(pnl) <= 0) {
                    fromOneTrade++;         // that one trade lost at least the whole day
                }
            } else {
                flat++;
                streak = 0;
            }
            maxGreenStreak = Math.max(maxGreenStreak, streak);
            maxRedStreak = Math.max(maxRedStreak, -streak);

            if (best == null || day.pnl().compareTo(best.pnl()) > 0) {
                best = day;
            }
            if (worst == null || day.pnl().compareTo(worst.pnl()) < 0) {
                worst = day;
            }
        }

        int count = days.size();
        int tradeCount = days.stream().mapToInt(DailyStats.Day::trades).sum();
        return new DailyStats(
                count, green, red, flat,
                count == 0 ? null : BigDecimal.valueOf(green * 100.0 / count).setScale(1, RoundingMode.HALF_UP),
                green == 0 ? null : divide(greenTotal, green),
                red == 0 ? null : divide(redTotal, red),
                count == 0 ? null : BigDecimal.valueOf(tradeCount).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP),
                best, worst,
                maxGreenStreak, maxRedStreak,
                fromOneTrade,
                days);
    }

    /** Splits trades into groups and summarizes each group. */
    public Map<String, TradeStats> breakdown(List<Trade> trades, Breakdown by) {
        List<Trade> closed = trades.stream()
                .filter(t -> t.getStatus() == Trade.Status.CLOSED)
                .sorted(Comparator.comparing(Trade::getOpenedAt).thenComparing(Trade::getId))
                .toList();
        Map<Long, String> labels = label(closed, by);

        Map<String, List<Trade>> groups = new LinkedHashMap<>();
        for (String label : ORDER.get(by)) {
            groups.put(label, new ArrayList<>());
        }
        for (Trade trade : closed) {
            groups.get(labels.get(trade.getId())).add(trade);
        }

        Map<String, TradeStats> result = new LinkedHashMap<>();
        groups.forEach((label, group) -> {
            if (!group.isEmpty()) {
                result.put(label, summarize(group));
            }
        });
        return result;
    }

    // =====================================================================
    //  Putting each trade in a group
    // =====================================================================

    /** Every group name for each breakdown, in the order they're shown. */
    private static final Map<Breakdown, List<String>> ORDER = Map.of(
            Breakdown.SIDE, List.of("Long", "Short"),
            Breakdown.TIME_OF_DAY, List.of("Premarket (before 9:30)", "9:30-10:00", "10:00-11:00",
                    "11:00-12:00", "12:00-2:00 PM", "2:00-4:00 PM", "After hours (4:00 PM+)"),
            Breakdown.DAY_OF_WEEK, List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
                    "Saturday", "Sunday"),
            Breakdown.HOLD_TIME, List.of("Under 1 min", "1-5 min", "5-15 min", "15-60 min",
                    "1 hour+ (same day)", "Held overnight"),
            Breakdown.PRICE, List.of("Under $1", "$1-$2", "$2-$5", "$5-$10", "$10-$20", "$20+"),
            Breakdown.TRADE_NUMBER_OF_DAY, List.of("1st trade", "2nd trade", "3rd trade",
                    "4th-5th trade", "6th-10th trade", "11th+ trade"),
            Breakdown.AFTER_A_LOSS, List.of("First trade of the day", "After a win",
                    "Within 5 min of a loss", "5+ min after a loss"),
            Breakdown.ATTEMPT_ON_TICKER, List.of("1st try", "2nd try", "3rd try", "4th+ try"),
            Breakdown.SIZE_AFTER_A_LOSS, List.of("Bigger size after a loss", "Same size after a loss",
                    "Smaller size after a loss", "Not after a loss"),
            Breakdown.ADDED_TO_LOSER, List.of("Added to a loser", "Didn't add to a loser"));

    /** The group name for every trade (by trade id). Trades must be sorted by open time. */
    private Map<Long, String> label(List<Trade> trades, Breakdown by) {
        Map<Long, String> labels = new HashMap<>();
        switch (by) {
            case SIDE -> trades.forEach(t -> labels.put(t.getId(), t.getSide() == Trade.Side.LONG ? "Long" : "Short"));
            case TIME_OF_DAY -> trades.forEach(t -> labels.put(t.getId(), timeOfDay(nyTime(t.getOpenedAt()).toLocalTime())));
            case DAY_OF_WEEK -> trades.forEach(t -> labels.put(t.getId(), dayName(nyTime(t.getOpenedAt()).getDayOfWeek())));
            case HOLD_TIME -> trades.forEach(t -> labels.put(t.getId(), holdTime(t)));
            case PRICE -> trades.forEach(t -> labels.put(t.getId(), price(t.getAvgEntryPrice())));
            case ADDED_TO_LOSER -> labelAddedToLoser(trades, labels);
            default -> labelByDay(trades, by, labels);       // the ones that need the rest of that day
        }
        return labels;
    }

    /** Breakdowns that depend on what else you did that day: trade number, after a loss, tries, size. */
    private void labelByDay(List<Trade> trades, Breakdown by, Map<Long, String> labels) {
        Map<LocalDate, List<Trade>> byDay = new LinkedHashMap<>();
        for (Trade trade : trades) {
            byDay.computeIfAbsent(nyTime(trade.getOpenedAt()).toLocalDate(), d -> new ArrayList<>()).add(trade);
        }

        for (List<Trade> day : byDay.values()) {
            Map<String, Integer> triesPerTicker = new HashMap<>();
            for (int i = 0; i < day.size(); i++) {
                Trade trade = day.get(i);
                Trade before = lastClosedBefore(day, trade);
                String label = switch (by) {
                    case TRADE_NUMBER_OF_DAY -> tradeNumber(i + 1);
                    case AFTER_A_LOSS -> afterALoss(trade, before);
                    case ATTEMPT_ON_TICKER -> attempt(triesPerTicker.merge(trade.getSymbol(), 1, Integer::sum));
                    case SIZE_AFTER_A_LOSS -> sizeAfterALoss(trade, before);
                    default -> throw new IllegalStateException("Not a day breakdown: " + by);
                };
                labels.put(trade.getId(), label);
            }
        }
    }

    /** The trade from the same day that closed most recently before this one opened, or null. */
    private static Trade lastClosedBefore(List<Trade> day, Trade trade) {
        Trade latest = null;
        for (Trade other : day) {
            if (other != trade && !other.getClosedAt().isAfter(trade.getOpenedAt())
                    && (latest == null || other.getClosedAt().isAfter(latest.getClosedAt()))) {
                latest = other;
            }
        }
        return latest;
    }

    private static String afterALoss(Trade trade, Trade before) {
        if (before == null) {
            return "First trade of the day";
        }
        if (before.getPnl().setScale(CENTS, RoundingMode.HALF_UP).signum() >= 0) {
            return "After a win";
        }
        Duration gap = Duration.between(before.getClosedAt(), trade.getOpenedAt());
        return gap.compareTo(RIGHT_AFTER) <= 0 ? "Within 5 min of a loss" : "5+ min after a loss";
    }

    /** Compares the dollars put in (shares x avg entry) with the losing trade just before. */
    private static String sizeAfterALoss(Trade trade, Trade before) {
        if (before == null || before.getPnl().setScale(CENTS, RoundingMode.HALF_UP).signum() >= 0) {
            return "Not after a loss";
        }
        BigDecimal now = positionSize(trade);
        BigDecimal then = positionSize(before);
        if (then.signum() == 0) {
            return "Same size after a loss";
        }
        BigDecimal ratio = now.divide(then, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(BIGGER) > 0) {
            return "Bigger size after a loss";
        }
        return ratio.compareTo(SMALLER) < 0 ? "Smaller size after a loss" : "Same size after a loss";
    }

    private static BigDecimal positionSize(Trade trade) {
        return trade.getAvgEntryPrice() == null ? BigDecimal.ZERO : trade.getQuantity().multiply(trade.getAvgEntryPrice());
    }

    /**
     * "Added to a loser" = you bought more of a long while the price was BELOW your average so far
     * (or sold more of a short while it was ABOVE). Needs the trade's fills.
     */
    private void labelAddedToLoser(List<Trade> trades, Map<Long, String> labels) {
        Map<Long, List<Fill>> fillsByTrade = new HashMap<>();
        for (Fill fill : tradeDao.findFillsOfTrades(trades.stream().map(Trade::getId).toList())) {
            fillsByTrade.computeIfAbsent(fill.getTrade().getId(), id -> new ArrayList<>()).add(fill);
        }

        for (Trade trade : trades) {
            Fill.Action entry = trade.getSide() == Trade.Side.LONG ? Fill.Action.BUY : Fill.Action.SELL;
            BigDecimal shares = BigDecimal.ZERO;
            BigDecimal dollars = BigDecimal.ZERO;
            boolean added = false;

            for (Fill fill : fillsByTrade.getOrDefault(trade.getId(), List.of())) {
                if (fill.getAction() != entry) {
                    continue;
                }
                if (shares.signum() > 0) {
                    BigDecimal average = dollars.divide(shares, 6, RoundingMode.HALF_UP);
                    int vsAverage = fill.getPrice().compareTo(average);
                    if ((trade.getSide() == Trade.Side.LONG && vsAverage < 0)
                            || (trade.getSide() == Trade.Side.SHORT && vsAverage > 0)) {
                        added = true;
                    }
                }
                shares = shares.add(fill.getQuantity());
                dollars = dollars.add(fill.getDollars());
            }
            labels.put(trade.getId(), added ? "Added to a loser" : "Didn't add to a loser");
        }
    }

    private static String timeOfDay(LocalTime time) {
        if (time.isBefore(LocalTime.of(9, 30))) return "Premarket (before 9:30)";
        if (time.isBefore(LocalTime.of(10, 0))) return "9:30-10:00";
        if (time.isBefore(LocalTime.of(11, 0))) return "10:00-11:00";
        if (time.isBefore(LocalTime.of(12, 0))) return "11:00-12:00";
        if (time.isBefore(LocalTime.of(14, 0))) return "12:00-2:00 PM";
        if (time.isBefore(LocalTime.of(16, 0))) return "2:00-4:00 PM";
        return "After hours (4:00 PM+)";
    }

    private static String holdTime(Trade trade) {
        if (!nyTime(trade.getClosedAt()).toLocalDate().equals(nyTime(trade.getOpenedAt()).toLocalDate())) {
            return "Held overnight";
        }
        long minutes = trade.getHoldTime().toSeconds() / 60;
        if (minutes < 1) return "Under 1 min";
        if (minutes < 5) return "1-5 min";
        if (minutes < 15) return "5-15 min";
        if (minutes < 60) return "15-60 min";
        return "1 hour+ (same day)";
    }

    private static String price(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ONE) < 0) return "Under $1";
        if (price.compareTo(new BigDecimal("2")) < 0) return "$1-$2";
        if (price.compareTo(new BigDecimal("5")) < 0) return "$2-$5";
        if (price.compareTo(new BigDecimal("10")) < 0) return "$5-$10";
        if (price.compareTo(new BigDecimal("20")) < 0) return "$10-$20";
        return "$20+";
    }

    private static String tradeNumber(int n) {
        if (n == 1) return "1st trade";
        if (n == 2) return "2nd trade";
        if (n == 3) return "3rd trade";
        if (n <= 5) return "4th-5th trade";
        if (n <= 10) return "6th-10th trade";
        return "11th+ trade";
    }

    private static String attempt(int n) {
        if (n == 1) return "1st try";
        if (n == 2) return "2nd try";
        if (n == 3) return "3rd try";
        return "4th+ try";
    }

    private static String dayName(DayOfWeek day) {
        String name = day.name();
        return name.charAt(0) + name.substring(1).toLowerCase();       // MONDAY -> Monday
    }

    // =====================================================================
    //  Small helpers
    // =====================================================================

    private List<Trade> closedTrades(String last4, TradeService.Period period, LocalDate day) {
        return tradeService.findTrades(last4, period, day).stream()
                .filter(t -> t.getStatus() == Trade.Status.CLOSED)
                .toList();
    }

    private static ZonedDateTime nyTime(Instant instant) {
        return instant.atZone(TradeService.MARKET_TIME);
    }

    private static BigDecimal divide(BigDecimal amount, int count) {
        return amount.divide(BigDecimal.valueOf(count), CENTS, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(CENTS, RoundingMode.HALF_UP);
    }
}
