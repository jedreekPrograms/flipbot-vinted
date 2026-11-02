package pl.flipbot.analytics;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.flipbot.analytics.dto.AnalyticsOverviewResponse;
import pl.flipbot.dashboard.DashboardPeriod;
import pl.flipbot.dictionary.DictionaryModelRepository;
import pl.flipbot.listing.HistoryOutcome;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.listing.OfferAssessment;
import pl.flipbot.marketstats.MarketListingObservationRepository;
import pl.flipbot.marketstats.MarketModelScanStateRepository;

@Service
@RequiredArgsConstructor
public class AnalyticsService {
    private final ListingRepository listingRepository;
    private final MarketListingObservationRepository observationRepository;
    private final MarketModelScanStateRepository scanStateRepository;
    private final DictionaryModelRepository modelRepository;
    private final HistoryModelResolver historyModelResolver;

    @Transactional(readOnly = true)
    public AnalyticsOverviewResponse getOverview(DashboardPeriod period, Set<Long> modelIds,
            Set<HistoryOutcome> outcomes, Set<OfferAssessment> assessments, AnalyticsSource source,
            AnalyticsGranularity granularity, LocalDate fromDate, LocalDate toDate) {
        DashboardPeriod effectivePeriod = period == null ? DashboardPeriod.ALL : period;
        AnalyticsSource effectiveSource = source == null ? AnalyticsSource.ALL : source;
        AnalyticsGranularity effectiveGranularity = granularity == null ? AnalyticsGranularity.DAY : granularity;
        Set<Long> selectedModelIds = modelIds == null ? Set.of() : Set.copyOf(modelIds);
        AnalyticsTimeRange range = AnalyticsTimeRange.resolveRange(
                effectivePeriod, fromDate, toDate, LocalDateTime.now());
        var data = new AnalyticsDataLoader(listingRepository, observationRepository, scanStateRepository,
                modelRepository, historyModelResolver).load(selectedModelIds, outcomes, assessments, effectiveSource, range);
        return AnalyticsReportBuilder.build(data, selectedModelIds, range, effectiveGranularity);
    }
}
