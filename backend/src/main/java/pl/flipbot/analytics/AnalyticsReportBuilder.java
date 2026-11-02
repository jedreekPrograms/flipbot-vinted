package pl.flipbot.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.flipbot.analytics.dto.AnalyticsOverviewResponse;
import pl.flipbot.dictionary.DictionaryModel;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingHistoryMetadata;
import pl.flipbot.listing.OfferAssessment;
import pl.flipbot.marketstats.MarketListingObservation;
import pl.flipbot.marketstats.MarketModelScanState;

import static pl.flipbot.analytics.AnalyticsMath.positive;

final class AnalyticsReportBuilder {
    private AnalyticsReportBuilder() {}

    static AnalyticsOverviewResponse build(AnalyticsDataLoader.Dataset data, Set<Long> selectedModelIds,
                                           AnalyticsTimeRange range, AnalyticsGranularity granularity) {
        var summary = buildSummary(data.history(), data.market(), range, data.scanStates());
        var breakdowns = data.models().stream()
                .filter(model -> selectedModelIds.isEmpty() || selectedModelIds.contains(model.getId()))
                .map(model -> buildModelBreakdown(model,
                        data.historyByModel().getOrDefault(model.getId(), List.of()),
                        data.marketByModel().getOrDefault(model.getId(), List.of()), range, data.scanStates()))
                .filter(AnalyticsReportBuilder::hasData)
                .toList();
        var timeline = AnalyticsTimeline.buildTimeline(data.history(), data.market(), granularity);
        var histogram = AnalyticsMath.histogram(data.market().stream()
                        .map(MarketAnalytics::marketPrice).filter(AnalyticsMath::positive).toList(), 10)
                .stream().map(bucket -> new AnalyticsOverviewResponse.HistogramBucket(
                        bucket.from(), bucket.to(), bucket.count())).toList();
        var options = data.models().stream().map(model -> new AnalyticsOverviewResponse.ModelOption(
                model.getId(), model.getBrand().getName(), model.getName(), label(model),
                model.getMarketMinPrice(), model.getMarketMaxPrice())).toList();
        return new AnalyticsOverviewResponse(options, summary, breakdowns, timeline, histogram);
    }

    static AnalyticsOverviewResponse.Summary buildSummary(
            List<Listing> history,
            List<MarketListingObservation> market,
            AnalyticsTimeRange range,
            Map<Long, MarketModelScanState> marketScanStates
    ) {
        List<BigDecimal> marketPrices = market.stream()
                .map(MarketAnalytics::marketPrice)
                .filter(AnalyticsMath::positive)
                .toList();

        List<BigDecimal> purchasePrices = history.stream()
                .filter(HistoryAnalytics::isPurchased)
                .map(Listing::getCurrentPrice)
                .filter(AnalyticsMath::positive)
                .toList();

        List<BigDecimal> legitRejectedPrices = history.stream()
                .filter(HistoryAnalytics::isNotPurchased)
                .filter(listing ->
                        ListingHistoryMetadata.effectiveAssessment(listing)
                                == OfferAssessment.LEGIT
                )
                .map(Listing::getCurrentPrice)
                .filter(AnalyticsMath::positive)
                .toList();

        List<BigDecimal> missedPrices = history.stream()
                .filter(HistoryAnalytics::isMissedOpportunity)
                .map(Listing::getCurrentPrice)
                .filter(AnalyticsMath::positive)
                .toList();

        AnalyticsMath.PriceSummary marketSummary = AnalyticsMath.summarize(marketPrices);
        AnalyticsMath.PriceSummary purchaseSummary = AnalyticsMath.summarize(purchasePrices);
        AnalyticsMath.PriceSummary rejectedSummary = AnalyticsMath.summarize(legitRejectedPrices);
        AnalyticsMath.PriceSummary missedSummary = AnalyticsMath.summarize(missedPrices);
        MarketAnalytics.MarketRates rates = MarketAnalytics.marketRates(market, range, marketScanStates);

        PriceAdvantage advantage = priceAdvantage(marketSummary.median(), purchaseSummary.median());

        return new AnalyticsOverviewResponse.Summary(
                history.size(),
                history.stream().filter(HistoryAnalytics::isPurchased).count(),
                history.stream().filter(HistoryAnalytics::isNotPurchased).count(),
                history.stream().filter(HistoryAnalytics::isMissedOpportunity).count(),
                HistoryAnalytics.countAssessment(history, OfferAssessment.LEGIT),
                HistoryAnalytics.countAssessment(history, OfferAssessment.SCAM),
                HistoryAnalytics.countAssessment(history, OfferAssessment.UNASSESSED),
                market.size(),
                marketSummary.count(),
                marketSummary.average(),
                marketSummary.median(),
                marketSummary.p25(),
                marketSummary.p75(),
                marketSummary.standardDeviation(),
                marketSummary.min(),
                marketSummary.max(),
                rates.perDay(),
                rates.perWeek(),
                rates.perMonth(),
                purchaseSummary.average(),
                purchaseSummary.median(),
                rejectedSummary.average(),
                rejectedSummary.median(),
                missedSummary.average(),
                missedSummary.median(),
                advantage.amount(),
                advantage.percent()
        );
    }

