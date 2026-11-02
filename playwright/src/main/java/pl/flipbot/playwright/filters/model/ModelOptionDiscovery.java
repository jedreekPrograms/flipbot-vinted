package pl.flipbot.playwright.filters.model;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.filters.FilterPage;
import pl.flipbot.playwright.filters.FilterSelectors;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;

import static pl.flipbot.playwright.filters.model.ModelElementReader.containsIgnoreCase;
import static pl.flipbot.playwright.filters.model.ModelElementReader.readCompleteModelOptionTexts;
import static pl.flipbot.playwright.filters.model.ModelElementReader.snapshotOptions;
import static pl.flipbot.playwright.filters.model.ModelElementReader.optionTexts;
import static pl.flipbot.playwright.filters.model.ModelElementReader.safeInnerText;
import static pl.flipbot.playwright.filters.model.ModelElementReader.safeAttribute;
import static pl.flipbot.playwright.filters.model.ModelElementReader.safeCount;
import static pl.flipbot.playwright.filters.model.ModelElementReader.safeIsVisible;
import static pl.flipbot.playwright.filters.model.ModelOptionIdentity.MODEL_TEST_ID_PREFIX;
import static pl.flipbot.playwright.filters.model.ModelOptionIdentity.MODEL_TITLE_TEST_ID_SUFFIX;
import static pl.flipbot.playwright.filters.model.ModelOptionIdentity.canonicalModelRowTestId;
import static pl.flipbot.playwright.filters.model.ModelOptionIdentity.exactVisibleModelLabelMatches;
import static pl.flipbot.playwright.filters.model.ModelOptionIdentity.modelCollectionIdFromTestId;
import static pl.flipbot.playwright.filters.model.ModelOptionIdentity.normalizeOptionText;

@Slf4j
final class ModelOptionDiscovery {
    private static final double FILTER_TIMEOUT_MS = 10_000;

    private static final int MODEL_DISCOVERY_MAX_ATTEMPTS = 3;

    private static final int MODEL_RETRY_PANEL_MAX_ATTEMPTS = 3;

    private static final double MODEL_OPTION_SETTLE_TIMEOUT_MS = 15_000;

    private static final double MODEL_OPTION_POLL_INTERVAL_MS = 250;

    private static final double MODEL_DISCOVERY_RETRY_DELAY_MS = 1_000;

    private static final double MODEL_PANEL_SETTLE_MS = 350;

    private final Page page;
    private final FilterPage ui;
    private final ModelOptionSelection selection;

    ModelOptionDiscovery(Page page, FilterPage ui) {
        this.page = page;
        this.ui = ui;
        this.selection = new ModelOptionSelection(page);
    }

    String select(String baseUrl, String model) {
        ui.ensureVintedBeforeFilterAction("selecting model '" + model + "'");

        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model cannot be blank");
        }

        if (!MarketplaceUrls.isCatalogUrl(baseUrl)) {
            throw new IllegalStateException(
                    "Cannot retry exact model discovery because the pre-model Vinted catalog URL is missing or unsafe: "
                            + baseUrl
            );
        }

        List<String> lastVisiblePartialLabels = List.of();
        int lastVisibleCandidateCount = 0;
        int lastTextMatchedCount = 0;

