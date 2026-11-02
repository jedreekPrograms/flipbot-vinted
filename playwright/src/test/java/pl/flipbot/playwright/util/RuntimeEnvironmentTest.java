package pl.flipbot.playwright.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RuntimeEnvironmentTest {
    @Test public void booleanAliasesAndFallbacksStayTheSameAcrossRuntimeSettings() {
        for (String raw : new String[]{"true", "1", " YES ", "y", "On"}) {
            assertEquals(true, RuntimeEnvironment.booleanValue("fixture", raw, false));
        }
        for (String raw : new String[]{"false", "0", " NO ", "n", "Off"}) {
            assertEquals(false, RuntimeEnvironment.booleanValue("fixture", raw, true));
        }
        for (String raw : new String[]{null, "", " ", "unknown"}) {
            assertEquals(true, RuntimeEnvironment.booleanValue("fixture", raw, true));
            assertEquals(false, RuntimeEnvironment.booleanValue("fixture", raw, false));
        }
    }

    @Test public void observerSettingsClampWhileWorkerSettingsRejectOutOfRangeValues() {
        assertEquals(1, RuntimeEnvironment.clampedIntValue("0", 3, 1, 5));
        assertEquals(5, RuntimeEnvironment.clampedIntValue("99", 3, 1, 5));
        assertEquals(3, RuntimeEnvironment.intValue("fixture", "0", 3, 1, 5));
        assertEquals(3, RuntimeEnvironment.intValue("fixture", "99", 3, 1, 5));
        assertEquals(5L, RuntimeEnvironment.clampedLongValue(" 1 ", 15L, 5L, 1440L));
        assertEquals(1440L, RuntimeEnvironment.clampedLongValue("99999", 15L, 5L, 1440L));
    }

    @Test public void clampedIntegerOverflowStillUsesFallbackRatherThanTheMaximum() {
        assertEquals(6, RuntimeEnvironment.clampedIntValue("2147483648", 6, 1, 50));
        assertEquals(50L, RuntimeEnvironment.clampedLongValue("2147483648", 6L, 1L, 50L));
        for (String raw : new String[]{null, "", " ", "invalid", "1.5", "9223372036854775808"}) {
            assertEquals(6, RuntimeEnvironment.clampedIntValue(raw, 6, 1, 50));
            assertEquals(15L, RuntimeEnvironment.clampedLongValue(raw, 15L, 5L, 1440L));
        }
    }

    @Test public void numericLimitsIncludeBothBoundariesAndTrimInput() {
        assertEquals(1, RuntimeEnvironment.intValue("fixture", " 1 ", 3, 1, 5));
        assertEquals(5, RuntimeEnvironment.intValue("fixture", "5", 3, 1, 5));
        assertEquals(Long.MAX_VALUE, RuntimeEnvironment.longValue("fixture", Long.toString(Long.MAX_VALUE),
                10L, 1L, Long.MAX_VALUE));
    }

    @Test public void invalidNumbersUseTheConfiguredFallbackInsteadOfClampingOrOverflowing() {
        for (String raw : new String[]{null, "", " ", "0", "6", "-1", "abc", "1.5", "1e1",
                "2147483648", "9223372036854775808"}) {
            assertEquals(3, RuntimeEnvironment.intValue("fixture", raw, 3, 1, 5));
            assertEquals(3L, RuntimeEnvironment.longValue("fixture", raw, 3L, 1L, 5L));
        }
    }
}
