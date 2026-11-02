package pl.flipbot.playwright.worker;

import static pl.flipbot.playwright.util.RuntimeEnvironment.booleanValue;
import static pl.flipbot.playwright.util.RuntimeEnvironment.intValue;
import static pl.flipbot.playwright.util.RuntimeEnvironment.longValue;

public record WorkerRuntimeConfig(
        int workerCount,
        long syncIntervalSeconds,
        long negotiationCheckIntervalSeconds,
        long catalogScanIntervalSeconds,
        long failureDelaySeconds,
        long rateLimitDelaySeconds,
        long browserIdleTimeoutSeconds,
        long shutdownTimeoutSeconds,
        boolean schedulerHeadless
) {

    private static final int DEFAULT_WORKER_COUNT = 10;
    private static final int MAX_WORKER_COUNT = 100;

    private static final long DEFAULT_SYNC_INTERVAL_SECONDS = 1L;
    private static final long DEFAULT_NEGOTIATION_CHECK_INTERVAL_SECONDS = 120L;
    private static final long DEFAULT_CATALOG_SCAN_INTERVAL_SECONDS = 15L * 60L;
    private static final long DEFAULT_FAILURE_DELAY_SECONDS = 60L;
    private static final long DEFAULT_RATE_LIMIT_DELAY_SECONDS = 10L * 60L;
    private static final long DEFAULT_BROWSER_IDLE_TIMEOUT_SECONDS = 180L;
    private static final long DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 30L;
    private static final boolean DEFAULT_SCHEDULER_HEADLESS = true;

    public static WorkerRuntimeConfig fromEnvironment() {

        return new WorkerRuntimeConfig(
                intValue(
                        "FLIPBOT_WORKER_COUNT",
                        DEFAULT_WORKER_COUNT,
                        1,
                        MAX_WORKER_COUNT
                ),
                longValue(
                        "FLIPBOT_SYNC_INTERVAL_SECONDS",
                        DEFAULT_SYNC_INTERVAL_SECONDS,
                        1L,
                        60L
                ),
                longValue(
                        "FLIPBOT_NEGOTIATION_CHECK_INTERVAL_SECONDS",
                        DEFAULT_NEGOTIATION_CHECK_INTERVAL_SECONDS,
                        30L,
                        60L * 60L
                ),
                longValue(
                        "FLIPBOT_CATALOG_SCAN_INTERVAL_SECONDS",
                        DEFAULT_CATALOG_SCAN_INTERVAL_SECONDS,
                        60L,
                        24L * 60L * 60L
                ),
                longValue(
                        "FLIPBOT_FAILURE_RETRY_SECONDS",
                        DEFAULT_FAILURE_DELAY_SECONDS,
                        1L,
                        24L * 60L * 60L
                ),
                longValue(
                        "FLIPBOT_RATE_LIMIT_RETRY_SECONDS",
                        DEFAULT_RATE_LIMIT_DELAY_SECONDS,
                        1L,
                        24L * 60L * 60L
                ),
                longValue(
                        "FLIPBOT_BROWSER_IDLE_TIMEOUT_SECONDS",
                        DEFAULT_BROWSER_IDLE_TIMEOUT_SECONDS,
                        30L,
                        60L * 60L
                ),
                longValue(
                        "FLIPBOT_SHUTDOWN_TIMEOUT_SECONDS",
                        DEFAULT_SHUTDOWN_TIMEOUT_SECONDS,
                        1L,
                        300L
                ),
                booleanValue(
                        "FLIPBOT_SCHEDULER_HEADLESS",
                        DEFAULT_SCHEDULER_HEADLESS
                )
        );
    }

    public long normalDelaySeconds(
            ScheduledJobType jobType
    ) {
        return switch (jobType) {
            case NEGOTIATION_CHECK -> negotiationCheckIntervalSeconds;
            case CATALOG_SCAN -> catalogScanIntervalSeconds;
        };
    }

}
