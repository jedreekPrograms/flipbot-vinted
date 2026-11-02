package pl.flipbot.playwright.worker;

import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

public class ScheduledRealActionConfigTest {
    @Test public void defaultAndLegacyFlagsCannotEnableContinuousSubmission() {
        assertFalse(ScheduledRealActionConfig.parse(Map.of()).realOffersEnabledFor(3L));
        assertFalse(ScheduledRealActionConfig.parse(Map.of(
                "FLIPBOT_REAL_OFFERS_ENABLED", "true",
                "FLIPBOT_REAL_ACTION_CONFIRM", "I_UNDERSTAND_REAL_ACTIONS"
        )).realOffersEnabledFor(3L));
    }

    @Test public void explicitProductionModeSupportsAllRunningBotsAndIndependentActions() {
        var config = ScheduledRealActionConfig.parse(Map.of("FLIPBOT_RUN_MODE", "PRODUCTION",
                "FLIPBOT_REAL_NEXT_STEPS_ENABLED", "false"));
        assertTrue(config.realOffersEnabledFor(3L));
        assertFalse(config.realNextStepsEnabledFor(3L));
        assertFalse(config.realOffersEnabledFor(null));
        assertFalse(config.realOffersEnabledFor(0L));
    }

    @Test public void allowlistRestrictsBothActionTypes() {
        var config = ScheduledRealActionConfig.parse(Map.of("FLIPBOT_RUN_MODE", "PRODUCTION",
                "FLIPBOT_REAL_ACTION_BOT_IDS", "3,7"));
        assertTrue(config.realOffersEnabledFor(3L));
        assertTrue(config.realNextStepsEnabledFor(7L));
        assertFalse(config.realOffersEnabledFor(8L));
    }

    @Test public void invalidModeOrScopeFailsBeforeAnyBrowserAction() {
        assertThrows(IllegalArgumentException.class, () -> ScheduledRealActionConfig.parse(Map.of("FLIPBOT_RUN_MODE", "typo")));
        assertThrows(IllegalArgumentException.class, () -> ScheduledRealActionConfig.parse(Map.of("FLIPBOT_REAL_ACTION_BOT_IDS", "3,")));
        assertThrows(IllegalArgumentException.class, () -> ScheduledRealActionConfig.parse(Map.of("FLIPBOT_REAL_ACTION_BOT_IDS", "0")));
    }
}
