package pl.flipbot.playwright.filters.brand;

import static pl.flipbot.playwright.util.FailureMessages.summary;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.filters.FilterPage;
import pl.flipbot.playwright.filters.FilterSelectors;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;

@Slf4j
@RequiredArgsConstructor
public final class BrandFilter {
    private static final double FILTER_TIMEOUT_MS = 10_000;

    private static final double OPTION_TIMEOUT_MS = 10_000;

    private static final int BRAND_MAX_ATTEMPTS = 3;

    private static final double BRAND_PRE_CONFIRM_PERSIST_TIMEOUT_MS = 1_000;

    private static final double BRAND_PERSIST_TIMEOUT_MS = 5_000;

    private static final double BRAND_RETRY_DELAY_MS = 2_000;

    private static final double BRAND_PANEL_SETTLE_MS = 400;

    private final Page page;
    private final FilterPage ui;

    public void apply(String brand) {
        String baseUrl = ui.openFilter(FilterSelectors.BRAND_FILTER);
        ui.waitForOption(brand);
        ui.getOptionLocator(brand).click();
        confirmBrandWithRetry(FilterSelectors.FILTER_SELECTION, brand, baseUrl);
    }

    private void confirmBrandWithRetry(String confirmSelector, String brand, String baseUrl) {
        if (!MarketplaceUrls.isCatalogUrl(baseUrl)) {
            throw new IllegalStateException(
                    "Cannot apply brand retry because the pre-brand Vinted catalog URL is missing or unsafe: "
                            + baseUrl
            );
        }

        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= BRAND_MAX_ATTEMPTS; attempt++) {
            try {
                log.info("[FILTER BRAND] Attempt {}/{} for brand '{}'.", attempt, BRAND_MAX_ATTEMPTS, brand);

                if (attempt > 1) {
                    prepareBrandRetry(baseUrl, brand);
                }

                if (waitForBrandFilterPersisted(BRAND_PRE_CONFIRM_PERSIST_TIMEOUT_MS)) {
                    log.info(
                            "[FILTER BRAND] Brand '{}' persisted before confirm on attempt {}/{}. Current URL: {}",
                            brand,
                            attempt,
                            BRAND_MAX_ATTEMPTS,
                            page.url()
                    );
                    return;
                }

                Locator confirmButton = page.locator(confirmSelector);
                ui.waitUntilVisible(confirmButton, OPTION_TIMEOUT_MS);
                confirmButton.click();

                if (waitForBrandFilterPersisted(BRAND_PERSIST_TIMEOUT_MS)) {
                    log.info(
                            "[FILTER BRAND] Brand '{}' persisted successfully on attempt {}/{}. Current URL: {}",
                            brand,
                            attempt,
                            BRAND_MAX_ATTEMPTS,
                            page.url()
                    );
                    return;
                }

                if (attempt < BRAND_MAX_ATTEMPTS) {
                    log.info(
                            "[FILTER BRAND] Attempt {}/{} did not persist '{}' yet. Retrying safely. Current URL: {}",
                            attempt,
                            BRAND_MAX_ATTEMPTS,
                            brand,
                            page.url()
                    );
                } else {
                    log.warn(
                            "[FILTER BRAND] Final attempt {}/{} did not persist '{}'. Current URL: {}",
                            attempt,
                            BRAND_MAX_ATTEMPTS,
                            brand,
                            page.url()
                    );
                }
            } catch (RuntimeException exception) {
                lastException = exception;

                if (attempt < BRAND_MAX_ATTEMPTS) {
                    log.info(
                            "[FILTER BRAND] Attempt {}/{} for '{}' needs retry: {}",
                            attempt,
                            BRAND_MAX_ATTEMPTS,
                            brand,
                            summary(exception)
                    );
                } else {
                    log.warn(
                            "[FILTER BRAND] Final attempt {}/{} for '{}' failed: {}",
                            attempt,
                            BRAND_MAX_ATTEMPTS,
                            brand,
                            summary(exception)
                    );
                }

                log.trace(
                        "[FILTER BRAND] Full brand filter error. Attempt {}/{}.",
                        attempt,
                        BRAND_MAX_ATTEMPTS,
                        exception
                );
            }

            if (attempt < BRAND_MAX_ATTEMPTS) {
                resetToBrandBaseUrl(baseUrl, attempt);
                log.info("[FILTER BRAND] Next attempt in {}ms.", (int) BRAND_RETRY_DELAY_MS);
                page.waitForTimeout(BRAND_RETRY_DELAY_MS);
            }
        }

