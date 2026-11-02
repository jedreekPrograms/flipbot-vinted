package pl.flipbot.playwright.worker;

import pl.flipbot.playwright.util.RuntimeEnvironment;

record CatalogConcurrencyConfig(
        int maxConcurrentCatalogScans,
        long retryDelayMillis
) {

    static final String MAX_CONCURRENT_CATALOG_SCANS_ENV =
            "FLIPBOT_MAX_CONCURRENT_CATALOG_SCANS";

    static final int DEFAULT_MAX_CONCURRENT_CATALOG_SCANS = 2;
    static final long DEFAULT_RETRY_DELAY_MILLIS = 5_000L;

    CatalogConcurrencyConfig {
        if (maxConcurrentCatalogScans < 1) {
            throw new IllegalArgumentException(
                    "maxConcurrentCatalogScans must be at least 1"
            );
        }
        if (retryDelayMillis < 1L) {
            throw new IllegalArgumentException(
                    "retryDelayMillis must be at least 1"
            );
        }
    }

    static CatalogConcurrencyConfig fromEnvironment() {
        return fromRaw(
                System.getenv(MAX_CONCURRENT_CATALOG_SCANS_ENV)
        );
    }

    static CatalogConcurrencyConfig fromRaw(String rawMaxConcurrentScans) {
        return new CatalogConcurrencyConfig(
                RuntimeEnvironment.intValue(MAX_CONCURRENT_CATALOG_SCANS_ENV, rawMaxConcurrentScans,
                        DEFAULT_MAX_CONCURRENT_CATALOG_SCANS, 1, 100),
                DEFAULT_RETRY_DELAY_MILLIS);
    }

}