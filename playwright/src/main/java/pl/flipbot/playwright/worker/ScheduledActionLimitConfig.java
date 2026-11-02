package pl.flipbot.playwright.worker;

import pl.flipbot.playwright.util.RuntimeEnvironment;

public record ScheduledActionLimitConfig(
        int maxRealOffersPerCatalogScan,
        int maxRealNextStepsPerCheck
) {

    static final String MAX_REAL_OFFERS_ENV =
            "FLIPBOT_MAX_REAL_OFFERS_PER_CATALOG_SCAN";

    static final String MAX_REAL_NEXT_STEPS_ENV =
            "FLIPBOT_MAX_REAL_NEXT_STEPS_PER_CHECK";

    private static final int DEFAULT_MAX_REAL_OFFERS_PER_CATALOG_SCAN = 5;
    private static final int DEFAULT_MAX_REAL_NEXT_STEPS_PER_CHECK = 5;
    private static final int MIN_ACTION_LIMIT = 1;
    private static final int MAX_ACTION_LIMIT = 5;

    public static ScheduledActionLimitConfig fromEnvironment() {
        return new ScheduledActionLimitConfig(
                parseLimit(
                        MAX_REAL_OFFERS_ENV,
                        System.getenv(MAX_REAL_OFFERS_ENV),
                        DEFAULT_MAX_REAL_OFFERS_PER_CATALOG_SCAN
                ),
                parseLimit(
                        MAX_REAL_NEXT_STEPS_ENV,
                        System.getenv(MAX_REAL_NEXT_STEPS_ENV),
                        DEFAULT_MAX_REAL_NEXT_STEPS_PER_CHECK
                )
        );
    }

    /**
     * Production uses the configured limits. Dry-run retains the one-action
     * planning limit; final-submit permissions are controlled separately.
     */
    public int effectiveMaxRealOffers(boolean productionModeEnabled) {
        return productionModeEnabled
                ? maxRealOffersPerCatalogScan
                : 1;
    }

    public int effectiveMaxRealNextSteps(boolean productionModeEnabled) {
        return productionModeEnabled
                ? maxRealNextStepsPerCheck
                : 1;
    }

    static int parseLimit(String environmentName, String rawValue, int defaultValue) {
        return RuntimeEnvironment.intValue(environmentName, rawValue, defaultValue,
                MIN_ACTION_LIMIT, MAX_ACTION_LIMIT);
    }

}
