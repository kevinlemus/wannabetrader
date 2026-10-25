package com.wannabetrader.core.account.dao;

import com.wannabetrader.core.account.model.SchwabAccount;
import com.wannabetrader.core.auth.service.SchwabAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Asks Schwab for your accounts.
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
}
