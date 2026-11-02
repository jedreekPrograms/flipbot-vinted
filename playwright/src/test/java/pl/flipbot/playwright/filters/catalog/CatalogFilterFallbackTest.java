package pl.flipbot.playwright.filters.catalog;

import com.microsoft.playwright.Page;
import org.junit.Test;
import pl.flipbot.playwright.filters.FilterPage;
import pl.flipbot.playwright.filters.FilterSelectors;
import pl.flipbot.playwright.model.BotConfigurationDto;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class CatalogFilterFallbackTest {
    @Test
    public void priceFallbackRetainsExactModelSearchAndSortWhenDrawerFails() {
        Fixture f = new Fixture("brand_collection_ids[]=9977&search_text=Galaxy+S25&order=newest_first&price_to=800#results");
        when(f.ui.openFilter(FilterSelectors.PRICE_FILTER)).thenThrow(new IllegalStateException("Drawer unavailable"));
        BotConfigurationDto configuration = new BotConfigurationDto();
        configuration.setMinPrice(new BigDecimal("100"));
        configuration.setMaxPrice(new BigDecimal("700"));

        new PriceFilter(f.page, f.ui, new CatalogUrlState(f.page)).apply(configuration);

        assertEquals("9977", CatalogUrl.parameter(f.current.get(), "brand_collection_ids[]"));
        assertEquals("Galaxy S25", CatalogUrl.parameter(f.current.get(), "search_text"));
        assertEquals("newest_first", CatalogUrl.parameter(f.current.get(), "order"));
        assertEquals("100", CatalogUrl.parameter(f.current.get(), "price_from"));
        assertEquals("700", CatalogUrl.parameter(f.current.get(), "price_to"));
        assertTrue(f.current.get().endsWith("#results"));
        verify(f.page, times(1)).navigate(anyString());
    }

    @Test
    public void closedPageFailureIsPropagatedWithoutUrlRecovery() {
        Fixture f = new Fixture("price_to=800");
        RuntimeException closed = new IllegalStateException("Page closed");
        when(f.ui.openFilter(FilterSelectors.PRICE_FILTER)).thenThrow(closed);
        when(f.page.isClosed()).thenReturn(true);
        assertSame(closed, assertThrows(IllegalStateException.class,
                () -> new PriceFilter(f.page, f.ui, new CatalogUrlState(f.page)).apply(new BotConfigurationDto())));
        verify(f.page, never()).navigate(anyString());
    }

    @Test
    public void sortFallbackRetainsModelAndPriceRange() {
        Fixture f = new Fixture("brand_collection_ids[]=9977&price_from=100&price_to=700&order=price_low_to_high");
        when(f.ui.openFilter(FilterSelectors.SORT_BY)).thenThrow(new IllegalStateException("Drawer unavailable"));
        new SortFilter(f.page, f.ui, new CatalogUrlState(f.page)).applyNewestFirst();
        assertEquals("newest_first", CatalogUrl.parameter(f.current.get(), "order"));
        assertEquals("9977", CatalogUrl.parameter(f.current.get(), "brand_collection_ids[]"));
        assertEquals("100", CatalogUrl.parameter(f.current.get(), "price_from"));
        assertEquals("700", CatalogUrl.parameter(f.current.get(), "price_to"));
    }

    private static final class Fixture {
        final Page page = mock(Page.class);
        final FilterPage ui = mock(FilterPage.class);
        final AtomicReference<String> current;

        Fixture(String query) {
            current = new AtomicReference<>("https://www.vinted.pl/catalog?" + query);
            when(page.url()).thenAnswer(call -> current.get());
            when(page.navigate(anyString())).thenAnswer(call -> {
                current.set(call.getArgument(0));
                return null;
            });
        }
    }
}
