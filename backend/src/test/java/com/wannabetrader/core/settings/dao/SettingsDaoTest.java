package com.wannabetrader.core.settings.dao;

import com.wannabetrader.core.settings.model.UserSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checks that saved settings come back the same.
 * Runs against a throwaway in-memory database (H2), never your real one.
 */
@DataJpaTest
@Import(SettingsDao.class)
class SettingsDaoTest {

    @Autowired
    SettingsDao dao;

    @Autowired
    TestEntityManager db;

    @Test
    void loadIsEmptyBeforeAnySave() {
        assertNull(dao.load().defaultAccount());
    }

    @Test
    void savedSettingsLoadBackTheSame() {
        dao.save(new UserSettings("5678"));
        db.flush();
        db.clear();

        assertEquals("5678", dao.load().defaultAccount());
    }

    @Test
    void changingDefaultReplacesIt() {
        dao.save(new UserSettings("5678"));
        dao.save(new UserSettings("4321"));
        db.flush();
        db.clear();

        assertEquals("4321", dao.load().defaultAccount());
    }
}
