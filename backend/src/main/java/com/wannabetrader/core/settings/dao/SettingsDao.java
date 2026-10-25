package com.wannabetrader.core.settings.dao;

import com.wannabetrader.core.settings.model.UserSettings;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves and loads your settings (like your default account) in the database
 * (table "user_settings").
 *
 * Works the same way as TokenDao - only reads and writes, never decides anything.
 */
@Repository
public class SettingsDao {

    private final EntityManager db;

    /** @param db Hibernate's handle on the database - reads and writes rows for us */
    public SettingsDao(EntityManager db) {
        this.db = db;
    }

    /**
     * Writes the settings to the database. Replaces what was there before.
     * "merge" = insert the row if it's new, update it if it already exists.
     */
    @Transactional
    public void save(UserSettings settings) {
        db.merge(settings);
    }

    /**
     * Reads the settings from the database.
     *
     * @return your saved settings, or empty settings if you haven't saved any yet
     */
    @Transactional(readOnly = true)
    public UserSettings load() {
        UserSettings saved = db.find(UserSettings.class, UserSettings.ONLY_ROW);
        return saved != null ? saved : new UserSettings();
    }
}
