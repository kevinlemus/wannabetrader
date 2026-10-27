package com.wannabetrader.core.account.dao;

import com.wannabetrader.core.account.model.SchwabAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Checks the accounts and positions calls against a fake Schwab. */
class SchwabAccountDaoTest {

    private static final String TRADER_URL = "https://api.schwabapi.com/trader/v1";

    private MockRestServiceServer fakeSchwab;
    private SchwabAccountDao dao;

    @BeforeEach
    void setUp() {
        RestClient.Builder http = RestClient.builder();
        fakeSchwab = MockRestServiceServer.bindTo(http).build();
        dao = new SchwabAccountDao(http, TRADER_URL);
    }

    @Test
    void readsAccountsAndSendsThePass() {
        fakeSchwab.expect(requestTo(TRADER_URL + "/accounts/accountNumbers"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer my-pass"))
                .andRespond(withSuccess("""
                        [{"accountNumber":"12345678","hashValue":"HASH-A"},
                         {"accountNumber":"87654321","hashValue":"HASH-B"}]
                        """, MediaType.APPLICATION_JSON));

        List<SchwabAccount> accounts = dao.findAll("my-pass");

        assertEquals(List.of(new SchwabAccount("12345678", "HASH-A"),
                        new SchwabAccount("87654321", "HASH-B")), accounts);
        assertEquals("SchwabAccount[accountNumber=****5678]", accounts.get(0).toString());
        fakeSchwab.verify();
    }

    @Test
    void readsPositionsLongPositiveShortNegative() {
        fakeSchwab.expect(requestTo(TRADER_URL + "/accounts/HASH-A?fields=positions"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer my-pass"))
                .andRespond(withSuccess("""
                        {"securitiesAccount":{"type":"MARGIN","positions":[
                          {"longQuantity":60,"shortQuantity":0,"instrument":{"assetType":"EQUITY","symbol":"YMAT"}},
                          {"longQuantity":0,"shortQuantity":50,"instrument":{"assetType":"EQUITY","symbol":"XYZ"}}]}}
                        """, MediaType.APPLICATION_JSON));

        Map<String, BigDecimal> positions = dao.findPositions("my-pass", new SchwabAccount("12345678", "HASH-A"));

        assertEquals(0, new BigDecimal("60").compareTo(positions.get("YMAT")));
        assertEquals(0, new BigDecimal("-50").compareTo(positions.get("XYZ")));
        fakeSchwab.verify();
    }

    @Test
    void holdingNothingGivesEmptyPositions() {
        fakeSchwab.expect(requestTo(TRADER_URL + "/accounts/HASH-A?fields=positions"))
                .andRespond(withSuccess("{\"securitiesAccount\":{\"type\":\"MARGIN\"}}", MediaType.APPLICATION_JSON));

        assertEquals(Map.of(), dao.findPositions("my-pass", new SchwabAccount("12345678", "HASH-A")));
    }
}
