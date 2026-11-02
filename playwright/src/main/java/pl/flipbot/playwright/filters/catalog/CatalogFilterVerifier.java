package pl.flipbot.playwright.filters.catalog;

import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.filters.model.ModelFilter;
import pl.flipbot.playwright.model.BotConfigurationDto;

@Slf4j
@RequiredArgsConstructor
public final class CatalogFilterVerifier {
    private final Page page;
    private final CatalogUrlState url;
    private final ModelFilter modelFilter;

    public void verify(BotConfigurationDto configuration, String targetMode) {
        if ("SEARCH_QUERY".equals(targetMode)) {
            String expectedSearchQuery = SearchFilter.normalizeQuery(configuration.getSearchQuery());

            String actualSearchQuery = url.parameter("search_text");

            if (!expectedSearchQuery.equals(actualSearchQuery)) {
                throw new IllegalStateException(
                        "Final Vinted catalog URL lost SEARCH_QUERY. "
                                + "Expected search_text='"
                                + expectedSearchQuery
                                + "', actual='"
                                + actualSearchQuery
                                + "'. URL: "
                                + page.url()
                );
            }
        }

        if (
                configuration.getCategoryPath() != null && !configuration.getCategoryPath().isEmpty()
                        && !url.hasParameter("catalog[]")
        ) {
            throw new IllegalStateException(
                    "Final Vinted catalog URL does not contain a category filter. "
                            + "URL: "
                            + page.url()
            );
        }

        if (
                configuration.getBrand() != null && !configuration.getBrand().isBlank()
                        && !url.hasParameter("brand_ids[]")
        ) {
            throw new IllegalStateException(
                    "Final Vinted catalog URL does not contain a brand filter. "
                            + "Configured brand: '"
                            + configuration.getBrand()
                            + "'. URL: "
                            + page.url()
            );
        }

        if ("VINTED_MODEL".equals(targetMode)) {
            modelFilter.requireVerifiedModelStillPersisted(configuration.getModel());
        }

        url.requirePrice("price_from", configuration.getMinPrice(), "minPrice");
        url.requirePrice("price_to", configuration.getMaxPrice(), "maxPrice");

        String order = url.parameter("order");

        if (!"newest_first".equals(order)) {
            throw new IllegalStateException(
                    "Final Vinted catalog URL does not use newest_first sorting. "
                            + "Actual order='"
                            + order
                            + "'. URL: "
                            + page.url()
            );
        }

        log.info(
                "[FILTER VERIFY] Final filters verified. "
                        + "targetMode={}, searchQuery='{}', category={}, "
                        + "brand='{}', model='{}', minPrice={}, maxPrice={}, "
                        + "order={}.",
                targetMode,
                configuration.getSearchQuery(),
                configuration.getCategoryPath(),
                configuration.getBrand(),
                configuration.getModel(),
                configuration.getMinPrice(),
                configuration.getMaxPrice(),
                order
        );
    }
}