        for (int discoveryAttempt = 1; discoveryAttempt <= MODEL_DISCOVERY_MAX_ATTEMPTS; discoveryAttempt++) {
            if (discoveryAttempt > 1) {
                prepareModelDiscoveryRetry(baseUrl, model, discoveryAttempt);
            }

            log.info(
                    "[FILTER MODEL] Exact model discovery attempt {}/{} for '{}'.",
                    discoveryAttempt,
                    MODEL_DISCOVERY_MAX_ATTEMPTS,
                    model
            );

            long deadline = System.currentTimeMillis()
                    + (long) MODEL_OPTION_SETTLE_TIMEOUT_MS;
            boolean modelSearchApplied = false;

            while (System.currentTimeMillis() <= deadline) {
                ui.ensureVintedBeforeFilterAction("waiting for exact model '" + model + "'");

                /*
                 * Vinted exposes the model name on a dedicated title element:
                 *
                 * selectable-item-brand_collection-10632--title
                 *   -> "Galaxy S26"
                 *
                 * Use that as the source of truth. Do not infer identity from
                 * the text of the whole row, because "Galaxy S26" is also a
                 * substring of "Galaxy S26 Ultra" / "Galaxy S26+".
                 */
                ExactModelTitleMatch exactTitle = findExactVisibleModelTitle(model);

                lastTextMatchedCount = exactTitle.textMatchedCount();
                lastVisibleCandidateCount = exactTitle.visibleTitleCount();
                lastVisiblePartialLabels = exactTitle.visiblePartialLabels();

                if (exactTitle.titleLocator() != null) {
                    Locator titleLocator = exactTitle.titleLocator();
                    String evidenceTestId = titleLocator.getAttribute("data-testid");
                    String collectionId = modelCollectionIdFromTestId(evidenceTestId);

                    if (collectionId == null) {
                        throw new IllegalStateException(
                                "Exact model evidence for '"
                                        + model
                                        + "' has an unexpected data-testid='"
                                        + evidenceTestId
                                        + "'. Refusing to click because the Vinted collection id could not be derived."
                        );
                    }

                    Locator modelRow = canonicalModelRow(collectionId, titleLocator);

                    log.info(
                            "[FILTER MODEL] EXACT model identity matched. requested='{}', evidenceTestId='{}', collectionId='{}', canonicalRowTestId='{}'.",
                            model,
                            evidenceTestId,
                            collectionId,
                            safeAttribute(modelRow, "data-testid")
                    );

                    // A DOM snapshot is evidence for discovery, not permission to click a re-rendered row.
                    if (!safeIsVisible(titleLocator) || !evidenceTestId.equals(safeAttribute(titleLocator, "data-testid"))
                            || !currentEvidenceMatches(titleLocator, evidenceTestId, model)) {
                        continue;
                    }
                    selection.select(modelRow, model, collectionId);

                    ui.assertStillOnVinted("selecting exact model '" + model + "'");
                    return collectionId;
                }

                if (tryApplyOptionalModelSearch(model)) {
                    if (!modelSearchApplied) {
                        modelSearchApplied = true;
                        log.info(
                                "[FILTER MODEL] Optional Vinted model-search input narrowed the native list to '{}'. Exact row identity is still required before any click.",
                                model
                        );
                        page.waitForTimeout(MODEL_PANEL_SETTLE_MS);
                        continue;
                    }
                }

                page.waitForTimeout(MODEL_OPTION_POLL_INTERVAL_MS);
            }

            if (discoveryAttempt < MODEL_DISCOVERY_MAX_ATTEMPTS) {
                log.warn(
                        "[FILTER MODEL] Exact option '{}' did not become provable within {}s on discovery attempt {}/{}. modelSearchApplied={}, hasText candidates={}, visible candidates inspected={}, visible partial/full labels={}. Resetting to the known-good pre-model catalog state and retrying the native Vinted UI.",
                        model,
                        Math.round(MODEL_OPTION_SETTLE_TIMEOUT_MS / 1_000),
                        discoveryAttempt,
                        MODEL_DISCOVERY_MAX_ATTEMPTS,
                        modelSearchApplied,
                        lastTextMatchedCount,
                        lastVisibleCandidateCount,
                        lastVisiblePartialLabels
                );
                page.waitForTimeout(MODEL_DISCOVERY_RETRY_DELAY_MS);
            }
        }

