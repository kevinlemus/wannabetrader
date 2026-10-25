package com.wannabetrader.core.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The login pass Schwab gives us. Saved in the database table "schwab_token".
 *
 * accessToken  - sent with every Schwab request. Lasts 30 minutes.
 * refreshToken - used to get a new accessToken without logging in again. Lasts 7 days.
 * The two "ExpiresAt" fields say exactly when each one stops working.
 *
 * There is only ever ONE token, so the table only ever has one row (id = 1).
 * Saving a new token replaces that row.
 */
@Entity                         // Hibernate: "this class is a database table"
@Table(name = "schwab_token")
public class SchwabToken {

    /** The id of the one and only row. */
    public static final long ONLY_ROW = 1L;

    @Id                         // Hibernate: "this is the row's id"
    private Long id = ONLY_ROW;

    @Column(columnDefinition = "TEXT")      // tokens are long - TEXT has no length limit
    private String accessToken;

    @Column(columnDefinition = "TEXT")
    private String refreshToken;

    private Instant accessTokenExpiresAt;
    private Instant refreshTokenExpiresAt;

    /** Hibernate needs an empty constructor to build the object when reading a row. */
    protected SchwabToken() {
    }

    public SchwabToken(String accessToken, String refreshToken,
                       Instant accessTokenExpiresAt, Instant refreshTokenExpiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public Instant accessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    public Instant refreshTokenExpiresAt() {
        return refreshTokenExpiresAt;
    }

    /** Hides the real token values so they never end up in a log file. */
    @Override
    public String toString() {
        return "SchwabToken[accessTokenExpiresAt=" + accessTokenExpiresAt
                + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + "]";
    }
}
