package pl.flipbot.analytics;

import lombok.RequiredArgsConstructor;
import pl.flipbot.dictionary.DictionaryModel;
import pl.flipbot.dictionary.DictionaryModelRepository;
import pl.flipbot.listing.HistoryOutcome;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingHistoryMetadata;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.listing.OfferAssessment;
import pl.flipbot.marketstats.MarketListingObservation;
import pl.flipbot.marketstats.MarketListingObservationRepository;
import pl.flipbot.marketstats.MarketModelScanState;
import pl.flipbot.marketstats.MarketModelScanStateRepository;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Selects one request's data and resolves each eligible history row's model at most once. */
@RequiredArgsConstructor
final class AnalyticsDataLoader {
    private final ListingRepository listingRepository;
    private final MarketListingObservationRepository observationRepository;
    private final MarketModelScanStateRepository scanStateRepository;
    private final DictionaryModelRepository modelRepository;
    private final HistoryModelResolver historyModelResolver;

    Dataset load(Set<Long> modelIds, Set<HistoryOutcome> outcomes, Set<OfferAssessment> assessments,
                 AnalyticsSource source, AnalyticsTimeRange range) {
        List<DictionaryModel> models = modelRepository.findAll().stream()
                .sorted(Comparator.comparing((DictionaryModel model) -> model.getBrand().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(DictionaryModel::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        Map<Long, MarketModelScanState> states = scanStateRepository.findAll().stream().collect(Collectors.toMap(
                MarketModelScanState::getModelId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Set<Long> knownIds = models.stream().map(DictionaryModel::getId).collect(Collectors.toSet());
        Set<Long> unknownIds = modelIds.stream().filter(id -> !knownIds.contains(id)).collect(Collectors.toSet());
        if (!unknownIds.isEmpty()) {
            throw new NoSuchElementException("Dictionary model(s) were not found: " + unknownIds);
        }

        // Scope this cache to the request, so edits/new dictionary mappings take effect on the next request.
        Map<Listing, Optional<Long>> resolvedIds = new IdentityHashMap<>();
        Function<Listing, Optional<Long>> modelId = listing -> resolvedIds.computeIfAbsent(
                listing, row -> historyModelResolver.resolveModelId(row, models));
        List<Listing> history = source == AnalyticsSource.OBSERVER ? List.of() : listingRepository.findAll(AnalyticsQueries.history(range)).stream()
                .filter(ListingHistoryMetadata::isHistoryListing)
                .filter(listing -> !listing.isHistoryHidden())
                .filter(listing -> range.contains(ListingHistoryMetadata.effectiveHistoryDate(listing)))
                .filter(listing -> modelIds.isEmpty() || modelId.apply(listing).map(modelIds::contains).orElse(false))
                .filter(listing -> outcomes == null || outcomes.isEmpty() || HistoryAnalytics.matchesOutcomeFilter(listing, outcomes))
                .filter(listing -> assessments == null || assessments.isEmpty()
                        || assessments.contains(ListingHistoryMetadata.effectiveAssessment(listing))).toList();
        Map<Long, List<Listing>> historyByModel = new LinkedHashMap<>();
        for (Listing listing : history) {
            modelId.apply(listing).ifPresent(id -> historyByModel.computeIfAbsent(id, ignored -> new java.util.ArrayList<>()).add(listing));
        }

        // Baseline survivors do not establish historical coverage before baseline completion.
        List<MarketListingObservation> market = source == AnalyticsSource.HISTORY ? List.of() : observationRepository.findAll(AnalyticsQueries.market(modelIds, range)).stream()
                .filter(observation -> observation.getPublishedAt() != null)
                .filter(observation -> MarketAnalytics.isCurrentTrackingGeneration(observation, states))
                .filter(observation -> MarketAnalytics.withinReliableMarketCoverage(observation, states))
                .filter(observation -> range.contains(observation.getPublishedAt()))
                .filter(observation -> modelIds.isEmpty() || observation.getModel() != null
                        && modelIds.contains(observation.getModel().getId())).toList();
        Map<Long, List<MarketListingObservation>> marketByModel = market.stream().collect(Collectors.groupingBy(
                observation -> observation.getModel().getId(), LinkedHashMap::new, Collectors.toList()));
        return new Dataset(models, states, history, market, historyByModel, marketByModel);
    }

    record Dataset(List<DictionaryModel> models, Map<Long, MarketModelScanState> scanStates,
                   List<Listing> history, List<MarketListingObservation> market,
                   Map<Long, List<Listing>> historyByModel, Map<Long, List<MarketListingObservation>> marketByModel) {}
}
