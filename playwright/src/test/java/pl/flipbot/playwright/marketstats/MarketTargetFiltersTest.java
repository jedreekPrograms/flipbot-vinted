package pl.flipbot.playwright.marketstats;

import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.filters.FilterService;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;
import pl.flipbot.playwright.marketstats.dto.MarketStatsTargetDto;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class MarketTargetFiltersTest {
    @Test
    public void nativeModelPlanKeepsCategoryAndPriceRangeAndTrustsOnlyExactFiltering() {
        try (MockedConstruction<FilterService> filters = mockConstruction(FilterService.class);
             MockedConstruction<MarketplaceNavigator> navigation = mockConstruction(MarketplaceNavigator.class)) {
            MarketTargetFilters.PreparedScan plan = filters().prepareScan(mock(BotContext.class), observer(), target("VINTED_MODEL", true));
            assertTrue(plan.trustsExactModelFilter());
            assertFalse(plan.usedFallback());
            assertFalse(plan.accessoryFiltering());
            BotConfigurationDto configuration = plan.scanBot().getConfiguration();
            assertEquals("Galaxy S25", configuration.getModel());
            assertNull(configuration.getSearchQuery());
            assertEquals(List.of("Elektronika", "Telefony"), configuration.getCategoryPath());
            assertPrices(configuration);
        }
    }

    @Test
    public void nativeModelWithoutCategoryNeverTriesTextSearch() {
        try (MockedConstruction<FilterService> services = mockConstruction(FilterService.class)) {
            assertThrows(IllegalStateException.class,
                    () -> filters().prepareScan(mock(BotContext.class), observer(), target("VINTED_MODEL", false)));
            assertTrue(services.constructed().isEmpty());
        }
    }

    @Test
    public void failedExactModelFilterNeverFallsBackToBroaderSearch() {
        RuntimeException failure = new IllegalStateException("Model drawer unavailable");
        try (MockedConstruction<FilterService> services = mockConstruction(FilterService.class,
                     (service, ignored) -> doThrow(failure).when(service).applyFilters(any()));
             MockedConstruction<MarketplaceNavigator> navigation = mockConstruction(MarketplaceNavigator.class)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> filters().prepareScan(mock(BotContext.class), observer(), target("VINTED_MODEL", true)));
            assertSame(failure, error.getCause());
            assertEquals(1, services.constructed().size());
        }
    }

    @Test
    public void textFallbackRetainsPriceRangeAndFiltersAccessories() {
        try (MockedConstruction<FilterService> services = mockConstruction(FilterService.class,
                     (service, construction) -> {
                         if (construction.getCount() == 1) {
                             doThrow(new IllegalStateException("Brand drawer unavailable")).when(service).applyFilters(any());
                         }
                     });
             MockedConstruction<MarketplaceNavigator> navigation = mockConstruction(MarketplaceNavigator.class)) {
            MarketTargetFilters.PreparedScan plan = filters().prepareScan(mock(BotContext.class), observer(), target("SEARCH_QUERY", true));
            assertTrue(plan.usedFallback());
            assertTrue(plan.accessoryFiltering());
            assertFalse(plan.trustsExactModelFilter());
            BotConfigurationDto configuration = plan.scanBot().getConfiguration();
            assertEquals("Samsung Galaxy S25", configuration.getSearchQuery());
            assertNull(configuration.getBrand());
            assertNull(configuration.getModel());
            assertTrue(configuration.getCategoryPath().isEmpty());
            assertPrices(configuration);
            assertEquals(2, services.constructed().size());
        }
    }

    @Test
    public void trafficBackoffOrInterruptionPreventsFallbackEvenInTextMode() {
        for (RuntimeException failure : List.of(
                new IllegalStateException(MarketListingPublishedAtResolver.TRAFFIC_BACKOFF_MARKER),
                new IllegalStateException(new InterruptedException("Stop observer")))) {
            try (MockedConstruction<FilterService> services = mockConstruction(FilterService.class,
                         (service, ignored) -> doThrow(failure).when(service).applyFilters(any()));
                 MockedConstruction<MarketplaceNavigator> navigation = mockConstruction(MarketplaceNavigator.class)) {
                assertSame(failure, assertThrows(IllegalStateException.class,
                        () -> filters().prepareScan(mock(BotContext.class), observer(), target("SEARCH_QUERY", true))));
                assertEquals(1, services.constructed().size());
            }
        }
    }

    private static MarketTargetFilters filters() {
        return new MarketTargetFilters(mock(MarketObserverPage.class));
    }

    private static BotDetailsDto observer() {
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(0L);
        return bot;
    }

    private static MarketStatsTargetDto target(String mode, boolean category) {
        return new MarketStatsTargetDto(1L, "Samsung", "Galaxy S25", mode,
                category ? List.of("Elektronika", "Telefony") : List.of(), category,
                new BigDecimal("100"), new BigDecimal("700"), 1);
    }

    private static void assertPrices(BotConfigurationDto configuration) {
        assertEquals(new BigDecimal("100"), configuration.getMinPrice());
        assertEquals(new BigDecimal("700"), configuration.getMaxPrice());
    }
}
