package pl.flipbot.playwright.marketstats;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MarketStatsRuntimeConfigTest {
    @Test
    public void invalidExplicitObserverOverrideStillTakesPrecedenceOverSchedulerSetting() {
        assertTrue(MarketStatsRuntimeConfig.resolveObserverHeadless("unknown", "false"));
        assertFalse(MarketStatsRuntimeConfig.resolveObserverHeadless(" ", "false"));
    }

    @Test
    public void overflowFallbackRemainsDistinctFromClampingForObserverIntervals() {
        assertEquals(6, MarketStatsRuntimeConfig.resolveBrowserRecycleTargetCount("2147483648"));
        assertEquals(1440L, MarketStatsRuntimeConfig.resolveRefreshCooldownMinutes("2147483648"));
        assertEquals(15L, MarketStatsRuntimeConfig.resolveRefreshCooldownMinutes("9223372036854775808"));
    }


    @Test
    public void inheritsVisibleSchedulerWhenObserverOverrideIsMissing() {
        assertFalse(
                MarketStatsRuntimeConfig.resolveObserverHeadless(
                        null,
                        "false"
                )
        );
    }

    @Test
    public void inheritsHeadlessSchedulerWhenObserverOverrideIsMissing() {
        assertTrue(
                MarketStatsRuntimeConfig.resolveObserverHeadless(
                        null,
                        "true"
                )
        );
    }

    @Test
    public void explicitObserverOverrideWinsOverSchedulerSetting() {
        assertTrue(
                MarketStatsRuntimeConfig.resolveObserverHeadless(
                        "true",
                        "false"
                )
        );

        assertFalse(
                MarketStatsRuntimeConfig.resolveObserverHeadless(
                        "false",
                        "true"
                )
        );
    }

    @Test
    public void defaultsToHeadlessWhenNeitherSettingExists() {
        assertTrue(
                MarketStatsRuntimeConfig.resolveObserverHeadless(
                        null,
                        null
                )
        );
    }

    @Test
    public void acceptsCommonBooleanAliases() {
        assertFalse(
                MarketStatsRuntimeConfig.resolveObserverHeadless(
                        null,
                        "off"
                )
        );
        assertTrue(
                MarketStatsRuntimeConfig.resolveObserverHeadless(
                        "yes",
                        "off"
                )
        );
    }

    @Test
    public void defaultsToFifteenMinuteCooldown() {
        assertEquals(
                15L,
                MarketStatsRuntimeConfig.resolveRefreshCooldownMinutes(
                        null
                )
        );
    }

    @Test
    public void explicitMinuteCooldownIsUsed() {
        assertEquals(
                25L,
                MarketStatsRuntimeConfig.resolveRefreshCooldownMinutes(
                        "25"
                )
        );
    }


    @Test
    public void refreshCooldownIsClampedToSafeBounds() {
        assertEquals(
                5L,
                MarketStatsRuntimeConfig.resolveRefreshCooldownMinutes(
                        "1"
                )
        );
        assertEquals(
                1_440L,
                MarketStatsRuntimeConfig.resolveRefreshCooldownMinutes(
                        "99999"
                )
        );
    }

    @Test
    public void marketStatsBrowserDefaultsToSixTargetsBeforeRecycle() {
        assertEquals(
                6,
                MarketStatsRuntimeConfig.resolveBrowserRecycleTargetCount(null)
        );
        assertEquals(
                6,
                MarketStatsRuntimeConfig.resolveBrowserRecycleTargetCount("bad")
        );
    }

    @Test
    public void marketStatsBrowserRecycleCountIsConfigurableAndBounded() {
        assertEquals(
                5,
                MarketStatsRuntimeConfig.resolveBrowserRecycleTargetCount("5")
        );
        assertEquals(
                1,
                MarketStatsRuntimeConfig.resolveBrowserRecycleTargetCount("0")
        );
        assertEquals(
                50,
                MarketStatsRuntimeConfig.resolveBrowserRecycleTargetCount("999")
        );
    }
}
