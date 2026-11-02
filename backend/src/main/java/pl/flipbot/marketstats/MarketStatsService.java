package pl.flipbot.marketstats;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.flipbot.exception.InvalidRequestException;
import pl.flipbot.bot.configuration.BotAdditionalTarget;
import pl.flipbot.bot.configuration.BotAdditionalTargetRepository;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.bot.configuration.BotConfigurationRepository;
import pl.flipbot.dictionary.DictionaryModel;
import pl.flipbot.dictionary.DictionaryModelRepository;
import pl.flipbot.marketstats.dto.KnownMarketListingIdsResponse;
import pl.flipbot.marketstats.dto.MarketObservationBatchRequest;
import pl.flipbot.marketstats.dto.MarketObservationBatchResponse;
import pl.flipbot.marketstats.dto.MarketStatsTargetResponse;

import static pl.flipbot.marketstats.MarketProductMatcher.*;

@Service
@RequiredArgsConstructor
public class MarketStatsService {

    private static final int OBSERVATION_RETENTION_DAYS = 400;

    private final DictionaryModelRepository modelRepository;
    private final BotConfigurationRepository configurationRepository;
    private final BotAdditionalTargetRepository additionalTargetRepository;
    private final MarketModelScanStateRepository scanStateRepository;
    private final MarketListingObservationRepository observationRepository;

    @Transactional(readOnly = true)
    public List<MarketStatsTargetResponse> getTargets() {
        List<BotConfiguration> configurations = configurationRepository.findAll();
        List<BotAdditionalTarget> additionalTargets =
                additionalTargetRepository.findAll()
                        .stream()
                        .filter(target -> Boolean.TRUE.equals(target.getActive()))
                        .toList();
        Map<Long, MarketModelScanState> scanStates =
                scanStateRepository.findAll()
                        .stream()
                        .filter(state -> state.getModelId() != null)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        MarketModelScanState::getModelId,
                                        state -> state,
                                        (left, right) -> left
                                )
                        );

        return modelRepository.findAll()
                .stream()
                .sorted(
                        MarketStatsTargetOrdering.comparator(
                                configurations,
                                scanStates
                        )
                )
                .map(model -> {
                    MarketTargetCategories.Resolution category = MarketTargetCategories.resolve(
                            model,
                            configurations,
                            additionalTargets
                    );

                    return new MarketStatsTargetResponse(
                            model.getId(),
                            model.getBrand().getName(),
                            model.getName(),
                            resolveTargetMode(model),
                            category.path(),
                            category.resolved(),
                            model.getMarketMinPrice(),
                            model.getMarketMaxPrice(),
                            trackingGeneration(scanStates.get(model.getId()))
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public KnownMarketListingIdsResponse getKnownListingIds(
            Long modelId
    ) {
        requireModel(modelId);

        LocalDateTime cutoff = LocalDateTime.now()
                .minusDays(OBSERVATION_RETENTION_DAYS);

        MarketModelScanState state = scanStateRepository
                .findById(modelId)
                .orElse(null);
        int generation = trackingGeneration(state);

        List<String> listingIds = observationRepository.findKnownListingIds(
                modelId,
                generation,
                cutoff
        );

        boolean baselineComplete = state != null
                && state.getBaselineCompleteAt() != null;

        return new KnownMarketListingIdsResponse(
                modelId,
                listingIds,
                baselineComplete
        );
    }

    @Transactional
    public void resetModelTracking(
            Long modelId
    ) {
        requireModel(modelId);

        MarketModelScanState state = scanStateRepository
                .findByModelIdForUpdate(modelId)
                .orElse(null);

        if (state == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int nextGeneration = trackingGeneration(state) + 1;

        state.setTrackingGeneration(nextGeneration);
        state.setInitializedAt(now);
        state.setBaselineCompleteAt(null);
        state.setBaselineOfferCount(null);
        state.setPublicationWindowCompleteAt(null);
        state.setLastScanAt(now);
        state.setLastSuccessfulScanAt(null);
        state.setLastScanComplete(false);
        scanStateRepository.save(state);
    }


    @Transactional
    public MarketObservationBatchResponse recordObservations(Long modelId, MarketObservationBatchRequest request) {
        return new MarketObservationIngestion(modelRepository, scanStateRepository, observationRepository).record(modelId, request);
    }

    private DictionaryModel requireModel(
            Long modelId
    ) {
        if (modelId == null || modelId <= 0) {
            throw new InvalidRequestException(
                    "Model id must be positive."
            );
        }

        return modelRepository.findById(modelId)
                .orElseThrow(
                        () -> new NoSuchElementException(
                                "Dictionary model was not found: " + modelId
                        )
                );
    }


}