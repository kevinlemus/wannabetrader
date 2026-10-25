package com.wannabetrader.core.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wannabetrader.core.auth.dao.TokenDao;
import com.wannabetrader.core.auth.model.SchwabToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;

/**
 * Logs in to Schwab and keeps the login alive.
 *
 * How Schwab login works:
 *   1. You open loginUrl() in a browser and sign in on Schwab's own page.
 *   2. Schwab sends you back to our callback with a one-time "code" (good for ~30 seconds).
 *   3. login(code) trades that code for a token and saves it.
 *   4. Every Schwab request asks accessToken() for the pass. If it is about to expire,
 *      this class quietly gets a new one using the refresh token.
 *   5. After 7 days the refresh token dies too - then you log in again (step 1).
 */
@Service
public class SchwabAuthService {

    /** Writes log lines (to the console and logs/wannabetrader.log). Never log the tokens themselves. */
    private static final Logger log = LoggerFactory.getLogger(SchwabAuthService.class);

    /** Schwab's refresh token lasts 7 days from login. Schwab doesn't send this number, so we set it. */
    private static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(7);

    /** Get a new access token 1 minute early, so it never runs out in the middle of a request. */
    private static final Duration REFRESH_EARLY_BY = Duration.ofMinutes(1);

    private final TokenDao tokenDao;
    private final RestClient http;
    private final String appKey;
    private final String appSecret;
    private final String authUrl;
    private final String tokenUrl;
    private final String callbackUrl;

    /** All values come from application.properties (key + secret from application-local.properties). */
    public SchwabAuthService(TokenDao tokenDao,
                             RestClient.Builder http,
                             @Value("${schwab.app-key}") String appKey,
                             @Value("${schwab.app-secret}") String appSecret,
                             @Value("${schwab.auth-url}") String authUrl,
                             @Value("${schwab.token-url}") String tokenUrl,
                             @Value("${schwab.callback-url}") String callbackUrl) {
        this.tokenDao = tokenDao;
        this.http = http.build();
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.authUrl = authUrl;
        this.tokenUrl = tokenUrl;
        this.callbackUrl = callbackUrl;
    }

    /** Step 1: the Schwab sign-in page to open in a browser. */
    public String loginUrl() {
        return UriComponentsBuilder.fromUriString(authUrl)
                .queryParam("client_id", appKey)
                .queryParam("redirect_uri", callbackUrl)
                .encode()
                .toUriString();
    }

    // Step 2: done by SCHWAB, not by our code - nothing missing here.
    //   You sign in on Schwab's page. Schwab checks your username + password,
    //   then sends your browser back to our callback address with a one-time code:
    //   https://127.0.0.1:8080/api/auth/callback?code=...
    //   AuthController.callback() catches it and hands the code to login() below.

    /** Step 3: trade the one-time code from Schwab for a token, and save it. */
    public void login(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", callbackUrl);

        TokenResponse response = requestToken(form);
        Instant now = Instant.now();

        tokenDao.save(new SchwabToken(
                response.accessToken(),
                response.refreshToken(),
                now.plusSeconds(response.expiresIn()),
                now.plus(REFRESH_TOKEN_LIFETIME)));

        log.info("Logged in to Schwab. Login good until {}", now.plus(REFRESH_TOKEN_LIFETIME));
    }

    /** Sends a request to Schwab's token address. Schwab wants the app key + secret as "Basic" login. */
    private TokenResponse requestToken(MultiValueMap<String, String> form) {
        return http.post()
                .uri(tokenUrl)
                .headers(headers -> headers.setBasicAuth(appKey, appSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
    }
    
    /**
     * Step 4: the pass to send with a Schwab request. Refreshes it first if needed.
     *
     * "synchronized" = only one caller at a time, so two requests can't both refresh at once.
     *
     * @throws IllegalStateException if you have never logged in, or the 7 days are up
     */
    public synchronized String accessToken() {
        SchwabToken token = tokenDao.load()
                .orElseThrow(() -> new IllegalStateException(
                        "Not logged in to Schwab. Open /api/auth/login"));

        Instant now = Instant.now();

        if (now.isAfter(token.refreshTokenExpiresAt())) {
            log.warn("Schwab login expired on {} - you need to log in again", token.refreshTokenExpiresAt());
            throw new IllegalStateException(
                    "Schwab login expired (7-day limit). Open /api/auth/login");
        }

        if (now.plus(REFRESH_EARLY_BY).isAfter(token.accessTokenExpiresAt())) {
            token = refresh(token);
        }

        return token.accessToken();
    }

    /** Gets a new access token using the refresh token. The 7-day clock does NOT restart. */
    private SchwabToken refresh(SchwabToken old) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", old.refreshToken());

        TokenResponse response = requestToken(form);

        SchwabToken fresh = new SchwabToken(
                response.accessToken(),
                response.refreshToken() != null ? response.refreshToken() : old.refreshToken(),
                Instant.now().plusSeconds(response.expiresIn()),
                old.refreshTokenExpiresAt());

        tokenDao.save(fresh);
        log.info("Schwab pass refreshed. Good until {}", fresh.accessTokenExpiresAt());
        return fresh;
    }


    /**
     * What Schwab sends back. Only the 3 fields we use - the rest are ignored.
     * expiresIn = seconds until the access token dies (Schwab sends 1800 = 30 minutes).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn
    ) {
    }
}
