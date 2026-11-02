package pl.flipbot.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import pl.flipbot.marketstats.MarketListingObservation;
import pl.flipbot.marketstats.MarketModelScanState;

import static pl.flipbot.analytics.AnalyticsMath.positive;

final class MarketAnalytics {
    private MarketAnalytics() {}

    static MarketRates marketRates(
            List<MarketListingObservation> market,
            AnalyticsTimeRange requestedRange,
            Map<Long, MarketModelScanState> marketScanStates
    ) {
        if (market.isEmpty()) {
            return MarketRates.empty();
        }

        Map<Long, List<MarketListingObservation>> byModel =
                market.stream()
                        .filter(observation ->
                                observation.getModel() != null
                                        && observation.getModel().getId() != null
                        )
                        .collect(
                                Collectors.groupingBy(
                                        observation ->
                                                observation.getModel().getId(),
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                )
                        );

        BigDecimal perDay = BigDecimal.ZERO;
        BigDecimal perWeek = BigDecimal.ZERO;
        BigDecimal perMonth = BigDecimal.ZERO;

        for (Map.Entry<Long, List<MarketListingObservation>> entry : byModel.entrySet()) {
            MarketModelScanState state = marketScanStates.get(entry.getKey());

            if (state == null || state.getBaselineCompleteAt() == null) {
                continue;
            }

            LocalDateTime effectiveFrom = requestedRange.from().equals(LocalDateTime.MIN)
                    ? state.getBaselineCompleteAt()
                    : requestedRange.from().isAfter(state.getBaselineCompleteAt())
                    ? requestedRange.from()
                    : state.getBaselineCompleteAt();

            LocalDate start = effectiveFrom.toLocalDate();
            LocalDate end = requestedRange.to().toLocalDate();

            if (end.isBefore(start)) {
                continue;
            }

            long days = Math.max(1, ChronoUnit.DAYS.between(start, end) + 1);
            BigDecimal count = BigDecimal.valueOf(entry.getValue().size());
            BigDecimal dailyRate = divide(count, days);

            /*
             * Aggregate rates as a sum of per-model daily rates. This prevents
             * a model whose tracking began mid-period from being diluted by
             * days when it was not tracked. Week/month are normalized rates,
             * not "number of calendar buckets touched", so a 10-day range does
             * not artificially halve the weekly pace.
             */
            perDay = perDay.add(dailyRate);
            perWeek = perWeek.add(dailyRate.multiply(BigDecimal.valueOf(7L)));
            perMonth = perMonth.add(dailyRate.multiply(new BigDecimal("30.44")));
        }

        return new MarketRates(
                perDay.setScale(2, RoundingMode.HALF_UP),
                perWeek.setScale(2, RoundingMode.HALF_UP),
                perMonth.setScale(2, RoundingMode.HALF_UP)
        );
    }

    static boolean isCurrentTrackingGeneration(
            MarketListingObservation observation,
            Map<Long, MarketModelScanState> marketScanStates
    ) {
        if (observation == null || observation.getModel() == null || observation.getModel().getId() == null) {
            return false;
        }

        MarketModelScanState state = marketScanStates.get(observation.getModel().getId());

        if (state == null) {
            return false;
        }

        int activeGeneration = state.getTrackingGeneration() == null
                || state.getTrackingGeneration() < 1
                ? 1
                : state.getTrackingGeneration();

        int observationGeneration =
                observation.getTrackingGeneration() == null
                        || observation.getTrackingGeneration() < 1
                        ? 1
                        : observation.getTrackingGeneration();

        return activeGeneration == observationGeneration;
    }

    static boolean withinReliableMarketCoverage(
            MarketListingObservation observation,
            Map<Long, MarketModelScanState> marketScanStates
    ) {
        if (observation == null
                || observation.getPublishedAt() == null
                || observation.getModel() == null
                || observation.getModel().getId() == null) {
            return false;
        }

        MarketModelScanState state = marketScanStates.get(observation.getModel().getId());

        if (state == null || state.getBaselineCompleteAt() == null) {
            return false;
        }

        return !observation.getPublishedAt().isBefore(state.getBaselineCompleteAt());
    }

    static BigDecimal divide(BigDecimal value, long divisor) {
        return value.divide(BigDecimal.valueOf(Math.max(divisor, 1)), 2, RoundingMode.HALF_UP);
    }

    static BigDecimal marketPrice(MarketListingObservation observation) {
        if (observation == null) {
            return null;
        }

        if (positive(observation.getFirstSeenPrice())) {
            return observation.getFirstSeenPrice();
        }

        return observation.getLatestPrice();
    }

    record MarketRates(BigDecimal perDay, BigDecimal perWeek, BigDecimal perMonth) {
        static MarketRates empty() {
            return new MarketRates(null, null, null);
        }
    }
}
