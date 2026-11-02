package pl.flipbot.playwright.marketstats;

import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketstats.dto.KnownMarketListingIdsDto;
import pl.flipbot.playwright.marketstats.dto.MarketObservationBatchResponseDto;
import pl.flipbot.playwright.marketstats.dto.MarketStatsTargetDto;
import pl.flipbot.playwright.model.BotDetailsDto;

@Slf4j
public final class MarketStatsCollector {
    private final MarketStatsRuntimeConfig config;
    private final MarketStatsApiClient apiClient;
    private final MarketObserverPage observer;
    private final MarketTargetFilters targetFilters;
    private final MarketCatalogScan catalog;

    public MarketStatsCollector(MarketStatsRuntimeConfig config, MarketStatsApiClient apiClient) {
        this.config = config;
        this.apiClient = apiClient;
        this.observer = new MarketObserverPage();
        this.targetFilters = new MarketTargetFilters(observer);
        this.catalog = new MarketCatalogScan(config, observer);
    }

    public void collectOnce() {
        BotDetailsDto observerBot = apiClient.getObserverBot();
        List<MarketStatsTargetDto> targets = apiClient.getTargets();

        log.info(
                "[MARKET STATS] Starting daily collection. observerBot={}, targets={}.",
                observerBot.getId(),
                targets.size()
        );

        int recordedTargets = 0;
        int fallbackTargets = 0;
        int failedTargets = 0;
        int incompleteTargets = 0;

        try (BrowserManager browserManager = new BrowserManager(config.headless());
             BotContext context = new BotContext(observerBot, browserManager)) {
            boolean authenticatedObserverSession = observer.openCatalogSession(context, observerBot);

            try {
                for (int index = 0; index < targets.size(); index++) {
                    MarketStatsTargetDto target = targets.get(index);

                    try {
                        TargetResult result = collectTarget(context, observerBot, target);
                        recordedTargets++;

                        if (result.usedFallback()) {
                            fallbackTargets++;
                        }
                        if (!result.complete()) {
                            incompleteTargets++;
                        }
                    } catch (Exception exception) {
                        if (MarketStatsTrafficBackoff.isRequired(exception)) {
                            log.warn(
                                    "[MARKET STATS] Vinted requested traffic backoff while scanning modelId={} {} / {}. Aborting the whole observer pass immediately instead of continuing through more models.",
                                    target == null ? null : target.modelId(),
                                    target == null ? null : target.brandName(),
                                    target == null ? null : target.modelName()
                            );

                            if (exception instanceof RuntimeException runtimeException) {
                                throw runtimeException;
                            }

                            throw new IllegalStateException(exception);
                        }

                        failedTargets++;
                        log.error(
                                "[MARKET STATS] Model scan failed. modelId={}, brand='{}', model='{}'. Continuing with the next model. This single target will be retried on the next full observer pass; it will not block later models in the queue.",
                                target == null ? null : target.modelId(),
                                target == null ? null : target.brandName(),
                                target == null ? null : target.modelName(),
                                exception
                        );
                    }

                    if (index + 1 < targets.size() && config.interModelDelayMillis() > 0) {
                        context.getPage().waitForTimeout(config.interModelDelayMillis());
                    }
                }
            } finally {
                if (authenticatedObserverSession) {
                    try {
                        context.saveSession();
                    } catch (Exception exception) {
                        log.warn(
                                "[MARKET STATS] Could not save observer session after daily scan.",
                                exception
                        );
                    }
                } else {
                    log.info(
                            "[MARKET STATS] Observer collection ran without an authenticated session. Skipping session save so anonymous storage state cannot replace the saved observer session."
                    );
                }
            }
        }

        log.info(
                "[MARKET STATS] Daily collection pass finished. targets={}, recorded={}, incomplete={}, fallback={}, failed={}.",
                targets.size(), recordedTargets, incompleteTargets, fallbackTargets, failedTargets
        );

        if (failedTargets > 0) {
            log.warn(
                    "[MARKET STATS] Browser batch finished with {} failed target(s) out of {}. Later targets are allowed to continue; failed targets will be retried on the next full observer pass.",
                    failedTargets,
                    targets.size()
            );
        }
        if (incompleteTargets > 0) {
            log.warn(
                    "[MARKET STATS] Stored partial results for {} target(s). Catalog/publication coverage is still incomplete; recorded rows do not confirm complete statistics. These targets remain eligible for a full retry.",
                    incompleteTargets
            );
        }
        if (failedTargets == 0 && incompleteTargets == 0) {
            log.info("[MARKET STATS] Browser batch finished with complete scans for all {} target(s).", targets.size());
        }
    }

    private TargetResult collectTarget(
            BotContext context,
            BotDetailsDto observerBot,
            MarketStatsTargetDto target
    ) {
        MarketTargetFilters.validateTarget(target);

        try {
            KnownMarketListingIdsDto knownState = apiClient.getKnownListingIds(target.modelId());

            Set<String> knownListingIds = knownState.listingIds() == null
                    ? Set.of()
                    : Set.copyOf(knownState.listingIds());

            MarketTargetFilters.PreparedScan preparedScan = targetFilters.prepareScan(context, observerBot, target);

            boolean trustVintedModelFilter = preparedScan.trustsExactModelFilter();

            MarketCatalogScan.ScanResult scanResult = catalog.scan(
                    context,
                    target.modelId(),
                    preparedScan.scanBot().getConfiguration(),
                    preparedScan.accessoryFiltering(),
                    trustVintedModelFilter,
                    knownListingIds,
                    knownState.baselineComplete()
            );

            MarketObservationBatchResponseDto recorded = apiClient.recordObservations(
                    target.modelId(),
                    scanResult.listingIds(),
                    scanResult.listingPrices(),
                    scanResult.complete()
            );

            log.info(
                    "[MARKET STATS] Model scan recorded. modelId={}, brand='{}', model='{}', strategy={}, trustedVintedFilter={}, minPrice={}, maxPrice={}, matched={}, knownBefore={}, newObserved={}, complete={}, baselineMode={}.",
                    target.modelId(), target.brandName(), target.modelName(), preparedScan.strategy(),
                    trustVintedModelFilter, target.minPrice(), target.maxPrice(),
                    scanResult.listingIds().size(), knownListingIds.size(), recorded.newListings(),
                    recorded.complete(), !knownState.baselineComplete()
            );

            return new TargetResult(preparedScan.usedFallback(), recorded.complete());
        } catch (RuntimeException exception) {
            apiClient.clearObservationContext(target.modelId());
            // A filter/scanner timeout may be the first symptom of a new challenge.
            // Only positive page evidence can turn it into protective backoff.
            observer.prepare(context);
            throw exception;
        }
    }

    private record TargetResult(boolean usedFallback, boolean complete) {}
}