    static AnalyticsOverviewResponse.ModelBreakdown buildModelBreakdown(
            DictionaryModel model,
            List<Listing> modelHistory,
            List<MarketListingObservation> modelMarket,
            AnalyticsTimeRange range,
            Map<Long, MarketModelScanState> marketScanStates
    ) {
        String modelLabel = label(model);

        AnalyticsMath.PriceSummary marketSummary =
                AnalyticsMath.summarize(
                        modelMarket.stream()
                                .map(MarketAnalytics::marketPrice)
                                .filter(AnalyticsMath::positive)
                                .toList()
                );

        AnalyticsMath.PriceSummary purchaseSummary =
                AnalyticsMath.summarize(
                        modelHistory.stream()
                                .filter(HistoryAnalytics::isPurchased)
                                .map(Listing::getCurrentPrice)
                                .filter(AnalyticsMath::positive)
                                .toList()
                );

        PriceAdvantage advantage = priceAdvantage(marketSummary.median(), purchaseSummary.median());
        MarketAnalytics.MarketRates rates = MarketAnalytics.marketRates(modelMarket, range, marketScanStates);

        long legitRejected = modelHistory.stream()
                .filter(HistoryAnalytics::isNotPurchased)
                .filter(listing ->
                        ListingHistoryMetadata.effectiveAssessment(listing)
                                == OfferAssessment.LEGIT
                )
                .count();

        return new AnalyticsOverviewResponse.ModelBreakdown(
                model.getId(),
                model.getBrand().getName(),
                model.getName(),
                modelLabel,
                modelMarket.size(),
                marketSummary.count(),
                marketSummary.average(),
                marketSummary.median(),
                rates.perDay(),
                rates.perWeek(),
                rates.perMonth(),
                modelHistory.stream().filter(HistoryAnalytics::isPurchased).count(),
                purchaseSummary.average(),
                purchaseSummary.median(),
                legitRejected,
                modelHistory.stream().filter(HistoryAnalytics::isMissedOpportunity).count(),
                HistoryAnalytics.countAssessment(modelHistory, OfferAssessment.SCAM),
                advantage.amount(),
                advantage.percent()
        );
    }

    static boolean hasData(AnalyticsOverviewResponse.ModelBreakdown breakdown) {
        return breakdown.marketListingCount() > 0
                || breakdown.purchasedCount() > 0
                || breakdown.legitRejectedCount() > 0
                || breakdown.missedOpportunityCount() > 0
                || breakdown.scamCount() > 0;
    }

    static String label(DictionaryModel model) {
        return ListingHistoryMetadata.modelLabel(model.getBrand().getName(), model.getName());
    }

    static PriceAdvantage priceAdvantage(BigDecimal marketMedian, BigDecimal purchaseMedian) {
        if (!positive(marketMedian) || !positive(purchaseMedian)) {
            return PriceAdvantage.empty();
        }

        BigDecimal amount = marketMedian
                .subtract(purchaseMedian)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal percent = amount
                .divide(marketMedian, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        return new PriceAdvantage(amount, percent);
    }

    record PriceAdvantage(BigDecimal amount, BigDecimal percent) {
        static PriceAdvantage empty() {
            return new PriceAdvantage(null, null);
        }
    }
}
