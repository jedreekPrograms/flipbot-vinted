package pl.flipbot.playwright.marketstats;

import com.microsoft.playwright.Page;

/** Bounded public-catalog DOM evidence; no HTML, cookies, form values or requests. */
final class MarketCatalogPageDiagnostics {
    private static final String SCRIPT = """
            () => {
                const text = (value, limit) => String(value ?? '').replace(/\\s+/g, ' ').trim().slice(0, limit);
                const visible = element => Boolean(element.getClientRects().length)
                    && getComputedStyle(element).visibility !== 'hidden';
                const main = document.querySelector('main');
                return JSON.stringify({
                    title: text(document.title, 120),
                    cards: document.querySelectorAll("[data-testid^='product-item-id-']:not([data-testid*='--'])").length,
                    mainText: text(main?.innerText, 700),
                    navigation: Array.from(document.querySelectorAll(
                        "[data-testid*='pagin'],nav[aria-label],a[rel='next'],a[rel='prev']"
                    )).filter(visible).slice(0, 6).map(element => ({
                        testId: text(element.getAttribute('data-testid'), 80),
                        label: text(element.getAttribute('aria-label'), 80),
                        text: text(element.innerText, 80),
                        disabled: element.hasAttribute('disabled') || element.getAttribute('aria-disabled') === 'true'
                    }))
                });
            }
            """;

    private MarketCatalogPageDiagnostics() {}

    static String describe(Page page) {
        try {
            Object value = page.evaluate(SCRIPT);
            return value instanceof String text ? text.substring(0, Math.min(text.length(), 3000)) : "unavailable";
        } catch (RuntimeException exception) {
            return "unavailable:" + exception.getClass().getSimpleName();
        }
    }
}
