package pl.flipbot.playwright.filters.catalog;

import org.junit.Test;

import static org.junit.Assert.*;

public class CatalogUrlTest {
    @Test
    public void decodesFilterNamesAndSearchValuesWithoutReadingFragment() {
        String url = "https://www.vinted.pl/catalog?catalog%5B%5D=123&search_text=telefon+Galaxy%2B#price_to=9";
        assertEquals("123", CatalogUrl.parameter(url, "catalog[]"));
        assertEquals("telefon Galaxy+", CatalogUrl.parameter(url, "search_text"));
        assertNull(CatalogUrl.parameter(url, "price_to"));
    }

    @Test
    public void distinguishesMissingAndEmptyParametersAndKeepsFirstValue() {
        String url = "https://www.vinted.pl/catalog?brand_ids[]=7&brand_ids[]=8&empty&blank=";
        assertEquals("7", CatalogUrl.parameter(url, "brand_ids[]"));
        assertEquals("", CatalogUrl.parameter(url, "empty"));
        assertEquals("", CatalogUrl.parameter(url, "blank"));
        assertNull(CatalogUrl.parameter(url, "catalog[]"));
        assertNull(CatalogUrl.parameter("https://www.vinted.pl/catalog", "catalog[]"));
    }

    @Test
    public void replacingPricePreservesModelSearchAndFragment() {
        String url = "https://www.vinted.pl/catalog?brand_collection_ids[]=9977&price_to=800&search_text=S25#results";
        assertEquals("https://www.vinted.pl/catalog?brand_collection_ids[]=9977&price_to=700&search_text=S25#results",
                CatalogUrl.withParameter(url, "price_to", "700"));
    }

    @Test
    public void appendsParametersBeforeFragmentWithOrWithoutExistingQuery() {
        assertEquals("https://www.vinted.pl/catalog?order=newest_first#results",
                CatalogUrl.withParameter("https://www.vinted.pl/catalog#results", "order", "newest_first"));
        assertEquals("https://www.vinted.pl/catalog?catalog[]=7&price_from=100#results",
                CatalogUrl.withParameter("https://www.vinted.pl/catalog?catalog[]=7#results", "price_from", "100"));
    }
}
