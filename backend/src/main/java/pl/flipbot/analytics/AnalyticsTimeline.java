package pl.flipbot.analytics;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import pl.flipbot.analytics.dto.AnalyticsOverviewResponse;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingHistoryMetadata;
import pl.flipbot.marketstats.MarketListingObservation;

import static pl.flipbot.analytics.AnalyticsMath.positive;

final class AnalyticsTimeline {
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MM.yyyy");

    private AnalyticsTimeline() {}

    static List<AnalyticsOverviewResponse.TimelinePoint> buildTimeline(
            List<Listing> history,
            List<MarketListingObservation> market,
            AnalyticsGranularity granularity
    ) {
        Map<LocalDate, List<BigDecimal>> marketPrices = new LinkedHashMap<>();
        Map<LocalDate, Long> marketCounts = new LinkedHashMap<>();
        Map<LocalDate, List<BigDecimal>> purchasePrices = new LinkedHashMap<>();

        for (MarketListingObservation observation : market) {
            LocalDate bucket = bucketStart(observation.getPublishedAt().toLocalDate(), granularity);
            marketCounts.merge(bucket, 1L, Long::sum);

            BigDecimal price = MarketAnalytics.marketPrice(observation);
            if (positive(price)) {
                marketPrices.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(price);
            }
        }

        for (Listing listing : history) {
            if (!HistoryAnalytics.isPurchased(listing)) {
                continue;
            }

            LocalDateTime eventAt = ListingHistoryMetadata.effectiveHistoryDate(listing);

            if (eventAt == null || !positive(listing.getCurrentPrice())) {
                continue;
            }

            LocalDate bucket = bucketStart(eventAt.toLocalDate(), granularity);
            purchasePrices.computeIfAbsent(
                    bucket,
                    ignored -> new ArrayList<>()
            ).add(listing.getCurrentPrice());
        }

        TreeSet<LocalDate> observedDates = new TreeSet<>();
        observedDates.addAll(marketCounts.keySet());
        observedDates.addAll(purchasePrices.keySet());

        if (observedDates.isEmpty()) {
            return List.of();
        }

        LocalDate firstBucket = observedDates.iterator().next();
        LocalDate lastBucket = observedDates.last();

        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = firstBucket;

        while (!cursor.isAfter(lastBucket)) {
            dates.add(cursor);
            cursor = nextBucket(cursor, granularity);
        }

        return dates.stream()
                .map(date -> {
                    AnalyticsMath.PriceSummary marketSummary =
                            AnalyticsMath.summarize(marketPrices.getOrDefault(date, List.of()));
                    AnalyticsMath.PriceSummary purchaseSummary =
                            AnalyticsMath.summarize(purchasePrices.getOrDefault(date, List.of()));

                    return new AnalyticsOverviewResponse.TimelinePoint(
                            date,
                            bucketLabel(date, granularity),
                            marketCounts.getOrDefault(date, 0L),
                            marketSummary.count(),
                            marketSummary.average(),
                            marketSummary.median(),
                            purchaseSummary.count(),
                            purchaseSummary.count(),
                            purchaseSummary.average(),
                            purchaseSummary.median()
                    );
                })
                .toList();
    }

    static LocalDate nextBucket(LocalDate date, AnalyticsGranularity granularity) {
        return switch (granularity) {
            case DAY -> date.plusDays(1);
            case WEEK -> date.plusWeeks(1);
            case MONTH -> date.plusMonths(1);
            case YEAR -> date.plusYears(1);
        };
    }

    static LocalDate bucketStart(LocalDate date, AnalyticsGranularity granularity) {
        return switch (granularity) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    static String bucketLabel(LocalDate date, AnalyticsGranularity granularity) {
        return switch (granularity) {
            case DAY -> date.format(DAY_LABEL);
            case WEEK -> "Tydz. " + date.format(DAY_LABEL);
            case MONTH -> date.format(MONTH_LABEL);
            case YEAR -> Integer.toString(date.getYear());
        };
    }
}
