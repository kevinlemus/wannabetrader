package com.wannabetrader.journal.dao;

import com.wannabetrader.core.account.model.SchwabAccount;
import com.wannabetrader.journal.model.Fill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Checks that Schwab's transactions turn into the right Fills.
 * The fake replies are shaped exactly like your real Schwab data (trimmed).
 */
class SchwabTransactionDaoTest {

    private static final String TRADER_URL = "https://api.schwabapi.com/trader/v1";
    private static final SchwabAccount ACCOUNT = new SchwabAccount("12346943", "HASH-A");

    private MockRestServiceServer fakeSchwab;
    private SchwabTransactionDao dao;

    @BeforeEach
    void setUp() {
        RestClient.Builder http = RestClient.builder();
        fakeSchwab = MockRestServiceServer.bindTo(http).build();
        dao = new SchwabTransactionDao(http, TRADER_URL);
    }

    /** A sell at 19:44, a buy at 14:30 (out of order on purpose), and an option that must be skipped. */
    private static final String SCHWAB_REPLY = """
            [
             {"activityId":2,"orderId":20,"time":"2026-10-23T19:44:52+0000","status":"VALID","type":"TRADE",
              "transferItems":[
                {"instrument":{"assetType":"CURRENCY","symbol":"CURRENCY_USD"},"amount":0,"cost":0,"feeType":"COMMISSION"},
                {"instrument":{"assetType":"CURRENCY","symbol":"CURRENCY_USD"},"amount":0,"cost":-0.01,"feeType":"SEC_FEE"},
                {"instrument":{"assetType":"EQUITY","symbol":"YMAT","type":"COMMON_STOCK"},
                 "amount":-0.9493,"cost":1.91,"price":2.015,"positionEffect":"CLOSING"}]},
             {"activityId":1,"orderId":10,"time":"2026-10-23T14:30:00+0000","status":"VALID","type":"TRADE",
              "transferItems":[
                {"instrument":{"assetType":"CURRENCY","symbol":"CURRENCY_USD"},"amount":0,"cost":0,"feeType":"COMMISSION"},
                {"instrument":{"assetType":"EQUITY","symbol":"YMAT","type":"COMMON_STOCK"},
                 "amount":100,"cost":-198,"price":1.98,"positionEffect":"OPENING"}]},
             {"activityId":3,"orderId":30,"time":"2026-10-23T15:00:00+0000","status":"VALID","type":"TRADE",
              "transferItems":[
                {"instrument":{"assetType":"OPTION","symbol":"YMAT 261120C00002000"},
                 "amount":1,"cost":-15,"price":0.15,"positionEffect":"OPENING"}]}
            ]
            """;

    @Test
    void turnsSchwabTransactionsIntoFills() {
        fakeSchwab.expect(requestTo(TRADER_URL + "/accounts/HASH-A/transactions"
                        // ":" is sent as "%3A" (standard web encoding) - Schwab accepts it
                        + "?startDate=2026-10-23T00%3A00%3A00.000Z&endDate=2026-10-24T00%3A00%3A00.000Z&types=TRADE"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer my-pass"))
                .andRespond(withSuccess(SCHWAB_REPLY, MediaType.APPLICATION_JSON));

        List<Fill> fills = dao.findFills("my-pass", ACCOUNT, 1L,
                Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-24T00:00:00Z"));

        assertEquals(2, fills.size(), "the option is skipped");

        Fill buy = fills.get(0);                                    // oldest first
        assertEquals(1L, buy.getActivityId());
        assertEquals(Fill.Action.BUY, buy.getAction());
        assertEquals("YMAT", buy.getSymbol());
        assertEquals("6943", buy.getAccount());
        assertEquals(0, new BigDecimal("100").compareTo(buy.getQuantity()));
        assertEquals(0, new BigDecimal("1.98").compareTo(buy.getPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(buy.getFees()));
        assertEquals(Instant.parse("2026-10-23T14:30:00Z"), buy.getExecutedAt());

        Fill sell = fills.get(1);
        assertEquals(Fill.Action.SELL, sell.getAction());
        assertEquals(0, new BigDecimal("0.9493").compareTo(sell.getQuantity()), "shares stored positive");
        assertEquals(0, new BigDecimal("2.015").compareTo(sell.getPrice()));
        assertEquals(0, new BigDecimal("1.91").compareTo(sell.getDollars()), "Schwab's exact cash, not 0.9493 x 2.015 = 1.9128");
        assertEquals(0, new BigDecimal("0.01").compareTo(sell.getFees()), "fees stored positive");
        assertEquals(20L, sell.getOrderId());

        fakeSchwab.verify();
    }

    @Test
    void noTradesGivesEmptyList() {
        fakeSchwab.expect(requestTo(org.hamcrest.Matchers.startsWith(TRADER_URL)))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertTrue(dao.findFills("my-pass", ACCOUNT, 1L,
                Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-24T00:00:00Z")).isEmpty());
    }

    @Test
    void readsReverseSplitRecordsAndSkipsTheCashFund() {
        fakeSchwab.expect(requestTo(org.hamcrest.Matchers.containsString("types=RECEIVE_AND_DELIVER")))
                .andRespond(withSuccess("""
                        [
                         {"activityId":82685220180,"time":"2024-06-25T18:17:08+0000","type":"RECEIVE_AND_DELIVER",
                          "description":"CROWN ELECTROKINETICS CO",
                          "transferItems":[{"instrument":{"assetType":"EQUITY","symbol":"CRKN"},"amount":90,"cost":0}]},
                         {"activityId":82685220179,"time":"2024-06-25T18:17:08+0000","type":"RECEIVE_AND_DELIVER",
                          "description":"CROWN ELECTROKINETICSXXXREVERSE SPLIT EFF",
                          "transferItems":[{"instrument":{"assetType":"EQUITY","symbol":"228339305"},"amount":-13465,"cost":0}]},
                         {"activityId":59296959489,"time":"2023-09-07T04:42:47+0000","type":"RECEIVE_AND_DELIVER",
                          "description":"Cash Alternatives Purchase",
                          "transferItems":[{"instrument":{"assetType":"CASH_EQUIVALENT","symbol":"MMDA1"},"amount":2000,"cost":0}]}
                        ]
                        """, MediaType.APPLICATION_JSON));

        var changes = dao.findShareAdjustments("my-pass", ACCOUNT, 1L,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T00:00:00Z"));

        assertEquals(2, changes.size(), "the cash fund (MMDA1) is skipped");
        assertEquals(List.of("CRKN", "228339305"), changes.stream().map(c -> c.getSymbol()).toList());
        assertEquals(0, new BigDecimal("-13465").compareTo(changes.get(1).getShares()));
        assertEquals("6943", changes.get(0).getAccount());
        assertEquals(Instant.parse("2024-06-25T18:17:08Z"), changes.get(0).getExecutedAt());
        assertEquals("CROWN ELECTROKINETICSXXXREVERSE SPLIT EFF", changes.get(1).getDescription());
    }
}
