package pl.flipbot.playwright.filters;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.filters.brand.BrandFilter;
import pl.flipbot.playwright.filters.category.CategoryNavigator;
import pl.flipbot.playwright.filters.catalog.CatalogFilterVerifier;
import pl.flipbot.playwright.filters.catalog.CatalogUrlState;
import pl.flipbot.playwright.filters.catalog.PriceFilter;
import pl.flipbot.playwright.filters.catalog.SearchFilter;
import pl.flipbot.playwright.filters.catalog.SortFilter;
import pl.flipbot.playwright.filters.model.ModelFilter;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import java.util.Locale;

@Slf4j
public final class FilterService {

    private static final double URL_PERSIST_TIMEOUT_MS = 5_000;
    private final Page page;
    private final CategoryNavigator category;
    private final BrandFilter brand;
    private final ModelFilter model;
    private final SearchFilter search;
    private final PriceFilter price;
    private final SortFilter sort;
    private final CatalogUrlState url;
    private final CatalogFilterVerifier verifier;

    public FilterService(BotContext context) {
        this.page = context.getPage();
        FilterPage ui = new FilterPage(page);
        this.url = new CatalogUrlState(page);
        this.category = new CategoryNavigator(ui);
        this.brand = new BrandFilter(page, ui);
        this.model = new ModelFilter(page, ui);
        this.search = new SearchFilter(page, url);
        this.price = new PriceFilter(page, ui, url);
        this.sort = new SortFilter(page, ui, url);
        this.verifier = new CatalogFilterVerifier(page, url, model);
    }

    public void applyFilters(BotDetailsDto bot) {
        if (bot == null) {
            throw new IllegalArgumentException("Bot cannot be null");
        }
        BotConfigurationDto configuration = bot.getConfiguration();
        if (configuration == null) {
            throw new IllegalStateException("Bot configuration is missing");
        }
        String targetMode = resolveTargetMode(configuration);
        log.info("[FILTER CONFIG] Bot {} configuration received by Playwright. CategoryPath={}, "
                        + "brand='{}', targetMode={}, model='{}', searchQuery='{}', minPrice={}, maxPrice={}.",
                bot.getId(), configuration.getCategoryPath(), configuration.getBrand(), targetMode,
                configuration.getModel(), configuration.getSearchQuery(),
                configuration.getMinPrice(), configuration.getMaxPrice());

        // Enter reloads /catalog, so text search must precede the other filters.
        if ("SEARCH_QUERY".equals(targetMode)) {
            requireText(configuration.getSearchQuery(), "SEARCH_QUERY target mode requires a non-blank searchQuery");
            search.apply(configuration.getSearchQuery());
        }
        if (configuration.getCategoryPath() != null && !configuration.getCategoryPath().isEmpty()) {
            category.select(configuration.getCategoryPath());
            url.requireParameter("catalog[]", "Category", URL_PERSIST_TIMEOUT_MS);
        }
        if (hasText(configuration.getBrand())) {
            brand.apply(configuration.getBrand());
            url.requireParameter("brand_ids[]", "Brand", URL_PERSIST_TIMEOUT_MS);
        } else {
            log.warn("[FILTER] Bot {} has no configured brand.", bot.getId());
        }
        if ("VINTED_MODEL".equals(targetMode)) {
            requireText(configuration.getModel(), "VINTED_MODEL target mode requires a non-blank model");
            model.apply(configuration.getModel());
            url.requireParameter("brand_collection_ids[]", "Model", URL_PERSIST_TIMEOUT_MS);
        }
        if (configuration.getMinPrice() != null || configuration.getMaxPrice() != null) {
            price.apply(configuration);
        }
        sort.applyNewestFirst();
        verifier.verify(configuration, targetMode);
        log.info("[FILTER] Finished applying filters for bot {}. Final catalog URL: {}", bot.getId(), page.url());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalStateException(message);
        }
    }

    private static String resolveTargetMode(BotConfigurationDto configuration) {
        String raw = configuration.getTargetMode();
        if (!hasText(raw)) {
            return "VINTED_MODEL";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("VINTED_MODEL".equals(normalized) || "SEARCH_QUERY".equals(normalized)) {
            return normalized;
        }
        throw new IllegalStateException("Unsupported target mode: " + raw);
    }
}
