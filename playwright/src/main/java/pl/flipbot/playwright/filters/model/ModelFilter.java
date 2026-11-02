package pl.flipbot.playwright.filters.model;

import static pl.flipbot.playwright.util.FailureMessages.summary;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.filters.FilterPage;
import pl.flipbot.playwright.filters.FilterSelectors;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;

@Slf4j
public final class ModelFilter {
    private static final double FILTER_TIMEOUT_MS = 10_000;

    private static final double OPTION_TIMEOUT_MS = 10_000;

    private static final int MODEL_MAX_UI_ATTEMPTS = 3;

    private static final double MODEL_PERSIST_TIMEOUT_MS = 5_000;

    private static final double MODEL_RETRY_DELAY_MS = 1_000;

    private static final double MODEL_PANEL_SETTLE_MS = 350;

    private final Page page;
    private final FilterPage ui;
    private final ModelOptionDiscovery discovery;
    private String verifiedModelCollectionId;

    public ModelFilter(Page page, FilterPage ui) {
        this.page = page;
        this.ui = ui;
        this.discovery = new ModelOptionDiscovery(page, ui);
    }

    public void apply(String model) {
        String baseUrl = ui.openFilter(FilterSelectors.MODEL_FILTER);
        verifiedModelCollectionId = null;
        log.info("[FILTER] Searching Vinted model option for '{}'.", model);
        String collectionId = discovery.select(baseUrl, model);
        log.info("[FILTER] Vinted model option clicked for '{}'.", model);
        ui.ensureVintedBeforeFilterAction("confirming filter selection");
        confirmExactModelWithRetry(baseUrl, model, collectionId);
    }

