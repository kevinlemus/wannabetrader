package com.wannabetrader.core.account.controller;

import com.wannabetrader.core.account.model.SchwabAccount;
import com.wannabetrader.core.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Checks the /api/accounts addresses. The service is faked - no real Schwab. */
@WebMvcTest(AccountController.class)
class AccountControllerTest {

    private static final SchwabAccount MAIN = new SchwabAccount("12345678", "HASH-A");
    private static final SchwabAccount IRA = new SchwabAccount("87654321", "HASH-B");

    @Autowired
    MockMvc web;

    @MockitoBean
    AccountService accountService;

    @Test
    void listShowsLast4AndMarksDefault() throws Exception {
        when(accountService.getAccounts()).thenReturn(List.of(MAIN, IRA));
        when(accountService.getDefaultAccount()).thenReturn(Optional.of("5678"));

        web.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"account":"****5678","isDefault":true},
                         {"account":"****4321","isDefault":false}]
                        """, true));
    }

    @Test
    void listWithNoDefaultMarksNone() throws Exception {
        when(accountService.getAccounts()).thenReturn(List.of(MAIN));
        when(accountService.getDefaultAccount()).thenReturn(Optional.empty());

        web.perform(get("/api/accounts"))
                .andExpect(content().json("[{\"account\":\"****5678\",\"isDefault\":false}]", true));
    }

    @Test
    void selectShowsOneAccount() throws Exception {
        when(accountService.selectAccount("4321")).thenReturn(IRA);
        when(accountService.getDefaultAccount()).thenReturn(Optional.of("5678"));

        web.perform(get("/api/accounts/4321"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"account\":\"****4321\",\"isDefault\":false}", true));
    }

    @Test
    void setDefaultSavesAndShowsIt() throws Exception {
        when(accountService.setDefaultAccount("4321")).thenReturn(IRA);

        web.perform(put("/api/accounts/default/4321"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"account\":\"****4321\",\"isDefault\":true}", true));
    }

    @Test
    void unknownAccountGives400WithMessage() throws Exception {
        when(accountService.selectAccount("9999"))
                .thenThrow(new IllegalArgumentException("No account ending in 9999"));

        web.perform(get("/api/accounts/9999"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("No account ending in 9999"));
    }

    @Test
    void notLoggedInGives401WithMessage() throws Exception {
        when(accountService.getAccounts())
                .thenThrow(new IllegalStateException("Not logged in to Schwab. Open /api/auth/login"));

        web.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Not logged in to Schwab. Open /api/auth/login"));
    }
}
