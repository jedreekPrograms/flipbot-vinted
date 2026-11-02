package pl.flipbot.playwright.filters.catalog;

import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.filters.FilterPage;
import pl.flipbot.playwright.filters.FilterSelectors;

@Slf4j
@RequiredArgsConstructor
public final class SortFilter {
    private final Page page;
    private final FilterPage ui;
    private final CatalogUrlState url;

    public void applyNewestFirst() {
        try {
            ui.openFilter(FilterSelectors.SORT_BY);

            ui.clickSelector(FilterSelectors.SORT_BY_NEWEST);

            if (url.waitForValue("order", "newest_first", 4_000)) {
                log.info("[FILTER SORT] newest_first persisted through UI. " + "Current URL: {}", page.url());

                return;
            }
        } catch (RuntimeException exception) {
            if (page.isClosed()) {
                throw exception;
            }

            log.warn(
                    "[FILTER SORT] Could not apply newest_first through Vinted UI. "
                            + "Falling back to the catalog URL. reason={}",
                    exception.getMessage()
            );
        }

        String currentUrl = page.url();

        String correctedUrl = CatalogUrl.withParameter(currentUrl, "order", "newest_first");

        log.warn("[FILTER SORT] Applying newest_first URL fallback: {} -> {}", currentUrl, correctedUrl);

        page.navigate(correctedUrl);

        ui.waitForTimeout(1_000);

        if (!"newest_first".equals(url.parameter("order"))) {
            throw new IllegalStateException("Could not apply newest_first sorting. URL: " + page.url());
        }
    }
}