    private void confirmExactModelWithRetry(String baseUrl, String model, String expectedModelCollectionId) {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "Model filter confirmation was requested without the exact requested model label."
            );
        }

        if (expectedModelCollectionId == null || expectedModelCollectionId.isBlank()) {
            throw new IllegalStateException(
                    "Model filter confirmation was requested without a verified exact model collection id."
            );
        }

        if (!MarketplaceUrls.isCatalogUrl(baseUrl)) {
            throw new IllegalStateException(
                    "Cannot retry exact model filter because the pre-model Vinted catalog URL is missing or unsafe: "
                            + baseUrl
            );
        }

        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= MODEL_MAX_UI_ATTEMPTS; attempt++) {
            try {
                if (attempt > 1) {
                    prepareExactModelRetry(baseUrl, model, expectedModelCollectionId, attempt);
                }

                log.info(
                        "[FILTER MODEL] Confirm attempt {}/{} for exact model '{}' / collectionId={}.",
                        attempt,
                        MODEL_MAX_UI_ATTEMPTS,
                        model,
                        expectedModelCollectionId
                );

                Locator button = page.getByTestId("filter-selection-button");
                ui.waitUntilVisible(button, OPTION_TIMEOUT_MS);
                button.click();
                ui.assertStillOnVinted("confirming exact model selection");

                if (ui.waitForUrlParameterValue(
                        "brand_collection_ids[]",
                        expectedModelCollectionId,
                        MODEL_PERSIST_TIMEOUT_MS
                )) {
                    verifiedModelCollectionId = expectedModelCollectionId;
                    log.info(
                            "[FILTER MODEL] EXACT model persistence verified end-to-end on attempt {}/{}. brand_collection_ids[]={}. Current URL: {}",
                            attempt,
                            MODEL_MAX_UI_ATTEMPTS,
                            expectedModelCollectionId,
                            page.url()
                    );
                    return;
                }

                log.warn(
                        "[FILTER MODEL] Exact row was proven and clicked, but Vinted dropped collectionId={} after confirm attempt {}/{}. Current URL: {}. Retrying without changing target identity.",
                        expectedModelCollectionId,
                        attempt,
                        MODEL_MAX_UI_ATTEMPTS,
                        page.url()
                );
            } catch (RuntimeException exception) {
                lastException = exception;
                log.warn(
                        "[FILTER MODEL] Exact model confirm attempt {}/{} needs retry. model='{}', collectionId={}, reason={}",
                        attempt,
                        MODEL_MAX_UI_ATTEMPTS,
                        model,
                        expectedModelCollectionId,
                        summary(exception)
                );
                log.trace(
                        "[FILTER MODEL] Full exact-model retry error. Attempt {}/{}.",
                        attempt,
                        MODEL_MAX_UI_ATTEMPTS,
                        exception
                );
            }

            if (attempt < MODEL_MAX_UI_ATTEMPTS) {
                resetToModelBaseUrl(baseUrl, attempt);
                page.waitForTimeout(MODEL_RETRY_DELAY_MS);
            }
        }

        /*
         * The collection id below is not guessed and does not come from text
         * search. It was parsed from the data-testid of a selectable row whose
         * COMPLETE visible label independently proved the exact requested
         * model. If Vinted's confirmation UI repeatedly loses that state, the
         * same proven id can safely be restored in the catalog URL. We still
         * verify the final URL and fail closed if it does not persist.
         */
        String recoveryUrl = appendExactModelCollectionId(baseUrl, expectedModelCollectionId);

        log.warn(
                "[FILTER MODEL] Vinted UI failed to persist exact model '{}' after {} attempts. Applying fail-safe exact-ID URL recovery using previously proven collectionId={}: {}",
                model,
                MODEL_MAX_UI_ATTEMPTS,
                expectedModelCollectionId,
                recoveryUrl
        );

        try {
            ui.navigateToSafeVintedUrl(recoveryUrl);

            if (ui.waitForUrlParameterValue(
                    "brand_collection_ids[]",
                    expectedModelCollectionId,
                    MODEL_PERSIST_TIMEOUT_MS
            )) {
                verifiedModelCollectionId = expectedModelCollectionId;
                log.info(
                        "[FILTER MODEL] EXACT model persistence recovered with the previously proven collection id. brand_collection_ids[]={}. Current URL: {}",
                        expectedModelCollectionId,
                        page.url()
                );
                return;
            }
        } catch (RuntimeException recoveryFailure) {
            if (lastException != null) {
                recoveryFailure.addSuppressed(lastException);
            }
            throw recoveryFailure;
        }

        String message =
                "Vinted did not persist the exact proven model collection id after "
                        + MODEL_MAX_UI_ATTEMPTS
                        + " UI attempts and exact-ID recovery. Model='"
                        + model
                        + "', expected brand_collection_ids[]="
                        + expectedModelCollectionId
                        + ", actual="
                        + ui.getUrlParameter("brand_collection_ids[]")
                        + ", URL="
                        + page.url();

        if (lastException != null) {
            throw new IllegalStateException(message, lastException);
        }

        throw new IllegalStateException(message);
    }

    private void prepareExactModelRetry(
            String baseUrl,
            String model,
            String expectedModelCollectionId,
            int attempt
    ) {
        if (!MarketplaceUrls.isCatalogUrl(page.url())) {
            ui.navigateToSafeVintedUrl(baseUrl);
        }

        Locator modelFilter = page.getByTestId(FilterSelectors.MODEL_FILTER);
        ui.waitUntilVisible(modelFilter, FILTER_TIMEOUT_MS);
        modelFilter.click();
        ui.assertStillOnVinted("reopening model filter for retry");
        page.waitForTimeout(MODEL_PANEL_SETTLE_MS);

        String selectedModelCollectionId = discovery.select(baseUrl, model);

        if (!expectedModelCollectionId.equals(selectedModelCollectionId)) {
            throw new IllegalStateException(
                    "Exact model retry resolved a different Vinted collection id. Expected "
                            + expectedModelCollectionId
                            + ", actual "
                            + selectedModelCollectionId
                            + ". Failing closed."
            );
        }

        log.info(
                "[FILTER MODEL] Exact model '{}' selected again for persistence retry {}/{}. collectionId={}.",
                model,
                attempt,
                MODEL_MAX_UI_ATTEMPTS,
                expectedModelCollectionId
        );
    }

    private void resetToModelBaseUrl(String baseUrl, int failedAttempt) {
        log.info(
                "[FILTER MODEL] Resetting catalog to the known-good pre-model URL after failed confirm attempt {}/{}.",
                failedAttempt,
                MODEL_MAX_UI_ATTEMPTS
        );

        ui.navigateToSafeVintedUrl(baseUrl);

        if (!ui.isCatalogPage()) {
            throw new IllegalStateException(
                    "Model retry reset did not return to a Vinted catalog page. URL: "
                            + page.url()
            );
        }

    }

    private String appendExactModelCollectionId(String baseUrl, String expectedModelCollectionId) {
        if (!MarketplaceUrls.isCatalogUrl(baseUrl)) {
            throw new IllegalArgumentException(
                    "Refusing exact model URL recovery on a non-catalog URL: " + baseUrl
            );
        }

        String withoutFragment = baseUrl;
        String fragment = "";
        int fragmentIndex = baseUrl.indexOf('#');

        if (fragmentIndex >= 0) {
            withoutFragment = baseUrl.substring(0, fragmentIndex);
            fragment = baseUrl.substring(fragmentIndex);
        }

        String separator = withoutFragment.contains("?") ? "&" : "?";
        return withoutFragment
                + separator
                + "brand_collection_ids[]="
                + expectedModelCollectionId
                + fragment;
    }

    public void requireVerifiedModelStillPersisted(String model) {
        if (verifiedModelCollectionId == null || verifiedModelCollectionId.isBlank()) {
            throw new IllegalStateException(
                    "No exact Vinted collection id was verified for model '" + model + "'."
            );
        }

        String actualCollectionId = ui.getUrlParameter("brand_collection_ids[]");
        if (!verifiedModelCollectionId.equals(actualCollectionId)) {
            throw new IllegalStateException(
                    "Final Vinted catalog URL no longer contains the exact proven model collection id. Model='"
                            + model
                            + "', expected brand_collection_ids[]="
                            + verifiedModelCollectionId
                            + ", actual="
                            + actualCollectionId
                            + ", URL="
                            + page.url()
            );
        }

        log.info(
                "[FILTER MODEL] Final exact model guard passed. model='{}', brand_collection_ids[]={}.",
                model,
                verifiedModelCollectionId
        );
    }


}
