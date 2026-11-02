package pl.flipbot.playwright.filters.catalog;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.filters.FilterPage;
import pl.flipbot.playwright.filters.FilterSelectors;
import pl.flipbot.playwright.model.BotConfigurationDto;

@Slf4j
@RequiredArgsConstructor
public final class PriceFilter {
    private final Page page;
    private final FilterPage ui;
    private final CatalogUrlState url;

    public void apply(BotConfigurationDto configuration) {
        try {
            ui.openFilter(FilterSelectors.PRICE_FILTER);

            Locator inputToCommit = null;

            if (configuration.getMinPrice() != null) {
                ui.fillInputBySelector(FilterSelectors.MIN_PRICE, configuration.getMinPrice());

                inputToCommit = page.locator(FilterSelectors.MIN_PRICE);
            }

            if (configuration.getMaxPrice() != null) {
                ui.fillInputBySelector(FilterSelectors.MAX_PRICE, configuration.getMaxPrice());

                inputToCommit = page.locator(FilterSelectors.MAX_PRICE);
            }

            if (inputToCommit != null) {
                inputToCommit.press("Enter");

                ui.waitForTimeout(1_000);
            }

            ui.clickOutsideSafely();

            ui.waitForTimeout(1_000);
        } catch (RuntimeException exception) {
            if (page.isClosed()) {
                throw exception;
            }

            log.warn(
                    "[FILTER PRICE] Could not fully apply the configured price range through Vinted UI. "
                            + "Falling back to exact catalog URL parameters. reason={}",
                    exception.getMessage()
            );
        }

        String currentUrl = page.url();
        String correctedUrl = currentUrl;

        if (configuration.getMinPrice() != null
                && !url.priceMatches("price_from", configuration.getMinPrice())) {
            correctedUrl = CatalogUrl.withParameter(
                    correctedUrl,
                    "price_from",
                    configuration.getMinPrice().toPlainString()
            );
        }

        if (configuration.getMaxPrice() != null
                && !url.priceMatches("price_to", configuration.getMaxPrice())) {
            correctedUrl = CatalogUrl.withParameter(
                    correctedUrl,
                    "price_to",
                    configuration.getMaxPrice().toPlainString()
            );
        }

        if (!correctedUrl.equals(currentUrl)) {
            log.warn("[FILTER PRICE] Applying exact price URL fallback: {} -> {}", currentUrl, correctedUrl);

            page.navigate(correctedUrl);
            ui.waitForTimeout(1_000);
        }

        url.requirePrice("price_from", configuration.getMinPrice(), "minPrice");
        url.requirePrice("price_to", configuration.getMaxPrice(), "maxPrice");

        log.info("[FILTER PRICE] Price filter completed. Current URL: {}", page.url());
    }
}
