package pl.flipbot.marketstats;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.flipbot.analytics.HistoryModelResolver;
import pl.flipbot.bot.configuration.BotAdditionalTarget;
import pl.flipbot.bot.configuration.BotAdditionalTargetRepository;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.bot.configuration.BotConfigurationRepository;
import pl.flipbot.dictionary.DictionaryModel;
import pl.flipbot.dictionary.DictionaryModelRepository;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.marketstats.dto.CalendarModelPlanningResponse;
import pl.flipbot.negotiation.audit.RealActionAuditRepository;

import static pl.flipbot.marketstats.MarketProductMatcher.*;

@Service
@RequiredArgsConstructor
public class MarketStatsCalendarPlanningService {

    private static final ZoneId MARKET_STATS_ZONE = ZoneId.of("Europe/Warsaw");
    private static final long MIN_CURRENT_WINDOW_FRESHNESS_MINUTES = 120L;
    private static final long MAX_CURRENT_WINDOW_FRESHNESS_MINUTES = 360L;
    private static final long ESTIMATED_MINUTES_PER_MODEL = 2L;

    private final DictionaryModelRepository modelRepository;
    private final BotConfigurationRepository configurationRepository;
    private final BotAdditionalTargetRepository additionalTargetRepository;
    private final MarketModelScanStateRepository scanStateRepository;
    private final MarketListingObservationRepository observationRepository;
    private final RealActionAuditRepository realActionAuditRepository;
    private final ListingRepository listingRepository;
    private final HistoryModelResolver historyModelResolver;

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<CalendarModelPlanningResponse> getPlanning() {
        LocalDateTime now = LocalDateTime.now(MARKET_STATS_ZONE);
        List<BotConfiguration> configurations = configurationRepository.findAll();
        List<BotAdditionalTarget> additionalTargets =
                additionalTargetRepository.findAll()
                        .stream()
                        .filter(target -> Boolean.TRUE.equals(target.getActive()))
                        .toList();
        List<DictionaryModel> models = modelRepository.findAll();
        ObservedConversationCapacity.Profile capacityProfile =
                new ObservedConversationCapacity(realActionAuditRepository, listingRepository, historyModelResolver).load(
                        now.toLocalDate(),
                        models
                );
        long currentWindowFreshnessMinutes =
                currentWindowFreshnessMinutes(models.size());

        Map<Long, MarketModelScanState> states = scanStateRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(MarketModelScanState::getModelId, state -> state));
        var windows = MarketStatsPlanningCalculator.windows(now);
        Map<Long, MarketListingObservationRepository.PublicationCounts> counts = observationRepository
                .countPlanningWindows(windows.todayStart(), windows.currentWeekStart(), windows.previousWeekStart(), now)
                .stream().collect(java.util.stream.Collectors.toMap(
                        MarketListingObservationRepository.PublicationCounts::getModelId, value -> value));

