package pl.flipbot.playwright.browser;

import pl.flipbot.playwright.util.RuntimeEnvironment;

/** The opt-in service-worker block applies only to headless account contexts. */
final class BrowserResourceOptimizationConfig {
    static final String BLOCK_SERVICE_WORKERS_ENV = "FLIPBOT_BROWSER_BLOCK_SERVICE_WORKERS";
    private BrowserResourceOptimizationConfig() {}

    static boolean blockServiceWorkers(boolean headless) {
        return blockServiceWorkers(headless, System.getenv(BLOCK_SERVICE_WORKERS_ENV));
    }

    static boolean blockServiceWorkers(boolean headless, String rawOverride) {
        return headless && RuntimeEnvironment.booleanValue(BLOCK_SERVICE_WORKERS_ENV, rawOverride, false);
    }
}
