package com.wannabetrader.core.account.dao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wannabetrader.core.account.model.SchwabAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks Schwab for your accounts, and for what each account holds right now.
 *
 * A dao gets data from somewhere - here "somewhere" is Schwab's servers instead of a file.
 *
 * It does NOT get the access token itself. The service passes it in.
 * Why: the dao is the bottom layer. If it called the service, the layers would loop
 * (service -> dao -> service). Keeping it one-way keeps it clean.
 */
@Repository
public class SchwabAccountDao {

    private final RestClient http;
    private final String traderUrl;

    /** @param traderUrl Schwab's trading address (set in application.properties) */
    public SchwabAccountDao(RestClient.Builder http,
                            @Value("${schwab.trader-url}") String traderUrl) {
        this.http = http.build();
        this.traderUrl = traderUrl;
    }

    /**
     * All your Schwab accounts, each with its account number and hash.
     *
     * @param accessToken the pass from SchwabAuthService.accessToken()
     * @return your accounts, or an empty list if Schwab sends none
     */
    public List<SchwabAccount> findAll(String accessToken) {
        List<SchwabAccount> accounts = http.get()
                .uri(traderUrl + "/accounts/accountNumbers")
                .headers(headers -> headers.setBearerAuth(accessToken))     // "Bearer" = "here's my pass"
                .retrieve()
                .body(new ParameterizedTypeReference<List<SchwabAccount>>() {});
        return accounts == null ? List.of() : accounts;
    }

    /**
     * What the account holds right now: symbol -> shares.
     * Positive = you own the shares (long). Negative = you're short.
     * A stock you don't hold isn't in the map at all.
     *
     * @param accessToken the pass from SchwabAuthService.accessToken()
     */
    public Map<String, BigDecimal> findPositions(String accessToken, SchwabAccount account) {
        AccountReply reply = http.get()
                .uri(traderUrl + "/accounts/{hash}?fields=positions", account.hashValue())
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(AccountReply.class);

        Map<String, BigDecimal> positions = new HashMap<>();
        if (reply == null || reply.securitiesAccount() == null || reply.securitiesAccount().positions() == null) {
            return positions;
        }
        for (Position position : reply.securitiesAccount().positions()) {
            BigDecimal shares = orZero(position.longQuantity()).subtract(orZero(position.shortQuantity()));
            positions.merge(position.instrument().symbol(), shares, BigDecimal::add);
        }
        return positions;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ---- What Schwab sends back for positions. Only the fields we use - the rest are ignored. ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AccountReply(SecuritiesAccount securitiesAccount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SecuritiesAccount(List<Position> positions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Position(BigDecimal longQuantity, BigDecimal shortQuantity, Instrument instrument) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Instrument(String symbol) {
    }
}
