package pl.flipbot.playwright.filters.catalog;

import com.microsoft.playwright.Page;
import org.junit.Test;
import pl.flipbot.playwright.filters.model.ModelFilter;
import pl.flipbot.playwright.model.BotConfigurationDto;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CatalogFilterVerifierTest {
    @Test
    public void verifiesSearchCategoryBrandAndNumericallyEqualPricesWithoutNativeModelCheck() {
        Page page = page("catalog[]=1&brand_ids[]=2&search_text=Galaxy+S25&price_from=100.00&price_to=700&order=newest_first");
        BotConfigurationDto configuration = configuration();
        configuration.setSearchQuery("  Galaxy   S25  ");
        configuration.setMinPrice(new BigDecimal("100"));
        configuration.setMaxPrice(new BigDecimal("700.00"));
        ModelFilter model = mock(ModelFilter.class);

        new CatalogFilterVerifier(page, new CatalogUrlState(page), model).verify(configuration, "SEARCH_QUERY");

        verifyNoInteractions(model);
    }

    @Test
    public void nativeModelVerificationRequiresThePreviouslyProvenExactCollection() {
        Page page = page("catalog[]=1&brand_ids[]=2&brand_collection_ids[]=9977&order=newest_first");
        BotConfigurationDto configuration = configuration();
        configuration.setModel("Galaxy S25 FE");
        ModelFilter model = mock(ModelFilter.class);
        new CatalogFilterVerifier(page, new CatalogUrlState(page), model).verify(configuration, "VINTED_MODEL");
        verify(model).requireVerifiedModelStillPersisted("Galaxy S25 FE");
    }

    @Test
    public void lostSearchQueryCannotPassFinalVerification() {
        Page page = page("catalog[]=1&brand_ids[]=2&search_text=Galaxy+S25+Ultra&order=newest_first");
        BotConfigurationDto configuration = configuration();
        configuration.setSearchQuery("Galaxy S25");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new CatalogFilterVerifier(page, new CatalogUrlState(page), mock(ModelFilter.class))
                        .verify(configuration, "SEARCH_QUERY"));
        assertTrue(error.getMessage().contains("lost SEARCH_QUERY"));
    }

    @Test
    public void lostCategoryBrandOrSortCannotPassFinalVerification() {
        for (String query : List.of("brand_ids[]=2&search_text=Galaxy&order=newest_first", "catalog[]=1&search_text=Galaxy&order=newest_first",
                "catalog[]=1&brand_ids[]=2&search_text=Galaxy&order=price_low_to_high")) {
            Page page = page(query);
            assertThrows(IllegalStateException.class,
                    () -> new CatalogFilterVerifier(page, new CatalogUrlState(page), mock(ModelFilter.class))
                            .verify(configuration(), "SEARCH_QUERY"));
        }
    }

    @Test
    public void priceGuardRejectsMissingInvalidAndChangedAmounts() {
        for (String value : List.of("", "abc", "101")) {
            Page page = page("price_to=" + value);
            assertThrows(IllegalStateException.class,
                    () -> new CatalogUrlState(page).requirePrice("price_to", new BigDecimal("100"), "maxPrice"));
        }
        Page page = page("order=newest_first");
        assertThrows(IllegalStateException.class,
                () -> new CatalogUrlState(page).requirePrice("price_to", new BigDecimal("100"), "maxPrice"));
    }

    private static Page page(String query) {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://www.vinted.pl/catalog?" + query);
        return page;
    }

    private static BotConfigurationDto configuration() {
        BotConfigurationDto configuration = new BotConfigurationDto();
        configuration.setCategoryPath(List.of("Elektronika", "Telefony"));
        configuration.setBrand("Samsung");
        configuration.setSearchQuery("Galaxy");
        return configuration;
    }
}
