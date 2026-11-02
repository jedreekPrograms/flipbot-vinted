package pl.flipbot.playwright.filters;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.filters.catalog.CatalogUrl;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;

/** Exercises production locators against local HTML. Every HTTP request is intercepted. */
public final class FilterBrowserSmoke {
    private FilterBrowserSmoke() {}

    public static void verify(BrowserContext browser) {
        browser.route("**/*", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html").setBody(FIXTURE)));
        Page page = browser.newPage();
        try {
            BotContext context = mock(BotContext.class);
            when(context.getPage()).thenReturn(page);
            BotDetailsDto bot = new BotDetailsDto();
            bot.setId(0L);
            BotConfigurationDto configuration = new BotConfigurationDto();
            configuration.setCategoryPath(List.of("Elektronika", "Telefony"));
            configuration.setBrand("Samsung");
            configuration.setModel("Galaxy S25");
            configuration.setMinPrice(new BigDecimal("100"));
            configuration.setMaxPrice(new BigDecimal("700"));
            bot.setConfiguration(configuration);

            page.navigate("https://www.vinted.pl/catalog");
            configuration.setTargetMode("VINTED_MODEL");
            new FilterService(context).applyFilters(bot);
            require("10632".equals(CatalogUrl.parameter(page.url(), "brand_collection_ids[]")), "Exact model was lost");
            require(page.locator("input[value='10632']").isChecked(), "Exact model checkbox was not checked");
            require(!page.locator("input[value='10633']").isChecked(), "Similar Ultra model was selected");
            require(((Number) page.evaluate("window.modelClicks")).intValue() == 1, "Exact model was toggled twice");

            // Older row/label markup, a hidden duplicate and a larger native list.
            page.navigate("https://www.vinted.pl/catalog");
            page.evaluate("""
                    () => {
                        const row = document.querySelector('[data-testid="selectable-item-brand_collection-10632"]');
                        const duplicate = row.cloneNode(true); duplicate.hidden = true;
                        row.parentElement.prepend(duplicate);
                        row.querySelector('[data-testid$="--title"]').removeAttribute('data-testid');
                        row.setAttribute('aria-label', 'Galaxy S25');
                        for (let i = 0; i < 75; i++) {
                            const other = document.createElement('div'); other.setAttribute('role', 'button');
                            other.dataset.testid = 'selectable-item-brand_collection-' + (20000 + i);
                            other.innerHTML = '<span data-testid="selectable-item-brand_collection-' + (20000 + i)
                                + '--title">Other model ' + i + '</span>';
                            row.parentElement.prepend(other);
                        }
                    }
                    """);
            new FilterService(context).applyFilters(bot);
            require("10632".equals(CatalogUrl.parameter(page.url(), "brand_collection_ids[]")), "Fallback model was lost");
            require(page.locator("input[value='10632']").nth(1).isChecked(), "Visible fallback row was not checked");
            require(!page.locator("input[value='10632']").first().isChecked(), "Hidden duplicate was selected");
            require(!page.locator("input[value='10633']").isChecked(), "Fallback selected a similar model");

            pl.flipbot.playwright.filters.model.ModelSnapshotBrowserSmoke.verify(browser);

            page.navigate("https://www.vinted.pl/catalog");
            configuration.setTargetMode("SEARCH_QUERY");
            configuration.setSearchQuery("  Galaxy   S25  ");
            new FilterService(context).applyFilters(bot);
            require("Galaxy S25".equals(CatalogUrl.parameter(page.url(), "search_text")), "Text query was lost");
            require(CatalogUrl.parameter(page.url(), "brand_collection_ids[]") == null, "Text search selected a native model");
            require(((Number) page.evaluate("window.modelClicks")).intValue() == 0, "Native model was clicked in text mode");
            System.out.println("FILTER BROWSER SMOKE PASS exactModel=true similarModelUntouched=true searchMode=true "
                    + "categoryBrandPriceSortPreserved=true batchedModelFallback=true allRequestsFulfilledLocally=true");
        } finally {
            page.close();
            browser.unroute("**/*");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final String FIXTURE = """
            <!doctype html><meta charset="utf-8">
            <script>
              window.modelClicks = 0;
              let active = '';
              function parameter(name, value) {
                const url = new URL(location.href); url.searchParams.set(name, value);
                history.replaceState(null, '', url);
              }
              function openPanel(name) {
                document.querySelectorAll('[data-panel]').forEach(p => p.hidden = p.dataset.panel !== name);
                active = name; document.querySelector('#confirm').hidden = !['brand', 'model'].includes(name);
              }
              function confirmFilter() {
                if (active === 'brand') parameter('brand_ids[]', '2');
                if (active === 'model') parameter('brand_collection_ids[]', '10632');
                openPanel('');
              }
              function commitPrice(event) {
                if (event.key === 'Enter') {
                  parameter('price_from', document.querySelector('#price_from').value);
                  parameter('price_to', document.querySelector('#price_to').value);
                }
              }
            </script>
            <header><form action="/catalog" onsubmit="event.preventDefault(); parameter('search_text', this.search_text.value)">
              <input name="search_text"><button type="submit">Szukaj</button>
            </form></header>
            <button data-testid="catalog--catalog-filter--trigger" onclick="openPanel('category')">Kategoria</button>
            <button data-testid="catalog--brand-filter--trigger" onclick="openPanel('brand')">Marka</button>
            <button data-testid="catalog--brand_collection-filter--trigger" onclick="openPanel('model')">Model</button>
            <button data-testid="catalog--price-filter--trigger" onclick="openPanel('price')">Cena</button>
            <button data-testid="catalog--sort-filter--trigger" onclick="openPanel('sort')">Sortuj</button>
            <section data-panel="category" hidden>
              <button>Elektronika</button><button onclick="parameter('catalog[]', '1'); openPanel('')">Telefony</button>
            </section>
            <section data-panel="brand" hidden><button>Samsung</button></section>
            <section data-panel="model" hidden>
              <div role="button" data-testid="selectable-item-brand_collection-10632">
                <span data-testid="selectable-item-brand_collection-10632--title">Galaxy S25</span>
                <span data-testid="selectable-item-brand_collection-10632--suffix"
                      onclick="window.modelClicks++; this.querySelector('input').checked = true">
                  <input type="checkbox" name="brand_collection_ids[]" value="10632" style="pointer-events:none">
                </span>
              </div>
              <div role="button" data-testid="selectable-item-brand_collection-10633">
                <span data-testid="selectable-item-brand_collection-10633--title">Galaxy S25 Ultra</span>
                <input type="checkbox" name="brand_collection_ids[]" value="10633">
              </div>
            </section>
            <section data-panel="price" hidden>
              <input id="price_from" onkeydown="commitPrice(event)"><input id="price_to" onkeydown="commitPrice(event)">
            </section>
            <section data-panel="sort" hidden>
              <button id="sort_by-list-item-newest_first" onclick="parameter('order', 'newest_first'); openPanel('')">Najnowsze</button>
            </section>
            <button id="confirm" data-testid="filter-selection-button" onclick="confirmFilter()" hidden>Potwierdź</button>
            """;
}
