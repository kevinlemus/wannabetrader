package com.wannabetrader.journal.dao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wannabetrader.core.account.model.SchwabAccount;
import com.wannabetrader.journal.model.Fill;
import com.wannabetrader.journal.model.ShareAdjustment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks Schwab for your account history and turns it into our classes:
 *   - fills          (Schwab type "TRADE")               -> Fill
 *   - share changes  (Schwab type "RECEIVE_AND_DELIVER")  -> ShareAdjustment  (reverse splits etc.)
 *
 * Like SchwabAccountDao, it does NOT get the access token itself - the service passes it in.
 *
 * Schwab rule: one request can cover at most 1 YEAR. The service asks year by year
 * for longer ranges.
 */
@Repository
public class SchwabTransactionDao {

    /** Writes log lines. Never log tokens or full account numbers. */
    private static final Logger log = LoggerFactory.getLogger(SchwabTransactionDao.class);

    /** The date format Schwab wants in the request: 2026-10-23T00:00:00.000Z */
    private static final DateTimeFormatter REQUEST_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** The date format Schwab sends back: 2026-10-23T19:44:52+0000 */
    private static final DateTimeFormatter REPLY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final RestClient http;
    private final String traderUrl;

    /** @param traderUrl Schwab's trading address (set in application.properties) */
    public SchwabTransactionDao(RestClient.Builder http,
                                @Value("${schwab.trader-url}") String traderUrl) {
        this.http = http.build();
        this.traderUrl = traderUrl;
    }

    /**
     * All your stock fills between two moments, oldest first.
     *
     * @param accessToken the pass from SchwabAuthService.accessToken()
     * @param account     which account (we use its hash in the request, its last 4 in the Fill)
     * @param userId      whose fills these are (always 1 for now)
     * @param from        start (at most 1 year before 'to')
     * @param to          end
     */
    public List<Fill> findFills(String accessToken, SchwabAccount account, Long userId,
                                Instant from, Instant to) {
        List<Fill> fills = new ArrayList<>();
        for (Transaction transaction : fetch(accessToken, account, from, to, "TRADE")) {
            Fill fill = toFill(transaction, userId, account.last4());
            if (fill != null) {
                fills.add(fill);
            }
        }
        fills.sort((a, b) -> a.getExecutedAt().compareTo(b.getExecutedAt()));
        return fills;
    }

    /**
     * All stock share changes that were NOT fills (reverse splits, ticker changes, moves
     * between margin and cash), oldest first. Cash-fund entries are skipped - they aren't stocks.
     *
     * Same parameters as findFills.
     */
    public List<ShareAdjustment> findShareAdjustments(String accessToken, SchwabAccount account, Long userId,
                                                      Instant from, Instant to) {
        List<ShareAdjustment> adjustments = new ArrayList<>();
        for (Transaction transaction : fetch(accessToken, account, from, to, "RECEIVE_AND_DELIVER")) {
            List<TransferItem> stocks = transaction.transferItems() == null ? List.of()
                    : transaction.transferItems().stream()
                    .filter(item -> item.instrument() != null && "EQUITY".equals(item.instrument().assetType()))
                    .toList();
            if (stocks.isEmpty()) {
                continue;                                   // e.g. the cash fund (MMDA1)
            }
            if (stocks.size() > 1) {
                log.warn("Schwab activity {} changes {} stocks at once - only the first is used",
                        transaction.activityId(), stocks.size());
            }
            TransferItem stock = stocks.get(0);
            adjustments.add(new ShareAdjustment(
                    transaction.activityId(),
                    userId,
                    account.last4(),
                    stock.instrument().symbol(),
                    stock.amount(),
                    OffsetDateTime.parse(transaction.time(), REPLY_TIME).toInstant(),
                    transaction.description()));
        }
        adjustments.sort((a, b) -> a.getExecutedAt().compareTo(b.getExecutedAt()));
        return adjustments;
    }

    /** Asks Schwab for one type of history between two moments. */
    private List<Transaction> fetch(String accessToken, SchwabAccount account,
                                    Instant from, Instant to, String type) {
        List<Transaction> transactions = http.get()
                .uri(traderUrl + "/accounts/{hash}/transactions?startDate={from}&endDate={to}&types={type}",
                        account.hashValue(), REQUEST_TIME.format(from), REQUEST_TIME.format(to), type)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(new ParameterizedTypeReference<List<Transaction>>() {});
        return transactions == null ? List.of() : transactions;
    }

    /**
     * Turns one Schwab transaction into a Fill.
     *
     * A Schwab transaction has several "transferItems":
     *   - ONE is the stock itself (amount = shares: positive = bought, negative = sold)
     *   - the rest are fees (commission, SEC fee, TAF...) - we add those up
     *
     * @return the Fill, or null if it isn't a stock fill (e.g. an option) - those are skipped for now
     */
    private Fill toFill(Transaction transaction, Long userId, String last4) {
        TransferItem stock = null;
        BigDecimal fees = BigDecimal.ZERO;

        for (TransferItem item : transaction.transferItems()) {
            if (item.feeType() != null) {
                fees = fees.add(item.cost().abs());                    // fees come as negative costs
            } else if (item.instrument() != null && "EQUITY".equals(item.instrument().assetType())) {
                stock = item;
            }
        }

        if (stock == null || !"VALID".equals(transaction.status())) {
            log.info("Skipped Schwab activity {} (not a valid stock fill)", transaction.activityId());
            return null;
        }

        Fill.Action action = stock.amount().signum() > 0 ? Fill.Action.BUY : Fill.Action.SELL;
        return new Fill(
                transaction.activityId(),
                transaction.orderId(),
                userId,
                last4,
                stock.instrument().symbol(),
                action,
                stock.amount().abs(),
                stock.price(),
                stock.cost().abs(),                                     // Schwab's exact dollars, rounded its way
                fees,
                OffsetDateTime.parse(transaction.time(), REPLY_TIME).toInstant());
    }

    // ---- What Schwab sends back. Only the fields we use - the rest are ignored. ----

    /** One Schwab transaction = one fill, or one share change. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Transaction(Long activityId, Long orderId, String time, String status,
                               String description, List<TransferItem> transferItems) {
    }

    /** One line inside a transaction: the stock, or a fee. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TransferItem(Instrument instrument, BigDecimal amount, BigDecimal cost,
                                BigDecimal price, String feeType) {
    }

    /** What was traded. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Instrument(String assetType, String symbol) {
    }
}
