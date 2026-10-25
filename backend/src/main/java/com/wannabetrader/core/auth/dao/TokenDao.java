package com.wannabetrader.core.auth.dao;

import com.wannabetrader.core.auth.model.SchwabToken;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Saves and loads the Schwab token in the database (table "schwab_token").
 *
 * Why: without this, you would have to log in to Schwab every time the app restarts.
 *
 * This class only reads and writes. It never decides if a token is still good -
 * that is the service's job.
 */
@Repository
public class TokenDao {

    private final EntityManager db;

    /** @param db Hibernate's handle on the database - reads and writes rows for us */
    public TokenDao(EntityManager db) {
        this.db = db;
    }

    /**
     * Writes the token to the database. Replaces any token that was there before.
     * "merge" = insert the row if it's new, update it if it already exists.
     */
    @Transactional
    public void save(SchwabToken token) {
        db.merge(token);
    }

    /**
     * Reads the token from the database.
     *
     * @return the saved token, or empty if we have never logged in (no row yet)
     */
    @Transactional(readOnly = true)
    public Optional<SchwabToken> load() {
        return Optional.ofNullable(db.find(SchwabToken.class, SchwabToken.ONLY_ROW));
    }
}
