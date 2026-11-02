package pl.flipbot.marketstats;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import pl.flipbot.exception.InvalidRequestException;
import pl.flipbot.dictionary.DictionaryModel;
import pl.flipbot.dictionary.DictionaryModelRepository;
import pl.flipbot.marketstats.dto.MarketObservationBatchRequest;
import pl.flipbot.marketstats.dto.MarketObservationBatchResponse;

import static pl.flipbot.marketstats.MarketProductMatcher.*;

/** Validates and writes one locked observer batch; its caller owns the transaction. */
@RequiredArgsConstructor
final class MarketObservationIngestion {
    private final DictionaryModelRepository modelRepository;
    private final MarketModelScanStateRepository scanStateRepository;
    private final MarketListingObservationRepository observationRepository;

    MarketObservationBatchResponse record(
            Long modelId,
            MarketObservationBatchRequest request
    ) {
        Objects.requireNonNull(
                request,
                "Market observation request cannot be null"
        );

        DictionaryModel model = modelRepository.findByIdForUpdate(modelId)
                .orElseThrow(
                        () -> new NoSuchElementException(
                                "Dictionary model was not found: " + modelId
                        )
                );

        if (!samePrice(model.getMarketMinPrice(), request.minPrice())
                || !samePrice(model.getMarketMaxPrice(), request.maxPrice())) {
            throw new IllegalStateException(
                    "Market observer price range changed while model "
                            + modelId
                            + " was being scanned. The stale observation batch was rejected."
            );
        }

        List<String> listingIds = normalizeListingIds(request.listingIds());
        Map<String, BigDecimal> listingPrices = normalizeListingPrices(
                request.listingPrices(),
                listingIds
        );

        if (listingIds.isEmpty() && !request.complete()) {
            throw new InvalidRequestException(
                    "An incomplete market scan must contain at least one marketplace listing id."
            );
        }

        LocalDateTime now = LocalDateTime.now();

        MarketModelScanState state = scanStateRepository
                .findByModelIdForUpdate(modelId)
                .orElse(null);

        int currentGeneration = trackingGeneration(state);
        int requestedGeneration = request.trackingGeneration() == null
                ? 1
                : request.trackingGeneration();

        if (requestedGeneration != currentGeneration) {
            throw new IllegalStateException(
                    "Stale market observer batch rejected for model "
                            + modelId
                            + ": request generation="
                            + requestedGeneration
                            + ", current generation="
                            + currentGeneration
            );
        }

        boolean createdState = state == null;

        if (state == null) {
            state = MarketModelScanState.builder()
                    .model(model)
                    .initializedAt(now)
                    .trackingGeneration(1)
                    .baselineCompleteAt(null)
                    .baselineOfferCount(null)
                    .lastScanAt(now)
                    .lastSuccessfulScanAt(null)
                    .lastScanComplete(false)
                    .build();

            state = scanStateRepository.saveAndFlush(state);
        }

        boolean baselineMode = state.getBaselineCompleteAt() == null;
        int generation = trackingGeneration(state);

        Map<String, MarketListingObservation> existingById =
                new HashMap<>();

        if (!listingIds.isEmpty()) {
            observationRepository
                    .findAllByModel_IdAndTrackingGenerationAndMarketplaceListingIdIn(
                            modelId,
                            generation,
                            listingIds
                    )
                    .forEach(observation -> existingById.put(
                            observation.getMarketplaceListingId(),
                            observation
                    ));
        }

        List<MarketListingObservation> changed = new ArrayList<>();
        int newListings = 0;

        for (String listingId : listingIds) {
            MarketListingObservation existing = existingById.get(listingId);

            BigDecimal observedPrice = listingPrices.get(listingId);

            if (existing != null) {
                existing.setLastSeenAt(now);

                /*
                 * MarketStatsApiClient first persists discovery with
                 * complete=false and only sends complete=true after publication
                 * requirements have been satisfied. A healthy incremental scan
                 * may intentionally skip detail-page timestamp resolution for a
                 * brand-new post-baseline listing; only at this completion step
                 * is firstSeenAt promoted to the publication fallback.
                 *
                 * Interrupted/full-recovery scans never reach complete=true
                 * while an exact timestamp is still required, so they keep
                 * publishedAt=null and remain retryable.
                 */
                if (existing.getPublishedAt() == null
                        && request.complete()
                        && !baselineMode) {
                    existing.setPublishedAt(existing.getFirstSeenAt());
                }

                applyObservedPrice(existing, observedPrice);
                changed.add(existing);
                continue;
            }

            boolean baseline = baselineMode;

            MarketListingObservation observation =
                    MarketListingObservation.builder()
                            .model(model)
                            .trackingGeneration(generation)
                            .marketplaceListingId(listingId)
                            .firstSeenAt(now)
                            .lastSeenAt(now)
                            .baseline(baseline)
                            .build();

            applyObservedPrice(observation, observedPrice);
            changed.add(observation);

            if (!baseline) {
                newListings++;
            }
        }

        if (!changed.isEmpty()) {
            observationRepository.saveAll(changed);
        }

        state.setLastScanAt(now);
        state.setLastScanComplete(request.complete());

        if (request.complete()) {
            state.setLastSuccessfulScanAt(now);

            if (state.getBaselineCompleteAt() == null) {
                observationRepository.flush();
                state.setBaselineCompleteAt(now);
                state.setBaselineOfferCount(
                        safeInt(
                                observationRepository.countByModel_IdAndTrackingGenerationAndBaselineTrue(
                                        modelId,
                                        generation
                                )
                        )
                );
            }
        }

        scanStateRepository.save(state);

        return new MarketObservationBatchResponse(
                modelId,
                createdState || baselineMode,
                listingIds.size(),
                newListings,
                now,
                request.complete()
        );
    }

