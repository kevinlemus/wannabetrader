package com.wannabetrader.core.settings.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Your saved choices. Saved in the database table "user_settings", so they survive restarts.
 *
 * defaultAccount - last 4 digits of the account to show when you don't pick one (e.g. "5678").
 *                  null = you haven't picked one yet.
 *
 * There is only ONE set of settings, so the table only ever has one row (id = 1).
 * More settings can be added later as new fields - Hibernate adds the new column by itself.
 */
@Entity                         // Hibernate: "this class is a database table"
@Table(name = "user_settings")
public class UserSettings {

    /** The id of the one and only row. */
    public static final long ONLY_ROW = 1L;

    @Id                         // Hibernate: "this is the row's id"
    private Long id = ONLY_ROW;

    private String defaultAccount;

    /** Settings before you've chosen anything. Hibernate also uses this to read a row. */
    public UserSettings() {
    }

    public UserSettings(String defaultAccount) {
        this.defaultAccount = defaultAccount;
    }

    public String defaultAccount() {
        return defaultAccount;
    }

    @Override
    public String toString() {
        return "UserSettings[defaultAccount=" + defaultAccount + "]";
    }
}
