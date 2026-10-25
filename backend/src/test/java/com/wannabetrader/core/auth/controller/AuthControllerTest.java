package com.wannabetrader.core.auth.controller;

import com.wannabetrader.core.auth.service.SchwabAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Checks the two login addresses. The service is faked - no real Schwab. */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    MockMvc web;

    @MockitoBean
    SchwabAuthService authService;

    @Test
    void loginSendsBrowserToSchwab() throws Exception {
        when(authService.loginUrl()).thenReturn("https://api.schwabapi.com/v1/oauth/authorize?client_id=x");

        web.perform(get("/api/auth/login"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://api.schwabapi.com/v1/oauth/authorize?client_id=x"));
    }

    @Test
    void callbackPassesCodeToService() throws Exception {
        web.perform(get("/api/auth/callback").param("code", "ABC123"))
                .andExpect(status().isOk())
                .andExpect(content().string("Logged in to Schwab. You can close this tab."));

        verify(authService).login("ABC123");
    }
}
