package com.wannabetrader.journal.controller;

import com.wannabetrader.journal.model.Fill;
import com.wannabetrader.journal.model.Trade;
import com.wannabetrader.journal.service.TradeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Checks the /api/journal addresses. The service is faked - no real Schwab or database. */
@WebMvcTest(TradeController.class)
class TradeControllerTest {

    @Autowired
    MockMvc web;

    @MockitoBean
    TradeService tradeService;

    private static Trade closedTrade() {
        Trade trade = new Trade(1L, "6943", "YMAT", Trade.Side.LONG, Instant.parse("2026-10-23T14:30:00Z"));
        trade.setStatus(Trade.Status.CLOSED);
        trade.setPnl(new BigDecimal("9.99"));
        trade.setSetup("gap and go");
        return trade;
    }

    @Test
    void importWithoutDatesPullsTheLast7DaysIncludingToday() throws Exception {
        when(tradeService.importFills(any(), any(), any())).thenReturn(new TradeService.ImportResult(3, 1, 0));
        LocalDate today = LocalDate.now(TradeService.MARKET_TIME);

        web.perform(post("/api/journal/import"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"newFills\":3,\"newTrades\":1,\"tradesNeedingReview\":0}"));

        verify(tradeService).importFills(null,
                today.minusDays(7).atStartOfDay(TradeService.MARKET_TIME).toInstant(),
                today.plusDays(1).atStartOfDay(TradeService.MARKET_TIME).toInstant());
    }

    @Test
    void importWithFromDatePullsHistory() throws Exception {
        when(tradeService.importFills(any(), any(), any())).thenReturn(new TradeService.ImportResult(1113, 306, 1));

        web.perform(post("/api/journal/import").param("from", "2021-01-01").param("account", "6943"))
                .andExpect(status().isOk());

        verify(tradeService).importFills(eq("6943"),
                eq(LocalDate.parse("2021-01-01").atStartOfDay(TradeService.MARKET_TIME).toInstant()), any());
    }

    @Test
    void tradesForAWeek() throws Exception {
        when(tradeService.findTrades(null, TradeService.Period.WEEK, LocalDate.parse("2026-10-23")))
                .thenReturn(List.of(closedTrade()));

        web.perform(get("/api/journal/trades").param("period", "WEEK").param("day", "2026-10-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("YMAT"))
                .andExpect(jsonPath("$[0].side").value("LONG"))
                .andExpect(jsonPath("$[0].status").value("CLOSED"))
                .andExpect(jsonPath("$[0].pnl").value(9.99))
                .andExpect(jsonPath("$[0].setup").value("gap and go"))
                .andExpect(jsonPath("$[0].userId").doesNotExist());     // internal fields stay inside
    }

    @Test
    void oneTradeWithItsFills() throws Exception {
        when(tradeService.getTrade(7L)).thenReturn(closedTrade());
        when(tradeService.getFills(7L)).thenReturn(List.of(
                new Fill(101L, 1L, 1L, "6943", "YMAT", Fill.Action.BUY, new BigDecimal("100"),
                        new BigDecimal("2.00"), BigDecimal.ZERO, Instant.parse("2026-10-23T14:30:00Z"))));
        when(tradeService.getShareAdjustments(7L)).thenReturn(List.of());

        web.perform(get("/api/journal/trades/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trade.symbol").value("YMAT"))
                .andExpect(jsonPath("$.fills[0].action").value("BUY"))
                .andExpect(jsonPath("$.fills[0].price").value(2.00))
                .andExpect(jsonPath("$.shareChanges").isEmpty());
    }

    @Test
    void saveNotes() throws Exception {
        when(tradeService.updateNotes(7L, "micro pullback", "good entry")).thenReturn(closedTrade());

        web.perform(put("/api/journal/trades/7/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setup\":\"micro pullback\",\"notes\":\"good entry\"}"))
                .andExpect(status().isOk());

        verify(tradeService).updateNotes(7L, "micro pullback", "good entry");
    }

    @Test
    void unknownTradeGives400WithMessage() throws Exception {
        when(tradeService.getTrade(999L)).thenThrow(new IllegalArgumentException("No trade with id 999"));

        web.perform(get("/api/journal/trades/999"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("No trade with id 999"));
    }

    @Test
    void notLoggedInGives401WithMessage() throws Exception {
        when(tradeService.importFills(any(), any(), any()))
                .thenThrow(new IllegalStateException("Not logged in to Schwab. Open /api/auth/login"));

        web.perform(post("/api/journal/import"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Not logged in to Schwab. Open /api/auth/login"));
    }
}
