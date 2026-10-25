package com.wannabetrader.core.account.service;

import com.wannabetrader.core.account.dao.SchwabAccountDao;
import com.wannabetrader.core.account.model.SchwabAccount;
import com.wannabetrader.core.auth.service.SchwabAuthService;
import com.wannabetrader.core.settings.dao.SettingsDao;
import com.wannabetrader.core.settings.model.UserSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Checks listing, selecting and the default account. Schwab and the settings file are faked. */
class AccountServiceTest {

    private static final SchwabAccount MAIN = new SchwabAccount("12345678", "HASH-A");
    private static final SchwabAccount IRA = new SchwabAccount("87654321", "HASH-B");

    private final SchwabAuthService authService = mock(SchwabAuthService.class);
    private final SchwabAccountDao accountDao = mock(SchwabAccountDao.class);
    private final SettingsDao settingsDao = mock(SettingsDao.class);
    private final AccountService service = new AccountService(authService, accountDao, settingsDao);

    private void loggedInWithTwoAccounts() {
        when(authService.accessToken()).thenReturn("my-pass");
        when(accountDao.findAll("my-pass")).thenReturn(List.of(MAIN, IRA));
    }

    @Test
    void getsPassThenAsksDao() {
        loggedInWithTwoAccounts();

        assertEquals(List.of(MAIN, IRA), service.getAccounts());
    }

    @Test
    void notLoggedInStopsBeforeCallingSchwab() {
        when(authService.accessToken()).thenThrow(new IllegalStateException("Not logged in"));

        assertThrows(IllegalStateException.class, service::getAccounts);
        verifyNoInteractions(accountDao);
    }

    @Test
    void selectFindsAccountByLast4() {
        loggedInWithTwoAccounts();

        assertEquals(IRA, service.selectAccount("4321"));
        verifyNoInteractions(settingsDao); // selecting saves nothing
    }

    @Test
    void selectUnknownAccountGivesClearError() {
        loggedInWithTwoAccounts();

        var error = assertThrows(IllegalArgumentException.class, () -> service.selectAccount("9999"));
        assertEquals("No account ending in 9999", error.getMessage());
    }

    @Test
    void selectRejectsAnythingButFourDigits() {
        assertThrows(IllegalArgumentException.class, () -> service.selectAccount("56"));
        assertThrows(IllegalArgumentException.class, () -> service.selectAccount("abcd"));
        verifyNoInteractions(accountDao); // never bothers Schwab with a bad pick
    }

    @Test
    void setDefaultSavesIt() {
        loggedInWithTwoAccounts();

        assertEquals(MAIN, service.setDefaultAccount("5678"));
        verify(settingsDao).save(argThat(saved -> "5678".equals(saved.defaultAccount())));
    }

    @Test
    void setDefaultWithTypoSavesNothing() {
        loggedInWithTwoAccounts();

        assertThrows(IllegalArgumentException.class, () -> service.setDefaultAccount("9999"));
        verify(settingsDao, never()).save(any());
    }

    @Test
    void getDefaultReadsSavedSettings() {
        when(settingsDao.load()).thenReturn(new UserSettings("5678"));
        assertEquals(Optional.of("5678"), service.getDefaultAccount());

        when(settingsDao.load()).thenReturn(new UserSettings());
        assertEquals(Optional.empty(), service.getDefaultAccount());
    }
}
