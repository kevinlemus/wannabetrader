package com.wannabetrader.core.account.controller;

import com.wannabetrader.core.account.model.SchwabAccount;
import com.wannabetrader.core.account.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Your Schwab accounts in the browser - everything the account dropdown will need.
 *
 *   GET /api/accounts               - all accounts, your default marked     (fills the dropdown)
 *   GET /api/accounts/5678          - select one account                    (pick from the dropdown)
 *   PUT /api/accounts/default/5678  - make it your default (saved)          ("always open on this one")
 *
 * Only the last 4 digits are ever shown. The hash stays inside the app - you never need it.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** All accounts, with your default marked. */
    @GetMapping
    public List<AccountView> accounts() {
        String defaultLast4 = accountService.getDefaultAccount().orElse(null);
        return accountService.getAccounts().stream()
                .map(account -> AccountView.of(account, defaultLast4))
                .toList();
    }

    /** Selects one account by its last 4 digits. Nothing is saved. */
    @GetMapping("/{last4}")
    public AccountView select(@PathVariable String last4) {
        String defaultLast4 = accountService.getDefaultAccount().orElse(null);
        return AccountView.of(accountService.selectAccount(last4), defaultLast4);
    }

    /** Saves this account as your default. */
    @PutMapping("/default/{last4}")
    public AccountView setDefault(@PathVariable String last4) {
        return AccountView.of(accountService.setDefaultAccount(last4), last4);
    }

    /** Not logged in (or the 7 days are up)? Answer "401 Unauthorized" with the plain message. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> notLoggedIn(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
    }

    /** Bad or unknown last 4 digits? Answer "400 Bad Request" with the plain message. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badAccount(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    /**
     * What the browser sees for one account: { "account": "****5678", "isDefault": true }
     * Only this controller uses it, so it lives here (same idea as TokenResponse).
     */
    record AccountView(String account, boolean isDefault) {

        static AccountView of(SchwabAccount account, String defaultLast4) {
            boolean isDefault = defaultLast4 != null && account.accountNumber().endsWith(defaultLast4);
            return new AccountView(account.maskedNumber(), isDefault);
        }
    }
}