        String message =
                "Could not persist brand filter '"
                        + brand
                        + "' after "
                        + BRAND_MAX_ATTEMPTS
                        + " attempts. Expected either brand_ids[] or canonical /brand/... URL state. Base URL: "
                        + baseUrl
                        + ". Current URL: "
                        + page.url();

        if (lastException != null) {
            throw new IllegalStateException(message, lastException);
        }

        throw new IllegalStateException(message);
    }

    private void prepareBrandRetry(String baseUrl, String brand) {
        if (!ui.isCatalogPage()) {
            navigateToBrandBaseUrl(baseUrl);
        }

        Locator brandFilter = page.getByTestId(FilterSelectors.BRAND_FILTER);
        ui.waitUntilVisible(brandFilter, FILTER_TIMEOUT_MS);
        brandFilter.click();
        ui.assertStillOnVinted("reopening brand filter for retry");
        page.waitForTimeout(BRAND_PANEL_SETTLE_MS);

        Locator brandOption = ui.getOptionLocator(brand);
        ui.waitUntilVisible(brandOption, OPTION_TIMEOUT_MS);
        brandOption.click();

        log.info("[FILTER BRAND] Brand '{}' selected again for retry. Current URL: {}", brand, page.url());
    }

    private void resetToBrandBaseUrl(String baseUrl, int failedAttempt) {
        log.info(
                "[FILTER BRAND] Resetting catalog to the known-good pre-brand URL after failed attempt {}/{}.",
                failedAttempt,
                BRAND_MAX_ATTEMPTS
        );

        navigateToBrandBaseUrl(baseUrl);

        if (!ui.isCatalogPage()) {
            throw new IllegalStateException(
                    "Brand retry reset did not return to a Vinted catalog page. URL: " + page.url()
            );
        }

        log.info("[FILTER BRAND] Pre-brand catalog state restored. Current URL: {}", page.url());
    }

    private void navigateToBrandBaseUrl(String baseUrl) {
        if (!MarketplaceUrls.isCatalogUrl(baseUrl)) {
            throw new IllegalStateException(
                    "Refusing to use a non-Vinted catalog URL as brand retry base: " + baseUrl
            );
        }
        ui.navigateToSafeVintedUrl(baseUrl);
    }

    public boolean waitForBrandFilterPersisted(double timeoutMilliseconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutMilliseconds;

        while (System.currentTimeMillis() <= deadline) {
            ui.assertStillOnVinted("waiting for brand filter persistence");

            if (hasBrandFilterInUrl()) {
                return true;
            }

            page.waitForTimeout(200);
        }

        return false;
    }

    public boolean hasBrandFilterInUrl() {
        if (!MarketplaceUrls.isVintedUrl(page.url())) {
            return false;
        }

        if (ui.getUrlParameter("brand_ids[]") != null) {
            return true;
        }

        String currentUrl = page.url();
        int queryIndex = currentUrl.indexOf('?');
        String withoutQuery = queryIndex >= 0
                ? currentUrl.substring(0, queryIndex)
                : currentUrl;
        int fragmentIndex = withoutQuery.indexOf('#');

        if (fragmentIndex >= 0) {
            withoutQuery = withoutQuery.substring(0, fragmentIndex);
        }

        return withoutQuery.contains("/brand/");
    }


}
