package pl.flipbot.playwright.marketstats;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.scanner.ListingScanner;
import pl.flipbot.playwright.scanner.model.Listing;
import pl.flipbot.playwright.target.ListingTargetMatcher;
import pl.flipbot.playwright.util.ErrorMessages;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
final class MarketCatalogScan {
    private static final double PAGE_WAIT_MS = 1_000;

    private static final double CATALOG_PAGE_NAVIGATION_TIMEOUT_MS = 30_000;

    private static final int MAX_NO_GROWTH_PAGES = 2;

    private static final int HISTORICAL_PUBLICATION_BOUNDARY_SIZE = 20;

    private static final ZoneId MARKET_ZONE = ZoneId.of("Europe/Warsaw");

    private final MarketStatsRuntimeConfig config;
    private final MarketObserverPage observer;

    ScanResult scan(
            BotContext context,
            Long modelId,
            BotConfigurationDto targetConfiguration,
            boolean accessoryFiltering,
            boolean trustVintedModelFilter,
            Set<String> knownListingIds,
            boolean baselineComplete
    ) {
        ListingScanner scanner = new ListingScanner(context);
        ListingTargetMatcher matcher = new ListingTargetMatcher();
        MarketCatalogPublishedAtResolver catalogPublishedAtResolver =
                new MarketCatalogPublishedAtResolver(context);
        MarketListingPublishedAtResolver detailPublishedAtResolver =
                new MarketListingPublishedAtResolver(context);
        LinkedHashMap<String, Listing> matched = new LinkedHashMap<>();

        String filteredCatalogUrl = context.getPage().url();
        LocalDateTime earliestRelevantPublishedAt =
                MarketScanCoverage.earliestRelevantPublicationAt(LocalDate.now(MARKET_ZONE));
        int pageNumber = 1;
        int noGrowthPages = 0;
        boolean complete = false;
        boolean historicalPublicationBoundaryReached = false;
        boolean extendedPastConfiguredLimit = false;
        int configuredLimit = config.maxListingsPerModel();
        int dynamicHardLimit = MarketScanCoverage.dynamicMaxListingsPerModel(configuredLimit);

        while (matched.size() < dynamicHardLimit) {
            if (pageNumber > 1) {
                if (!navigateToCatalogPage(context, filteredCatalogUrl, pageNumber)) {
                    complete = false;
                    break;
                }
            }

            observer.prepare(context);
            List<Listing> loaded = scanner.scan();

            if (loaded.isEmpty()) {
                // ListingScanner also returns empty after a timeout or when all
                // cards fail parsing. That does not prove the catalog ended.
                complete = false;
                log.warn("[MARKET STATS] No readable cards on page {} for model {}. "
                        + "Keeping the scan incomplete; an empty result alone cannot confirm coverage.",
                        pageNumber, modelId);
                log.info("[MARKET STATS PAGE] model={} page={} evidence={}",
                        modelId, pageNumber, MarketCatalogPageDiagnostics.describe(context.getPage()));
                break;
            }

            int beforePage = matched.size();
            List<Listing> newlyAccepted = new ArrayList<>();

            for (Listing listing : loaded) {
                if (listing == null || isBlank(listing.getId())) {
                    continue;
                }

                boolean accepted = trustVintedModelFilter
                        || MarketListingFilter.matchesTarget(listing, targetConfiguration, accessoryFiltering, matcher);

                if (!accepted) {
                    continue;
                }

                Listing previous = matched.putIfAbsent(listing.getId(), listing);

                if (previous == null) {
                    newlyAccepted.add(listing);

                    if (matched.size() >= dynamicHardLimit) {
                        break;
                    }
                }
            }

            /*
             * First read immutable created_at_ts values already hydrated in
             * the filtered catalog document. This performs zero extra Vinted
             * requests. Only timestamps which are still unresolved afterwards
             * use the paced item-detail fallback.
             */
            catalogPublishedAtResolver.captureIfAvailable(newlyAccepted);
            detailPublishedAtResolver.captureIfNeeded(newlyAccepted);

            log.info(
                    "[MARKET STATS] Catalog page {} inspected. loaded={}, acceptedNew={}, acceptedTotal={}.",
                    pageNumber, loaded.size(), newlyAccepted.size(), matched.size()
            );

            List<String> pageListingIds = newlyAccepted.stream()
                    .map(Listing::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .map(String::trim)
                    .toList();

            Map<String, LocalDateTime> pagePublishedAt =
                    MarketStatsObservationContext.resolvedFor(modelId, pageListingIds);

            if (MarketScanCoverage.hasHistoricalPublicationBoundary(
                    pageListingIds,
                    pagePublishedAt,
                    earliestRelevantPublishedAt,
                    HISTORICAL_PUBLICATION_BOUNDARY_SIZE
            )) {
                complete = true;
                historicalPublicationBoundaryReached = true;

                log.info(
                        "[MARKET STATS] Historical publication boundary confirmed by {} consecutive oldest listings on catalog page {}. All were published before {} (start of the previous full Monday-Sunday week). Stopping this model because older listings cannot affect today/current-week/previous-full-week statistics.",
                        HISTORICAL_PUBLICATION_BOUNDARY_SIZE,
                        pageNumber,
                        earliestRelevantPublishedAt
                );
                break;
            }

            if (!knownListingIds.isEmpty()
                    && MarketScanCoverage.containsKnownBoundary(matched.keySet(), knownListingIds, config.knownBoundarySize())) {
                complete = true;
                break;
            }

            if (matched.size() == beforePage) {
                noGrowthPages++;
            } else {
                noGrowthPages = 0;
            }

            if (noGrowthPages >= MAX_NO_GROWTH_PAGES) {
                complete = true;
                break;
            }

            if (!extendedPastConfiguredLimit
                    && matched.size() >= configuredLimit
                    && dynamicHardLimit > configuredLimit) {
                extendedPastConfiguredLimit = true;
                log.info(
                        "[MARKET STATS] Configured soft limit {} was reached before proving the historical/known boundary. Continuing this unusually large model up to a dynamic safety ceiling of {} listings instead of marking it incomplete immediately.",
                        configuredLimit,
                        dynamicHardLimit
                );
            }

            pageNumber++;
        }

        List<String> ids = matched.keySet()
                .stream()
                .limit(dynamicHardLimit)
                .toList();

        Map<String, java.math.BigDecimal> listingPrices =
                ids.stream()
                        .map(matched::get)
                        .filter(java.util.Objects::nonNull)
                        .filter(item -> item.getPrice() != null && item.getPrice().signum() > 0)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Listing::getId,
                                        Listing::getPrice,
                                        (left, right) -> left,
                                        LinkedHashMap::new
                                )
                        );

