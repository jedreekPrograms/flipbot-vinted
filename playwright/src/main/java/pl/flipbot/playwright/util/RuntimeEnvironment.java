package pl.flipbot.playwright.util;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

/** Shared parsing; callers own each setting's defaults and range. */
@Slf4j
public final class RuntimeEnvironment {
    private RuntimeEnvironment() {}

    public static boolean booleanValue(String name, String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> {
                log.warn("Invalid {}. Using default {}.", name, fallback);
                yield fallback;
            }
        };
    }

    public static boolean booleanValue(String name, boolean fallback) {
        return booleanValue(name, System.getenv(name), fallback);
    }

    public static long longValue(String name, String raw, long fallback, long minimum, long maximum) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long value = Long.parseLong(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException ignored) {
            // Malformed and overflowing values use the same configured fallback.
        }
        log.warn("Invalid {}. Using default {}. Allowed range is {}..{}.", name, fallback, minimum, maximum);
        return fallback;
    }

    public static long longValue(String name, long fallback, long minimum, long maximum) {
        return longValue(name, System.getenv(name), fallback, minimum, maximum);
    }

    public static int intValue(String name, String raw, int fallback, int minimum, int maximum) {
        return (int) longValue(name, raw, fallback, minimum, maximum);
    }

    public static int intValue(String name, int fallback, int minimum, int maximum) {
        return intValue(name, System.getenv(name), fallback, minimum, maximum);
    }

    /** Observer settings clamp valid numbers; worker settings above reject out-of-range values. */
    public static int clampedIntValue(String raw, int fallback, int minimum, int maximum) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static long clampedLongValue(String raw, long fallback, long minimum, long maximum) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Math.max(minimum, Math.min(maximum, Long.parseLong(raw.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
