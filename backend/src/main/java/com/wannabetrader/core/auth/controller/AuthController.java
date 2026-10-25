package com.wannabetrader.core.auth.controller;

import com.wannabetrader.core.auth.service.SchwabAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The two web addresses for logging in to Schwab.
 *
 *   GET /api/auth/login     - you open this. It sends your browser to Schwab's sign-in page.
 *   GET /api/auth/callback  - Schwab sends your browser here after you sign in,
 *                             with a one-time code in the address (?code=...).
 *
 * This class has no logic. It only passes things to SchwabAuthService.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SchwabAuthService authService;

    public AuthController(SchwabAuthService authService) {
        this.authService = authService;
    }

    /** Step 1: send the browser to Schwab's sign-in page. */
    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        return ResponseEntity.status(HttpStatus.FOUND)          // FOUND (302) = "go to this other address"
                .location(URI.create(authService.loginUrl()))
                .build();
    }

    /** Step 2: Schwab sends you back here with the code. Trade it for a token. */
    @GetMapping("/callback")
    public String callback(@RequestParam("code") String code) {
        authService.login(code);
        return "Logged in to Schwab. You can close this tab.";
    }
}
