package com.wannabetrader.core.auth.dao;

import com.wannabetrader.core.auth.model.SchwabToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a saved token comes back exactly the same.
 * Runs against a throwaway in-memory database (H2), never your real one.
 */
@DataJpaTest
@Import(TokenDao.class)
class TokenDaoTest {

    @Autowired
    TokenDao dao;

    @Autowired
    TestEntityManager db;

    /** Push the writes to the database and forget what's in memory, so load() really reads the row. */
    private void flushAndForget() {
        db.flush();
        db.clear();
    }

    @Test
    void loadIsEmptyBeforeAnySave() {
        assertTrue(dao.load().isEmpty());
    }

    @Test
    void savedTokenLoadsBackTheSame() {
        dao.save(new SchwabToken("access-123", "refresh-456",
                Instant.parse("2026-10-22T14:30:00Z"), Instant.parse("2026-10-29T14:00:00Z")));
        flushAndForget();

        SchwabToken loaded = dao.load().orElseThrow();
        assertEquals("access-123", loaded.accessToken());
        assertEquals("refresh-456", loaded.refreshToken());
        assertEquals(Instant.parse("2026-10-22T14:30:00Z"), loaded.accessTokenExpiresAt());
        assertEquals(Instant.parse("2026-10-29T14:00:00Z"), loaded.refreshTokenExpiresAt());
    }

    @Test
    void savingAgainReplacesTheOneRow() {
        Instant later = Instant.parse("2026-10-29T14:00:00Z");
        dao.save(new SchwabToken("old", "r", later, later));
        dao.save(new SchwabToken("new", "r", later, later));
        flushAndForget();

        assertEquals("new", dao.load().orElseThrow().accessToken());
        assertEquals(1L, db.getEntityManager()
                .createQuery("select count(t) from SchwabToken t", Long.class).getSingleResult());
    }
}
