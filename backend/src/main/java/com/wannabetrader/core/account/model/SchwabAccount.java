package com.wannabetrader.core.account.model;

/**
 * One of your Schwab accounts.
 *
 * accountNumber - your real account number (the one you see in thinkorswim).
 * hashValue     - a scrambled stand-in for the account number. Schwab makes us use this
 *                 in every request about the account (orders, trades, balances),
 *                 so the real number never travels in web addresses.
 *
 * Schwab sends these two fields with these exact names, so this class can read
 * Schwab's reply directly - no separate DTO needed.
 */
public record SchwabAccount(
        String accountNumber,
        String hashValue
) {

    /** The account number with only the last 4 digits showing, e.g. "****5678". */
    public String maskedNumber() {
        String last4 = accountNumber == null || accountNumber.length() < 4
                ? "????"
                : accountNumber.substring(accountNumber.length() - 4);
        return "****" + last4;
    }

    /** Shows only the last 4 digits and hides the hash, so logs never hold the full number. */
    @Override
    public String toString() {
        return "SchwabAccount[accountNumber=" + maskedNumber() + "]";
    }
}