    private void applyObservedPrice(
            MarketListingObservation observation,
            BigDecimal observedPrice
    ) {
        if (observation == null
                || observedPrice == null
                || observedPrice.signum() <= 0) {
            return;
        }

        BigDecimal normalized = observedPrice.setScale(
                2,
                java.math.RoundingMode.HALF_UP
        );

        if (observation.getFirstSeenPrice() == null) {
            observation.setFirstSeenPrice(normalized);
        }

        observation.setLatestPrice(normalized);

        if (observation.getLowestSeenPrice() == null
                || normalized.compareTo(observation.getLowestSeenPrice()) < 0) {
            observation.setLowestSeenPrice(normalized);
        }

        if (observation.getHighestSeenPrice() == null
                || normalized.compareTo(observation.getHighestSeenPrice()) > 0) {
            observation.setHighestSeenPrice(normalized);
        }
    }

    private Map<String, BigDecimal> normalizeListingPrices(
            Map<String, BigDecimal> rawPrices,
            List<String> acceptedListingIds
    ) {
        if (rawPrices == null
                || rawPrices.isEmpty()
                || acceptedListingIds == null
                || acceptedListingIds.isEmpty()) {
            return Map.of();
        }

        Set<String> accepted = Set.copyOf(acceptedListingIds);
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();

        for (Map.Entry<String, BigDecimal> entry : rawPrices.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }

            String listingId = entry.getKey().trim();
            BigDecimal price = entry.getValue();

            if (!listingId.isEmpty()
                    && accepted.contains(listingId)
                    && price.signum() > 0) {
                normalized.put(listingId, price);
            }
        }

        return Map.copyOf(normalized);
    }

    private List<String> normalizeListingIds(
            List<String> rawListingIds
    ) {
        if (rawListingIds == null) {
            return List.of();
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();

        for (String rawId : rawListingIds) {
            if (rawId == null) {
                continue;
            }

            String normalized = rawId.trim();

            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }

        return List.copyOf(unique);
    }

    private boolean samePrice(
            BigDecimal left,
            BigDecimal right
    ) {
        if (left == null || right == null) {
            return left == right;
        }

        return left.compareTo(right) == 0;
    }

    private int safeInt(
            long value
    ) {
        return value > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) value;
    }

}
