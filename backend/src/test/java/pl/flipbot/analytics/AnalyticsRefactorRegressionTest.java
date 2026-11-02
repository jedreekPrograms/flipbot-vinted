package pl.flipbot.analytics;

import org.springframework.data.jpa.domain.Specification;
import org.junit.jupiter.api.Test;
import pl.flipbot.dashboard.DashboardPeriod;
import pl.flipbot.dictionary.DictionaryBrand;
import pl.flipbot.dictionary.DictionaryModel;
import pl.flipbot.dictionary.DictionaryModelRepository;
import pl.flipbot.listing.HistoryOutcome;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.listing.ListingStatus;
import pl.flipbot.listing.OfferAssessment;
import pl.flipbot.marketstats.MarketListingObservation;
import pl.flipbot.marketstats.MarketListingObservationRepository;
import pl.flipbot.marketstats.MarketModelScanState;
import pl.flipbot.marketstats.MarketModelScanStateRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsRefactorRegressionTest {
    @Test
    void resolvesEachHistoryModelOnceAndRetainsUnmappedHistoryInTheTotal() {
        Fixture f = new Fixture();
        Listing first = purchase(1L), second = purchase(2L), unmapped = purchase(3L);
        when(f.listings.findAll(any(Specification.class))).thenReturn(List.of(first, second, unmapped));
        when(f.resolver.resolveModelId(eq(first), anyList())).thenReturn(Optional.of(1L));
        when(f.resolver.resolveModelId(eq(second), anyList())).thenReturn(Optional.of(2L));
        var result = f.service().getOverview(DashboardPeriod.ALL, Set.of(), Set.of(), Set.of(), AnalyticsSource.HISTORY,
                AnalyticsGranularity.DAY, null, null);
        assertEquals(3, result.summary().purchasedCount());
        assertEquals(List.of(1L, 2L), result.modelBreakdowns().stream().map(row -> row.modelId()).toList());
        assertTrue(result.modelBreakdowns().stream().allMatch(row -> row.purchasedCount() == 1));
        verify(f.resolver, times(1)).resolveModelId(eq(first), anyList());
        verify(f.resolver, times(1)).resolveModelId(eq(second), anyList());
        verify(f.resolver, times(1)).resolveModelId(eq(unmapped), anyList());
        verify(f.observations, never()).findAll(any(Specification.class));
    }

    @Test
    void modelResolutionCacheEndsWithTheRequest() {
        Fixture f = new Fixture();
        Listing listing = purchase(1L);
        when(f.listings.findAll(any(Specification.class))).thenReturn(List.of(listing));
        when(f.resolver.resolveModelId(eq(listing), anyList())).thenReturn(Optional.of(1L), Optional.of(2L));
        AnalyticsService service = f.service();
        var first = service.getOverview(null, Set.of(), Set.of(), Set.of(), AnalyticsSource.HISTORY, null, null, null);
        var second = service.getOverview(null, Set.of(), Set.of(), Set.of(), AnalyticsSource.HISTORY, null, null, null);
        assertEquals(1L, first.modelBreakdowns().getFirst().modelId());
        assertEquals(2L, second.modelBreakdowns().getFirst().modelId());
        verify(f.resolver, times(2)).resolveModelId(eq(listing), anyList());
    }

    @Test
    void rejectedFilterIncludesMissedOpportunitiesAndExcludesHiddenHistory() {
        Fixture f = new Fixture();
        Listing missed = purchase(1L), hidden = purchase(2L), purchased = purchase(3L);
        missed.setStatus(ListingStatus.SKIPPED_BY_USER);
        missed.setHistoryOutcome(HistoryOutcome.MISSED_OPPORTUNITY);
        hidden.setHistoryHidden(true);
        when(f.listings.findAll(any(Specification.class))).thenReturn(List.of(missed, hidden, purchased));
        when(f.resolver.resolveModelId(eq(missed), anyList())).thenReturn(Optional.of(1L));
        var result = f.service().getOverview(null, Set.of(), Set.of(HistoryOutcome.REJECTED), Set.of(OfferAssessment.LEGIT),
                AnalyticsSource.HISTORY, null, null, null);
        assertEquals(1, result.summary().historyCount());
        assertEquals(1, result.summary().rejectedCount());
        assertEquals(1, result.summary().missedOpportunityCount());
        assertEquals(0, result.summary().purchasedCount());
        verify(f.resolver, never()).resolveModelId(eq(hidden), anyList());
        verify(f.resolver, never()).resolveModelId(eq(purchased), anyList());
    }

    @Test
    void marketRateIsSumOfPerModelRatesFromEachReliableBaseline() {
        Fixture f = new Fixture();
        when(f.states.findAll()).thenReturn(List.of(state(f.first, "2026-10-01T00:00:00", 1), state(f.second, "2026-10-02T00:00:00", 1)));
        when(f.observations.findAll(any(Specification.class))).thenReturn(List.of(
                observation(f.first, "2026-10-01T12:00:00", 1, "100", "80"),
                observation(f.first, "2026-10-02T12:00:00", 1, "100", "80"),
                observation(f.second, "2026-10-02T12:00:00", 1, "100", "80"),
                observation(f.second, "2026-10-02T13:00:00", 1, "100", "80")));
        var result = f.service().getOverview(null, Set.of(), Set.of(), Set.of(), AnalyticsSource.OBSERVER, null,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"));
        assertEquals(new BigDecimal("3.00"), result.summary().averageListingsPerDay());
        assertEquals(new BigDecimal("21.00"), result.summary().averageListingsPerWeek());
        assertEquals(new BigDecimal("91.32"), result.summary().averageListingsPerMonth());
        assertEquals(List.of(new BigDecimal("1.00"), new BigDecimal("2.00")),
                result.modelBreakdowns().stream().map(row -> row.averageListingsPerDay()).toList());
        verify(f.listings, never()).findAll(any(Specification.class));
    }

    @Test
    void marketCoverageExcludesOldGenerationsAndPreBaselineOrUndatedItems() {
        Fixture f = new Fixture();
        when(f.states.findAll()).thenReturn(List.of(state(f.first, "2026-10-02T00:00:00", 2)));
        when(f.observations.findAll(any(Specification.class))).thenReturn(List.of(
                observation(f.first, "2026-10-03T12:00:00", 1, "900", "800"),
                observation(f.first, "2026-10-01T12:00:00", 2, "900", "800"),
                observation(f.first, null, 2, "900", "800"),
                observation(f.first, "2026-10-02T12:00:00", 2, null, "150"),
                observation(f.first, "2026-10-03T12:00:00", 2, "100", "50")));
        var result = f.service().getOverview(null, Set.of(), Set.of(), Set.of(), AnalyticsSource.OBSERVER, null,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-04"));
        assertEquals(2, result.summary().marketListingCount());
        assertEquals(new BigDecimal("125.00"), result.summary().medianMarketPrice());
    }

    @Test
    void unknownModelIsRejectedBeforeHistoryOrObservationQueries() {
        Fixture f = new Fixture();
        assertThrows(NoSuchElementException.class, () -> f.service().getOverview(null, Set.of(99L), Set.of(), Set.of(), null, null, null, null));
        verify(f.listings, never()).findAll(any(Specification.class));
        verify(f.observations, never()).findAll(any(Specification.class));
    }

    @Test
    void explicitDateRangeIncludesWholeLastDayAndUsesOneClockForRelativeDates() {
        LocalDateTime now = LocalDateTime.parse("2026-10-16T00:00:00.000000001");
        var range = AnalyticsTimeRange.resolveRange(DashboardPeriod.ALL, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"), now);
        assertTrue(range.contains(LocalDateTime.parse("2026-10-02T23:59:59.999999999")));
        assertFalse(range.contains(LocalDateTime.parse("2026-10-03T00:00:00")));
        assertEquals(LocalDate.parse("2026-10-16").atStartOfDay(), AnalyticsTimeRange.resolveRange(DashboardPeriod.TODAY, null, null, now).from());
        assertThrows(IllegalArgumentException.class, () -> AnalyticsTimeRange.resolveRange(DashboardPeriod.ALL,
                LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-02"), now));
    }

    @Test
    void yearlyTimelineRetainsAnEmptyYearBetweenObservedYears() {
        Fixture f = new Fixture();
        when(f.states.findAll()).thenReturn(List.of(state(f.first, "2024-01-01T00:00:00", 1)));
        when(f.observations.findAll(any(Specification.class))).thenReturn(List.of(observation(f.first, "2024-10-01T12:00:00", 1, "100", "100"),
                observation(f.first, "2026-10-01T12:00:00", 1, "300", "300")));
        var result = f.service().getOverview(null, Set.of(), Set.of(), Set.of(), AnalyticsSource.OBSERVER,
                AnalyticsGranularity.YEAR, LocalDate.parse("2024-01-01"), LocalDate.parse("2026-12-31"));
        assertEquals(List.of("2024", "2025", "2026"), result.timeline().stream().map(point -> point.label()).toList());
        assertEquals(0L, result.timeline().get(1).marketListingCount());
        assertNull(result.timeline().get(1).medianMarketPrice());
    }

    private static Listing purchase(Long id) {
        return Listing.builder().id(id).listingId(String.valueOf(id)).status(ListingStatus.PURCHASED)
                .buyCandidateAt(LocalDateTime.parse("2026-10-01T12:00:00")).historyOutcome(HistoryOutcome.PURCHASED)
                .offerAssessment(OfferAssessment.LEGIT).currentPrice(new BigDecimal("100")).build();
    }
    private static MarketModelScanState state(DictionaryModel model, String baseline, int generation) {
        return MarketModelScanState.builder().modelId(model.getId()).model(model).trackingGeneration(generation)
                .baselineCompleteAt(LocalDateTime.parse(baseline)).build();
    }
    private static MarketListingObservation observation(DictionaryModel model, String time, int generation, String first, String latest) {
        return MarketListingObservation.builder().model(model).trackingGeneration(generation)
                .publishedAt(time == null ? null : LocalDateTime.parse(time))
                .firstSeenPrice(first == null ? null : new BigDecimal(first)).latestPrice(new BigDecimal(latest)).build();
    }
    private static final class Fixture {
        final ListingRepository listings = mock(ListingRepository.class);
        final MarketListingObservationRepository observations = mock(MarketListingObservationRepository.class);
        final MarketModelScanStateRepository states = mock(MarketModelScanStateRepository.class);
        final DictionaryModelRepository models = mock(DictionaryModelRepository.class);
        final HistoryModelResolver resolver = mock(HistoryModelResolver.class);
        final DictionaryModel first = model(1L, "Galaxy S25");
        final DictionaryModel second = model(2L, "Galaxy S26");
        Fixture() {
            when(models.findAll()).thenReturn(List.of(first, second));
            when(listings.findAll(any(Specification.class))).thenReturn(List.of());
            when(observations.findAll(any(Specification.class))).thenReturn(List.of());
            when(states.findAll()).thenReturn(List.of());
        }
        AnalyticsService service() { return new AnalyticsService(listings, observations, states, models, resolver); }
        private static DictionaryModel model(Long id, String name) {
            return DictionaryModel.builder().id(id).name(name).brand(DictionaryBrand.builder().id(1L).name("Samsung").build()).build();
        }
    }
}
