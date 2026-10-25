package com.wannabetrader.core.auth.service;

import com.wannabetrader.core.auth.dao.TokenDao;
import com.wannabetrader.core.auth.model.SchwabToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Tests the login logic against a fake Schwab (no internet, no real account). */
class SchwabAuthServiceTest {

    private static final String TOKEN_URL = "https://api.schwabapi.com/v1/oauth/token";

    private TokenDao tokenDao;
    private MockRestServiceServer fakeSchwab;
    private SchwabAuthService service;

    /** Stand-in for TokenDao that keeps the token in memory instead of the database. */
    private static class InMemoryTokenDao extends TokenDao {
        private SchwabToken saved;

        InMemoryTokenDao() {
            super(null);
        }

        @Override
        public void save(SchwabToken token) {
            saved = token;
        }

        @Override
        public Optional<SchwabToken> load() {
            return Optional.ofNullable(saved);
        }
    }

    @BeforeEach
    void setUp() {
        tokenDao = new InMemoryTokenDao();
        RestClient.Builder http = RestClient.builder();
        fakeSchwab = MockRestServiceServer.bindTo(http).build();
        service = new SchwabAuthService(tokenDao, http, "my-key", "my-secret",
                "https://api.schwabapi.com/v1/oauth/authorize", TOKEN_URL,
                "https://127.0.0.1:8080/api/auth/callback");
    }

    /** What Schwab's token reply looks like (extra fields included on purpose). */
    private static String schwabReply(String access, String refresh) {
        return """
                {"access_token":"%s","refresh_token":"%s","expires_in":1800,
                 "token_type":"Bearer","scope":"api","id_token":"xyz"}
                """.formatted(access, refresh);
    }

    @Test
    void loginUrlHasAppKeyAndCallback() {
        assertEquals("https://api.schwabapi.com/v1/oauth/authorize"
                        + "?client_id=my-key&redirect_uri=https://127.0.0.1:8080/api/auth/callback",
                service.loginUrl());
    }

    @Test
    void loginTradesCodeForTokenAndSavesIt() {
        fakeSchwab.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic bXkta2V5Om15LXNlY3JldA==")) // my-key:my-secret
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "authorization_code", "code", "CODE123")))
                .andRespond(withSuccess(schwabReply("access-1", "refresh-1"), MediaType.APPLICATION_JSON));

        service.login("CODE123");

        SchwabToken saved = tokenDao.load().orElseThrow();
        assertEquals("access-1", saved.accessToken());
        assertEquals("refresh-1", saved.refreshToken());
        assertInWindow(Instant.now().plusSeconds(1800), saved.accessTokenExpiresAt());
        assertInWindow(Instant.now().plus(Duration.ofDays(7)), saved.refreshTokenExpiresAt());
        fakeSchwab.verify();
    }

    @Test
    void freshTokenIsUsedWithoutCallingSchwab() {
        tokenDao.save(new SchwabToken("access-ok", "refresh-ok",
                Instant.now().plus(Duration.ofMinutes(20)), Instant.now().plus(Duration.ofDays(5))));

        assertEquals("access-ok", service.accessToken());
        fakeSchwab.verify(); // no calls expected
    }

    @Test
    void expiredAccessTokenIsRefreshedAndSevenDayClockKept() {
        Instant sevenDayEnd = Instant.now().plus(Duration.ofDays(3));
        tokenDao.save(new SchwabToken("access-old", "refresh-ok",
                Instant.now().minusSeconds(10), sevenDayEnd));

        fakeSchwab.expect(requestTo(TOKEN_URL))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "refresh_token", "refresh_token", "refresh-ok")))
                .andRespond(withSuccess(schwabReply("access-new", "refresh-ok"), MediaType.APPLICATION_JSON));

        assertEquals("access-new", service.accessToken());

        SchwabToken saved = tokenDao.load().orElseThrow();
        assertEquals("access-new", saved.accessToken());
        assertEquals(sevenDayEnd, saved.refreshTokenExpiresAt());
        fakeSchwab.verify();
    }

    @Test
    void notLoggedInGivesClearError() {
        var error = assertThrows(IllegalStateException.class, service::accessToken);
        assertTrue(error.getMessage().contains("/api/auth/login"));
    }

    @Test
    void afterSevenDaysYouMustLogInAgain() {
        tokenDao.save(new SchwabToken("a", "r",
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(1)));

        var error = assertThrows(IllegalStateException.class, service::accessToken);
        assertTrue(error.getMessage().contains("7-day"));
    }

    private static void assertInWindow(Instant expected, Instant actual) {
        assertTrue(Duration.between(expected, actual).abs().toSeconds() < 5,
                "expected about " + expected + " but was " + actual);
    }
}
