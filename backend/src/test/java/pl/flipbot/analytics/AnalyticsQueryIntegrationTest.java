package pl.flipbot.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import pl.flipbot.dictionary.DictionaryModelRepository;
import pl.flipbot.listing.HistoryOutcome;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.listing.OfferAssessment;
import pl.flipbot.marketstats.MarketListingObservationRepository;
import pl.flipbot.marketstats.MarketModelScanStateRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Compare actual PostgreSQL preselection with the previous whole-table loader. */
@SpringBootTest
@Transactional
class AnalyticsQueryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ListingRepository listings;
    @Autowired MarketListingObservationRepository observations;
    @Autowired MarketModelScanStateRepository states;
    @Autowired DictionaryModelRepository models;
    @Autowired HistoryModelResolver resolver;
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime END = START.plusDays(2).minusNanos(1);

    @BeforeEach void fixtures() {
        jdbc.update("insert into bot(id, market_stats_observer, name, status) values (-8901, false, 'analytics query fixture', 'STOPPED')");
        jdbc.update("insert into dictionary_brand(id, name) values (-8901, 'QueryBrand')");
        for (long modelId : new long[]{-8901, -8902}) {
            jdbc.update("insert into dictionary_model(id, brand_id, name, target_mode) values (?, -8901, ?, 'VINTED_MODEL')",
                    modelId, "QueryModel" + -modelId);
            jdbc.update("""
                    insert into market_model_scan_state(model_id, tracking_generation, initialized_at, baseline_complete_at, last_scan_at, last_scan_complete)
                    values (?, 2, ?, ?, ?, true)
                    """, modelId, START.minusDays(1), START, START.plusDays(2));
        }
        history(-8911, START, null, false, "PURCHASED", "QueryBrand QueryModel8901");
        history(-8912, START.minusDays(1), START, false, "EXPIRED", "QueryBrand → QueryModel8902");
        history(-8913, START, START.plusDays(3), false, "PURCHASED", "outside effective date");
        history(-8914, START, null, true, "PURCHASED", "hidden");
        history(-8915, null, START, false, "PURCHASED", "no candidate");
        history(-8916, START, null, false, "NEGOTIATING", "not history");
        history(-8917, START.plusDays(1), null, false, "SKIPPED_BY_USER", "unmapped item");
        history(-8918, START.minusDays(3), null, false, "PURCHASED", "old unmapped item");
        history(-8919, START.plusDays(2).minusNanos(1000), null, false, "PURCHASED", "last microsecond");
        for (long modelId : new long[]{-8901, -8902}) {
            market(modelId, "lower", 2, START);
            market(modelId, "upper", 2, START.plusDays(2).minusNanos(1000));
            market(modelId, "next-day", 2, START.plusDays(2));
            market(modelId, "old-generation", 1, START);
            market(modelId, "before-coverage", 2, START.minusNanos(1000));
            market(modelId, "undated", 2, null);
        }
    }

    @Test void allSourcesRangesSelectionsAndLegacyMappingsMatchThePreviousLoader() {
        ListingRepository wholeHistory = mock(ListingRepository.class);
        MarketListingObservationRepository wholeMarket = mock(MarketListingObservationRepository.class);
        when(wholeHistory.findAll(any(Specification.class))).thenReturn(listings.findAll());
        when(wholeMarket.findAll(any(Specification.class))).thenReturn(observations.findAll());
        var baseline = new AnalyticsDataLoader(wholeHistory, wholeMarket, states, models, resolver);
        var optimized = new AnalyticsDataLoader(listings, observations, states, models, resolver);
        for (var range : List.of(new AnalyticsTimeRange(START, END), new AnalyticsTimeRange(LocalDateTime.MIN, END))) {
            for (var source : AnalyticsSource.values()) {
                for (Set<Long> selected : List.of(Set.<Long>of(), Set.of(-8901L), Set.of(-8902L))) {
                    var before = baseline.load(selected, Set.of(), Set.of(), source, range);
                    var after = optimized.load(selected, Set.of(), Set.of(), source, range);
                    assertSameRows(before, after);
                    for (var granularity : AnalyticsGranularity.values()) {
                        assertEquals(AnalyticsReportBuilder.build(before, selected, range, granularity),
                                AnalyticsReportBuilder.build(after, selected, range, granularity));
                    }
                    assertSameRows(baseline.load(selected, Set.of(HistoryOutcome.REJECTED), Set.of(OfferAssessment.UNASSESSED), source, range),
                            optimized.load(selected, Set.of(HistoryOutcome.REJECTED), Set.of(OfferAssessment.UNASSESSED), source, range));
                }
            }
        }
    }

    @Test void sqlEliminatesOutOfRangeRowsAndAllTimeNeverBindsAnUnrepresentableMinimum() {
        var range = new AnalyticsTimeRange(START, END);
        var historyIds = listings.findAll(AnalyticsQueries.history(range)).stream().map(Listing::getId).toList();
        assertTrue(historyIds.containsAll(List.of(-8911L, -8912L, -8917L, -8919L)));
        assertFalse(historyIds.contains(-8913L)); assertFalse(historyIds.contains(-8914L));
        assertFalse(historyIds.contains(-8915L)); assertFalse(historyIds.contains(-8918L));
        assertTrue(listings.findAll(AnalyticsQueries.history(new AnalyticsTimeRange(LocalDateTime.MIN, END)))
                .stream().anyMatch(row -> row.getId().equals(-8918L)));
        var market = observations.findAll(AnalyticsQueries.market(Set.of(-8901L), range));
        assertEquals(3, market.size()); // Two current-generation rows plus one rejected later by coverage logic.
        assertTrue(market.stream().allMatch(row -> row.getModel().getId().equals(-8901L)));
        assertTrue(market.stream().anyMatch(row -> row.getMarketplaceListingId().equals("lower")));
        assertTrue(market.stream().anyMatch(row -> row.getMarketplaceListingId().equals("upper")));
    }

    private void assertSameRows(AnalyticsDataLoader.Dataset expected, AnalyticsDataLoader.Dataset actual) {
        // SQL index plans may return rows in a different order. Analytics calculations are order-independent.
        assertEquals(Set.copyOf(expected.history()), Set.copyOf(actual.history()));
        assertEquals(Set.copyOf(expected.market()), Set.copyOf(actual.market()));
        assertEquals(expected.historyByModel().keySet(), actual.historyByModel().keySet());
        for (long id : expected.historyByModel().keySet()) assertEquals(Set.copyOf(expected.historyByModel().get(id)), Set.copyOf(actual.historyByModel().get(id)));
        assertEquals(expected.marketByModel().keySet(), actual.marketByModel().keySet());
        for (long id : expected.marketByModel().keySet()) assertEquals(Set.copyOf(expected.marketByModel().get(id)), Set.copyOf(actual.marketByModel().get(id)));
    }

    private void history(long id, LocalDateTime candidate, LocalDateTime decision, boolean hidden, String status, String title) {
        jdbc.update("""
                insert into listing(id, bot_id, listing_id, title, url, original_price, current_price,
                    current_step, awaiting_seller_response, history_hidden, status, buy_candidate_at, decision_at)
                values (?, -8901, ?, ?, 'https://example.test/item', 100, 80, 1, false, ?, ?, ?, ?)
                """, id, "fixture" + id, title, hidden, status, candidate, decision);
    }
    private void market(long modelId, String id, int generation, LocalDateTime published) {
        jdbc.update("""
                insert into market_listing_observation(model_id, marketplace_listing_id, tracking_generation,
                    baseline, first_seen_at, last_seen_at, published_at, first_seen_price, latest_price)
                values (?, ?, ?, false, ?, ?, ?, 100, ?)
                """, modelId, id, generation, START, START.plusDays(2), published, id.equals("lower") ? 100 : 120);
    }
}
