package pl.flipbot.playwright.filters.catalog;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public final class SearchFilter {
    private static final String VINTED_SEARCH_INPUT_SELECTOR =
            "form[action='/catalog'] input[name='search_text']";

    private final Page page;
    private final CatalogUrlState url;

    public void apply(String configuredQuery) {
        String searchQuery = normalizeQuery(configuredQuery);

        log.info("[FILTER SEARCH] Applying text search query: '{}'.", searchQuery);

        Locator searchInput = resolveVisibleSearchInput();

        searchInput.waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(5_000)
        );

        log.info(
                "[FILTER SEARCH] Search input found. "
                        + "Placeholder='{}', current value='{}'.",
                searchInput.getAttribute("placeholder"),
                searchInput.inputValue()
        );

        searchInput.click();

        searchInput.fill(searchQuery);

        String enteredValue = searchInput.inputValue();

        if (!searchQuery.equals(enteredValue)) {
            throw new IllegalStateException(
                    "Vinted search input contains unexpected value. "
                            + "Expected: '"
                            + searchQuery
                            + "', actual: '"
                            + enteredValue
                            + "'."
            );
        }

        log.info("[FILTER SEARCH] Search query entered successfully. " + "Input value='{}'.", enteredValue);

        searchInput.press("Enter");

        waitForSearchQueryInUrl(searchQuery);

        log.info("[FILTER SEARCH] Search query submitted successfully. " + "Current URL: {}", page.url());
    }

    private Locator resolveVisibleSearchInput() {
        Locator visibleHeaderInputs = page.locator("header " + VINTED_SEARCH_INPUT_SELECTOR + ":visible");

        int visibleHeaderCount = visibleHeaderInputs.count();

        if (visibleHeaderCount > 0) {
            log.info(
                    "[FILTER SEARCH] Found {} visible search input(s) "
                            + "inside <header>. Using the first one.",
                    visibleHeaderCount
            );

            return visibleHeaderInputs.first();
        }

        Locator visibleSearchInputs = page.locator(VINTED_SEARCH_INPUT_SELECTOR + ":visible");

        int visibleSearchInputCount = visibleSearchInputs.count();

        log.info(
                "[FILTER SEARCH] No visible header search input found. "
                        + "Visible /catalog search inputs: {}.",
                visibleSearchInputCount
        );

        if (visibleSearchInputCount == 0) {
            int allSearchInputCount = page.locator(VINTED_SEARCH_INPUT_SELECTOR).count();

            throw new IllegalStateException(
                    "Vinted search input is not visible. "
                            + "Matching /catalog search inputs in DOM: "
                            + allSearchInputCount
            );
        }

        return visibleSearchInputs.first();
    }

    private void waitForSearchQueryInUrl(String expectedSearchQuery) {
        final int maxAttempts = 20;

        final double delayMilliseconds = 250;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String currentSearchQuery = url.parameter("search_text");

            if (expectedSearchQuery.equals(currentSearchQuery)) {
                return;
            }

            page.waitForTimeout(delayMilliseconds);
        }

        throw new IllegalStateException(
                "Vinted did not submit the expected search query. "
                        + "Expected search_text='"
                        + expectedSearchQuery
                        + "', current URL: "
                        + page.url()
        );
    }

    public static String normalizeQuery(String searchQuery) {
        if (searchQuery == null) {
            return "";
        }

        return searchQuery
                .trim()
                .replaceAll("\\s+", " ");
    }
}