        return models.stream()
                .sorted(
                        Comparator.comparing(
                                        (DictionaryModel model) -> model.getBrand().getName(),
                                        String.CASE_INSENSITIVE_ORDER
                                )
                                .thenComparing(
                                        DictionaryModel::getName,
                                        String.CASE_INSENSITIVE_ORDER
                                )
                )
                .map(model -> toPlanningResponse(
                        model,
                        configurations,
                        additionalTargets,
                        capacityProfile,
                        now,
                        currentWindowFreshnessMinutes, states.get(model.getId()), counts.get(model.getId())
                ))
                .toList();
    }

    private CalendarModelPlanningResponse toPlanningResponse(
            DictionaryModel model,
            List<BotConfiguration> configurations,
            List<BotAdditionalTarget> additionalTargets,
            ObservedConversationCapacity.Profile capacityProfile,
            LocalDateTime now,
            long currentWindowFreshnessMinutes,
            MarketModelScanState state,
            MarketListingObservationRepository.PublicationCounts counts
    ) {

        List<Long> matchingBotIds = matchingBotIds(
                model,
                configurations,
                additionalTargets
        );
        int existingBots = matchingBotIds.size();
        int dailyConversationCapacityPerBot =
                capacityProfile.dailyCapacityFor(
                        model.getId(),
                        matchingBotIds
                );
        int weeklyConversationCapacityPerBot =
                dailyConversationCapacityPerBot
                        * MarketStatsPlanningCalculator.DAYS_PER_WEEK;

        if (state == null || state.getBaselineCompleteAt() == null) {
            return new CalendarModelPlanningResponse(
                    model.getId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    dailyConversationCapacityPerBot,
                    weeklyConversationCapacityPerBot,
                    true,
                    existingBots,
                    false,
                    false,
                    false,
                    0,
                    state == null ? null : state.getLastScanAt(),
                    state == null ? null : state.getLastSuccessfulScanAt(),
                    false,
                    state != null && Boolean.TRUE.equals(state.getLastScanComplete())
            );
        }

        LocalDateTime baselineCompleteAt = state.getBaselineCompleteAt();
        MarketStatsPlanningCalculator.CalendarWindows windows =
                MarketStatsPlanningCalculator.windows(now);

        int offersToday = counts == null ? 0 : safeInt(counts.getToday());
        int offersCurrentWeek = counts == null ? 0 : safeInt(counts.getCurrentWeek());

        LocalDateTime lastSuccessfulScanAt = state.getLastSuccessfulScanAt();
        boolean publicationCoverageEstablished =
                state.getPublicationWindowCompleteAt() != null;
        boolean latestScanComplete =
                Boolean.TRUE.equals(state.getLastScanComplete());
        boolean successfulScanToday = lastSuccessfulScanAt != null
                && !lastSuccessfulScanAt.isBefore(windows.todayStart());
        boolean successfulScanThisWeek = lastSuccessfulScanAt != null
                && !lastSuccessfulScanAt.isBefore(windows.currentWeekStart());
        boolean currentScanFresh = lastSuccessfulScanAt != null
                && !lastSuccessfulScanAt.isBefore(
                        now.minusMinutes(currentWindowFreshnessMinutes)
                );

        /*
         * A traversal of today's currently-active catalog cannot reconstruct
         * offers which were published and sold before tracking started.
         * Therefore a calendar window is exact only when the model baseline
         * was already complete at the START of that window.
         *
         * Current-day/current-week values are exact through the latest
         * successful complete scan. Freshness is exposed separately so the UI
         * can show "stan na HH:mm" instead of hiding a valid count merely
         * because a large Observer pass takes longer than two hours.
         */
        boolean todayWindowComplete = publicationCoverageEstablished
                && latestScanComplete
                && successfulScanToday
                && MarketStatsPlanningCalculator.coversWindowFrom(
                        baselineCompleteAt,
                        windows.todayStart()
                );
        boolean currentWeekWindowComplete = publicationCoverageEstablished
                && latestScanComplete
                && successfulScanToday
                && MarketStatsPlanningCalculator.coversWindowFrom(
                        baselineCompleteAt,
                        windows.currentWeekStart()
                );
        boolean previousFullWeekAvailable = publicationCoverageEstablished
                && successfulScanThisWeek
                && MarketStatsPlanningCalculator.coversWindowFrom(
                        baselineCompleteAt,
                        windows.previousWeekStart()
                );

        Integer offersPreviousFullWeek = null;
        int recommendationWeeklyOffers;
        boolean recommendationEstimated;

        int trackedDays = MarketStatsPlanningCalculator.trackedCalendarDays(
                baselineCompleteAt,
                now
        );

        if (previousFullWeekAvailable) {
            offersPreviousFullWeek = counts == null ? 0 : safeInt(counts.getPreviousWeek());
            recommendationWeeklyOffers = offersPreviousFullWeek;
            recommendationEstimated = false;
        } else {
            int observedSinceBaseline = counts == null ? 0 : safeInt(counts.getSinceBaseline());
            recommendationWeeklyOffers =
                    MarketStatsPlanningCalculator.projectWeeklyOffers(
                            observedSinceBaseline,
                            Math.max(trackedDays, 1)
                    );
            recommendationEstimated = true;
        }

        int recommendedBots = MarketStatsPlanningCalculator.recommendedBots(
                recommendationWeeklyOffers,
                weeklyConversationCapacityPerBot
        );

        return new CalendarModelPlanningResponse(
                model.getId(),
                state.getBaselineOfferCount(),
                offersToday,
                offersCurrentWeek,
                offersPreviousFullWeek,
                recommendedBots,
                recommendationWeeklyOffers,
                dailyConversationCapacityPerBot,
                weeklyConversationCapacityPerBot,
                recommendationEstimated,
                existingBots,
                todayWindowComplete,
                currentWeekWindowComplete,
                previousFullWeekAvailable,
                trackedDays,
                state.getLastScanAt(),
                lastSuccessfulScanAt,
                currentScanFresh,
                latestScanComplete
        );
    }

    static long currentWindowFreshnessMinutes(int modelCount) {
        long estimatedCycleMinutes =
                Math.max(0L, modelCount) * ESTIMATED_MINUTES_PER_MODEL;

        return Math.max(
                MIN_CURRENT_WINDOW_FRESHNESS_MINUTES,
                Math.min(
                        MAX_CURRENT_WINDOW_FRESHNESS_MINUTES,
                        estimatedCycleMinutes
                )
        );
    }


    private List<Long> matchingBotIds(
            DictionaryModel model,
            List<BotConfiguration> configurations,
            List<BotAdditionalTarget> additionalTargets
    ) {
        Set<Long> ids = new LinkedHashSet<>();

        for (BotConfiguration configuration : configurations) {
            if (matchesModel(model, configuration)
                    && configuration.getBot() != null
                    && configuration.getBot().getId() != null) {
                ids.add(configuration.getBot().getId());
            }
        }

        for (BotAdditionalTarget target : additionalTargets) {
            if (matchesModel(model, target)
                    && target.getConfiguration() != null
                    && target.getConfiguration().getBot() != null
                    && target.getConfiguration().getBot().getId() != null) {
                ids.add(target.getConfiguration().getBot().getId());
            }
        }

        return List.copyOf(ids);
    }


    private int safeInt(long value) {
        return value > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) Math.max(value, 0L);
    }
}