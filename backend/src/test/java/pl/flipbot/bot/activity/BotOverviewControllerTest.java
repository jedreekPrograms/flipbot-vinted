package pl.flipbot.bot.activity;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import pl.flipbot.bot.Bot;
import pl.flipbot.bot.BotRepository;
import pl.flipbot.bot.activity.dto.BotDailyActivityResponse;
import pl.flipbot.bot.runtime.BotRuntimeStateService;
import pl.flipbot.exception.GlobalExceptionHandler;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BotOverviewControllerTest {
    private BotDailyActivityService activity;
    private BotRepository bots;
    private BotRuntimeStateService runtime;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        activity = mock(BotDailyActivityService.class);
        bots = mock(BotRepository.class);
        runtime = mock(BotRuntimeStateService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BotOverviewController(bots, activity, runtime),
                        new ResourceProbe())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void anAlreadyOpenPanelCanReadTheSameActivityAsTheCurrentOverview() throws Exception {
        Bot bot = mock(Bot.class);
        when(bot.getId()).thenReturn(13L);
        when(bots.findAll()).thenReturn(List.of(bot));
        when(activity.getToday(13L)).thenReturn(new BotDailyActivityResponse(13L,
                LocalDate.of(2026, 10, 17), "Europe/Warsaw", 25, 7, 18, 3, 2, 1, 4, 7, 0, 0));
        String legacyJson = mvc.perform(get("/api/bots/13/activity/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLimitUsed").value(7))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/bots/overview")).andExpect(status().isOk())
                .andExpect(content().json("[{\"botId\":13,\"activity\":" + legacyJson + ",\"runtime\":null}]"));
        verify(activity, times(2)).getToday(13L);
    }

    @Test void legacyReadPreservesMissingBotAndMalformedIdStatuses() throws Exception {
        when(activity.getToday(99L)).thenThrow(new NoSuchElementException("Bot missing"));
        mvc.perform(get("/api/bots/99/activity/today")).andExpect(status().isNotFound());
        mvc.perform(get("/api/bots/invalid/activity/today")).andExpect(status().isBadRequest());
        verify(activity, never()).getToday(null);
    }

    @Test void missingStaticResourcesAre404AndDoNotBecomeUnexpectedServerErrors() throws Exception {
        mvc.perform(get("/test/missing-resource")).andExpect(status().isNotFound())
                .andExpect(content().json("{\"message\":\"Nie znaleziono żądanego zasobu.\"}"));
        verifyNoInteractions(activity, bots, runtime);
    }

    @RestController static class ResourceProbe {
        @GetMapping("/test/missing-resource")
        void missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/missing-resource", "missing.js");
        }
    }
}
