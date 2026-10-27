package com.wannabetrader.core.account.service;

import com.wannabetrader.core.account.dao.SchwabAccountDao;
import com.wannabetrader.core.account.model.SchwabAccount;
import com.wannabetrader.core.auth.service.SchwabAuthService;
import com.wannabetrader.core.settings.dao.SettingsDao;
import com.wannabetrader.core.settings.model.UserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything about your Schwab accounts: list them, select one, set your default,
 * and see what an account holds right now.
 *
 * Accounts are picked by their LAST 4 DIGITS (e.g. "5678") - the same digits
 * thinkorswim shows in its account dropdown.
 *
 *   Select      = "show me this account" (nothing saved - like flipping the dropdown)
 *   Set default = "always open on this account" (saved in the database, survives restarts)
 */
@Service
public class AccountService {

    /** Writes log lines (to the console and logs/wannabetrader.log). Only last 4 digits, never full numbers. */
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final SchwabAuthService authService;
    private final SchwabAccountDao accountDao;
    private final SettingsDao settingsDao;

    public AccountService(SchwabAuthService authService,
                          SchwabAccountDao accountDao,
                          SettingsDao settingsDao) {
        this.authService = authService;
        this.accountDao = accountDao;
        this.settingsDao = settingsDao;
    }

    /**
     * All accounts under your Schwab login.
     *
     * Two steps: get a working pass from SchwabAuthService, then hand it to the dao.
     *
     * @throws IllegalStateException if you are not logged in, or the 7 days are up
     */
    public List<SchwabAccount> getAccounts() {
        String pass = authService.accessToken();
        return accountDao.findAll(pass);
    }

    /**
     * Finds one account by its last 4 digits. Nothing is saved.
     * The returned account carries the hash that Schwab needs for trades.
     *
     * @param last4 e.g. "5678"
     * @throws IllegalArgumentException if no account ends in those digits
     */
    public SchwabAccount selectAccount(String last4) {
        if (last4 == null || !last4.matches("\\d{4}")) {
            throw new IllegalArgumentException("Pick an account by its last 4 digits, e.g. 5678");
        }
        return getAccounts().stream()
                .filter(account -> account.accountNumber().endsWith(last4))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No account ending in " + last4));
    }

    /**
     * Saves an account as your default. Checks it really exists first,
     * so a typo can't become your default.
     *
     * @param last4 e.g. "5678"
     * @return the account that is now your default
     */
    public SchwabAccount setDefaultAccount(String last4) {
        SchwabAccount account = selectAccount(last4);
        settingsDao.save(new UserSettings(last4));
        log.info("Default account set to {}", account.maskedNumber());
        return account;
    }

    /**
     * What an account holds right now, straight from Schwab: symbol -> shares
     * (positive = long, negative = short). Stocks you don't hold aren't in the map.
     *
     * @param last4 e.g. "5678"
     */
    public Map<String, BigDecimal> getPositions(String last4) {
        SchwabAccount account = selectAccount(last4);
        return accountDao.findPositions(authService.accessToken(), account);
    }

    /** @return the last 4 digits of your default account, or empty if you haven't picked one */
    public Optional<String> getDefaultAccount() {
        return Optional.ofNullable(settingsDao.load().defaultAccount());
    }
}