        throw new IllegalStateException(
                "Could not prove an exact visible Vinted model option for '"
                        + model
                        + "' after "
                        + MODEL_DISCOVERY_MAX_ATTEMPTS
                        + " native-UI discovery attempts of "
                        + Math.round(MODEL_OPTION_SETTLE_TIMEOUT_MS / 1_000)
                        + " seconds each. hasText candidates="
                        + lastTextMatchedCount
                        + ", visible candidates inspected="
                        + lastVisibleCandidateCount
                        + ", visible partial/full labels="
                        + lastVisiblePartialLabels
                        + ". Failing closed instead of clicking a similar model."
        );
    }

    private void prepareModelDiscoveryRetry(String baseUrl, String model, int attempt) {
        log.info(
                "[FILTER MODEL] Preparing exact model discovery retry {}/{} for '{}'. Resetting to pre-model URL: {}",
                attempt,
                MODEL_DISCOVERY_MAX_ATTEMPTS,
                model,
                baseUrl
        );

        RuntimeException lastFailure = null;

        for (int panelAttempt = 1; panelAttempt <= MODEL_RETRY_PANEL_MAX_ATTEMPTS; panelAttempt++) {
            try {
                /*
                 * Always start a panel retry from the same proven pre-model
                 * catalog URL. A failed Vinted drawer render can leave an
                 * invisible/stale model panel in the DOM; clicking that state
                 * again is less reliable than rebuilding it from the catalog.
                 */
                ui.navigateToSafeVintedUrl(baseUrl);

                if (!ui.isCatalogPage()) {
                    throw new IllegalStateException(
                            "Exact model discovery retry did not return to a Vinted catalog page. URL: "
                                    + page.url()
                    );
                }

                Locator modelFilter = page.getByTestId(FilterSelectors.MODEL_FILTER);
                ui.waitUntilVisible(modelFilter, FILTER_TIMEOUT_MS);
                modelFilter.click();
                ui.assertStillOnVinted("reopening model filter for discovery retry");
                page.waitForTimeout(MODEL_PANEL_SETTLE_MS);

                log.info(
                        "[FILTER MODEL] Exact model discovery retry {}/{} reopened the native model drawer for '{}' on panel-open attempt {}/{}. The drawer search input is optional; exact visible row identity will be checked first. Current URL: {}",
                        attempt,
                        MODEL_DISCOVERY_MAX_ATTEMPTS,
                        model,
                        panelAttempt,
                        MODEL_RETRY_PANEL_MAX_ATTEMPTS,
                        page.url()
                );
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;

                if (panelAttempt >= MODEL_RETRY_PANEL_MAX_ATTEMPTS) {
                    break;
                }

                log.warn(
                        "[FILTER MODEL] Retry {}/{} for '{}' could not render a usable native model drawer on panel-open attempt {}/{}. Rebuilding the catalog/filter state and trying again. reason={}",
                        attempt,
                        MODEL_DISCOVERY_MAX_ATTEMPTS,
                        model,
                        panelAttempt,
                        MODEL_RETRY_PANEL_MAX_ATTEMPTS,
                        exception.getMessage()
                );

                page.waitForTimeout(MODEL_DISCOVERY_RETRY_DELAY_MS);
            }
        }

        throw new IllegalStateException(
                "Could not reopen the native Vinted model drawer for '"
                        + model
                        + "' while preparing exact-model discovery retry "
                        + attempt
                        + "/"
                        + MODEL_DISCOVERY_MAX_ATTEMPTS
                        + " after "
                        + MODEL_RETRY_PANEL_MAX_ATTEMPTS
                        + " panel attempts.",
                lastFailure
        );
    }

    private boolean tryApplyOptionalModelSearch(String model) {
        Locator inputs = page.locator(FilterSelectors.MODEL_SEARCH_INPUT);
        int count = safeCount(inputs);

        for (int index = 0; index < count; index++) {
            Locator input = inputs.nth(index);

            if (!safeIsVisible(input)) {
                continue;
            }

            try {
                String currentValue = input.inputValue();

                if (!model.equals(currentValue)) {
                    input.fill(model);
                }

                return true;
            } catch (RuntimeException exception) {
                log.debug(
                        "[FILTER MODEL] Visible model-search input could not be used for '{}'. Exact visible-row discovery will continue without depending on it.",
                        model,
                        exception
                );
            }
        }

        return false;
    }

    private ExactModelTitleMatch findExactVisibleModelTitle(String requestedModel) {
        String selector =
                "[data-testid^='" + MODEL_TEST_ID_PREFIX
                        + "'][data-testid$='" + MODEL_TITLE_TEST_ID_SUFFIX + "']";

        Locator titles = page.locator(selector);
        int visibleCount = 0;
        int textMatchedCount = 0;
        Set<String> partialLabels = new LinkedHashSet<>();
        String normalizedRequested = normalizeOptionText(requestedModel);

        for (Map<String, Object> snapshot : snapshotOptions(titles, true)) {
            Locator title = titles.nth(((Number) snapshot.get("index")).intValue());

            if (!safeIsVisible(title)) {
                continue;
            }

            visibleCount++;

            String rawText = ((List<?>) snapshot.get("texts")).getFirst().toString();
            String normalizedVisible = normalizeOptionText(rawText);

            if (normalizedVisible.equalsIgnoreCase(normalizedRequested)
                    && snapshot.get("testId").equals(safeAttribute(title, "data-testid"))) {
                textMatchedCount++;
                return new ExactModelTitleMatch(
                        title,
                        textMatchedCount,
                        visibleCount,
                        List.copyOf(partialLabels)
                );
            }

            if (containsIgnoreCase(normalizedVisible, normalizedRequested)) {
                partialLabels.add(normalizedVisible);
            }
        }

        Locator canonicalRows = page.locator("[role='button'][data-testid^='" + MODEL_TEST_ID_PREFIX + "']");
        for (Map<String, Object> snapshot : snapshotOptions(canonicalRows, false)) {
            Locator row = canonicalRows.nth(((Number) snapshot.get("index")).intValue());

            if (!safeIsVisible(row)) {
                continue;
            }

            String rowTestId = snapshot.get("testId").toString();
            if (modelCollectionIdFromTestId(rowTestId) == null) {
                continue;
            }

            visibleCount++;

            for (String candidateText : optionTexts(snapshot, row)) {
                String normalizedCandidate = normalizeOptionText(candidateText);

                if (exactVisibleModelLabelMatches(requestedModel, candidateText)
                        && rowTestId.equals(safeAttribute(row, "data-testid"))) {
                    textMatchedCount++;
                    return new ExactModelTitleMatch(
                            row,
                            textMatchedCount,
                            visibleCount,
                            List.copyOf(partialLabels)
                    );
                }

                if (containsIgnoreCase(normalizedCandidate, normalizedRequested)) {
                    partialLabels.add(normalizedCandidate);
                }
            }
        }

        return new ExactModelTitleMatch(null, textMatchedCount, visibleCount, List.copyOf(partialLabels));
    }

    private boolean currentEvidenceMatches(Locator evidence, String testId, String model) {
        if (testId.endsWith(MODEL_TITLE_TEST_ID_SUFFIX)) {
            return normalizeOptionText(safeInnerText(evidence)).equalsIgnoreCase(normalizeOptionText(model));
        }
        return readCompleteModelOptionTexts(evidence).stream().anyMatch(text -> exactVisibleModelLabelMatches(model, text));
    }

    private record ExactModelTitleMatch(
            Locator titleLocator,
            int textMatchedCount,
            int visibleTitleCount,
            List<String> visiblePartialLabels
    ) {
    }

    private Locator canonicalModelRow(String collectionId, Locator evidenceLocator) {
        String canonicalTestId = canonicalModelRowTestId(collectionId);

        /*
         * Reuse the matching canonical row when discovery supplied one. A
         * page-wide .first() can point at a stale/responsive duplicate.
         */
        if (canonicalTestId.equals(safeAttribute(evidenceLocator, "data-testid"))
                && safeIsVisible(evidenceLocator)) {
            return evidenceLocator;
        }

        Locator canonicalRows = page.getByTestId(canonicalTestId);
        int rowCount = safeCount(canonicalRows);
        for (int index = 0; index < rowCount; index++) {
            Locator candidate = canonicalRows.nth(index);
            if (safeIsVisible(candidate)) {
                return candidate;
            }
        }

        /*
         * Compatibility fallback for a future Vinted variant where exact text
         * evidence comes from a child instead of the canonical row itself.
         */
        Locator ancestorCandidate = evidenceLocator.locator(
                "xpath=ancestor::*[@role='button' and @data-testid='" + canonicalTestId + "'][1]"
        );

        if (safeCount(ancestorCandidate) > 0 && safeIsVisible(ancestorCandidate.first())) {
            return ancestorCandidate.first();
        }

        return evidenceLocator;
    }
}