        boolean hitDynamicHardLimit = ids.size() >= dynamicHardLimit;

        if (hitDynamicHardLimit
                && !historicalPublicationBoundaryReached
                && !MarketScanCoverage.containsKnownBoundary(ids, knownListingIds, config.knownBoundarySize())) {
            complete = false;

            log.warn(
                    "[MARKET STATS] Catalog scan reached dynamic safety ceiling {} (configured soft limit was {}) before proving the end/known/statistics-window boundary. The scan stays incomplete rather than pretending the partial catalog is a full model window.",
                    dynamicHardLimit,
                    configuredLimit
            );
        }

        if (!baselineComplete && complete) {
            log.info(
                    "[MARKET STATS] Initial baseline covered the complete statistics publication window. listings={}.",
                    ids.size()
            );
        }

        return new ScanResult(ids, Map.copyOf(listingPrices), complete);
    }

    private boolean navigateToCatalogPage(BotContext context, String filteredCatalogUrl, int pageNumber) {
        if (isBlank(filteredCatalogUrl) || pageNumber <= 1) {
            return pageNumber <= 1;
        }

        String nextUrl;

        if (filteredCatalogUrl.matches(".*[?&]page=\\d+.*")) {
            nextUrl = filteredCatalogUrl.replaceFirst("([?&])page=\\d+", "$1page=" + pageNumber);
        } else {
            nextUrl = filteredCatalogUrl
                    + (filteredCatalogUrl.contains("?") ? "&" : "?")
                    + "page=" + pageNumber;
        }

        try {
            context.getPage().navigate(nextUrl, catalogPageNavigateOptions());
            context.getPage().waitForTimeout(PAGE_WAIT_MS);

            log.debug(
                    "[MARKET STATS] Opened filtered catalog page {} after DOMContentLoaded. url={}",
                    pageNumber,
                    context.getPage().url()
            );

            return true;
        } catch (RuntimeException exception) {
            log.warn(
                    "[MARKET STATS] Could not open filtered catalog page {}. url={}, reason={}",
                    pageNumber,
                    nextUrl,
                    ErrorMessages.fullMessage(exception, "unknown error")
            );
            return false;
        }
    }

    static Page.NavigateOptions catalogPageNavigateOptions() {
        return new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(CATALOG_PAGE_NAVIGATION_TIMEOUT_MS);
    }


    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record ScanResult(
            List<String> listingIds,
            Map<String, java.math.BigDecimal> listingPrices,
            boolean complete
    ) {
    }
}
