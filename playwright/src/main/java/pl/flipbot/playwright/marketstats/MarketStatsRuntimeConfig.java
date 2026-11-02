package pl.flipbot.playwright.marketstats;

import lombok.extern.slf4j.Slf4j;

import static pl.flipbot.playwright.util.RuntimeEnvironment.booleanValue;
import static pl.flipbot.playwright.util.RuntimeEnvironment.clampedIntValue;
import static pl.flipbot.playwright.util.RuntimeEnvironment.clampedLongValue;

@Slf4j
public record MarketStatsRuntimeConfig(
        boolean enabled,
        boolean headless,
        long refreshCooldownMinutes,
        int maxListingsPerModel,
        int knownBoundarySize,
        long interModelDelayMillis,
        int browserRecycleTargetCount
) {

    private static final String ENABLED_ENV =
            "FLIPBOT_MARKET_STATS_ENABLED";
    private static final String HEADLESS_ENV =
            "FLIPBOT_MARKET_STATS_HEADLESS";
    private static final String SCHEDULER_HEADLESS_ENV =
            "FLIPBOT_SCHEDULER_HEADLESS";
    private static final String REFRESH_MINUTES_ENV =
            "FLIPBOT_MARKET_STATS_REFRESH_MINUTES";
    private static final String MAX_LISTINGS_ENV =
            "FLIPBOT_MARKET_STATS_MAX_LISTINGS_PER_MODEL";
    private static final String KNOWN_BOUNDARY_ENV =
            "FLIPBOT_MARKET_STATS_KNOWN_BOUNDARY";
    private static final String INTER_MODEL_DELAY_ENV =
            "FLIPBOT_MARKET_STATS_INTER_MODEL_DELAY_MS";
    private static final String BROWSER_RECYCLE_TARGETS_ENV =
            "FLIPBOT_MARKET_STATS_BROWSER_RECYCLE_TARGETS";

    private static final long DEFAULT_REFRESH_COOLDOWN_MINUTES = 15L;
    private static final long MIN_REFRESH_COOLDOWN_MINUTES = 5L;
    private static final long MAX_REFRESH_COOLDOWN_MINUTES = 1_440L;
    private static final int DEFAULT_BROWSER_RECYCLE_TARGETS = 6;

    public static MarketStatsRuntimeConfig fromEnvironment() {
        String explicitObserverHeadless = System.getenv(HEADLESS_ENV);
        String schedulerHeadless = System.getenv(SCHEDULER_HEADLESS_ENV);
        String explicitRefreshMinutes = System.getenv(REFRESH_MINUTES_ENV);

        MarketStatsRuntimeConfig config =
                new MarketStatsRuntimeConfig(
                        booleanValue(ENABLED_ENV, true),
                        resolveObserverHeadless(
                                explicitObserverHeadless,
                                schedulerHeadless
                        ),
                        resolveRefreshCooldownMinutes(
                                explicitRefreshMinutes
                        ),
                        clampedIntValue(System.getenv(MAX_LISTINGS_ENV), 600, 50, 5_000),
                        clampedIntValue(System.getenv(KNOWN_BOUNDARY_ENV), 20, 5, 100),
                        clampedLongValue(System.getenv(INTER_MODEL_DELAY_ENV), 2_500L, 0L, 60_000L),
                        resolveBrowserRecycleTargetCount(
                                System.getenv(BROWSER_RECYCLE_TARGETS_ENV)
                        )
                );

        String headlessSource = hasText(explicitObserverHeadless)
                ? HEADLESS_ENV + " (explicit observer override)"
                : hasText(schedulerHeadless)
                ? SCHEDULER_HEADLESS_ENV + " (inherited from normal workers)"
                : "default=true";

        String refreshSource = hasText(explicitRefreshMinutes)
                ? REFRESH_MINUTES_ENV
                : "default=" + DEFAULT_REFRESH_COOLDOWN_MINUTES + "m";

        log.info(
                "[MARKET STATS CONFIG] enabled={}, observer=frontend-managed, headless={} [{}], "
                        + "refreshCooldown={}m [{}], maxListingsPerModel={}, knownBoundary={}, interModelDelay={}ms, browserRecycleTargets={}.",
                config.enabled(),
                config.headless(),
                headlessSource,
                config.refreshCooldownMinutes(),
                refreshSource,
                config.maxListingsPerModel(),
                config.knownBoundarySize(),
                config.interModelDelayMillis(),
                config.browserRecycleTargetCount()
        );

        return config;
    }

    static boolean resolveObserverHeadless(
            String explicitObserverHeadless,
            String schedulerHeadless
    ) {
        if (hasText(explicitObserverHeadless)) {
            return booleanValue(
                    HEADLESS_ENV,
                    explicitObserverHeadless,
                    true
            );
        }

        if (hasText(schedulerHeadless)) {
            return booleanValue(
                    SCHEDULER_HEADLESS_ENV,
                    schedulerHeadless,
                    true
            );
        }

        return true;
    }

    static long resolveRefreshCooldownMinutes(String explicitMinutes) {
        return clampedLongValue(explicitMinutes, DEFAULT_REFRESH_COOLDOWN_MINUTES,
                MIN_REFRESH_COOLDOWN_MINUTES, MAX_REFRESH_COOLDOWN_MINUTES);
    }
    static int resolveBrowserRecycleTargetCount(String raw) {
        return clampedIntValue(
                raw,
                DEFAULT_BROWSER_RECYCLE_TARGETS,
                1,
                50
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
