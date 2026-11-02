package pl.flipbot.playwright.marketstats;

import com.microsoft.playwright.Page;

/** Validates classpath JavaScript assets in the real engine using local DOM fixtures. */
public final class MarketPublicationBrowserSmoke {
    private MarketPublicationBrowserSmoke() {}

    public static void verify(Page page) {
        try {
            page.setContent("""
                    <div>Dodane <span>2 dni temu</span></div>
                    <section data-testid="item-attributes-upload_date">
                      <time itemprop="upload_date" datetime="2026-10-15T08:30:00Z">wczoraj</time>
                    </section>
                    """);
            require("ISO|2026-10-15T08:30:00Z".equals(read(page)), "Exact upload date did not win over generic text");
            page.setContent("""
                    <section data-testid="item-attributes-upload_date"><span itemprop="upload_date">3 godziny temu</span></section>
                    """);
            require("REL|3 godziny temu".equals(read(page)), "Relative upload date was not extracted");
            page.setContent("""
                    <script type="application/ld+json">{"@type":"Product","datePublished":"2026-10-14T10:00:00Z"}</script>
                    <p>Normal listing fixture</p>
                    """);
            require("ISO|2026-10-14T10:00:00Z".equals(read(page)), "Structured publication fallback was lost");
            require(!Boolean.TRUE.equals(page.evaluate(MarketPublicationScripts.BLOCK_PAGE_SCRIPT)), "Normal item was classified as blocked");
            page.setContent("<p>Twoja sesja została zablokowana</p>");
            require(Boolean.TRUE.equals(page.evaluate(MarketPublicationScripts.BLOCK_PAGE_SCRIPT)), "Explicit block evidence was lost");
            page.setContent("""
                    <main><h1>Brak wyników</h1><input value="fixture-private-value"></main>
                    <nav data-testid="catalog-pagination" aria-label="Strony">1</nav>
                    <nav data-testid="pagination-hidden" style="display:none">hidden-fixture</nav>
                    """);
            String evidence = MarketCatalogPageDiagnostics.describe(page);
            require(evidence.contains("Brak wyników") && evidence.contains("\"cards\":0"),
                    "Empty-page diagnostics did not capture the public result state");
            require(evidence.contains("catalog-pagination") && !evidence.contains("hidden-fixture")
                            && !evidence.contains("fixture-private-value"),
                    "Catalog diagnostics included hidden navigation or form values");
            page.setContent("<main>" + "x".repeat(10_000) + "</main>");
            require(MarketCatalogPageDiagnostics.describe(page).length() < 1000,
                    "Catalog diagnostic text was not bounded");
            System.out.println("MARKET PUBLICATION BROWSER SMOKE PASS classpathAssets=true exactDatePriority=true "
                    + "relativeAndStructuredFallbacks=true explicitBlockOnly=true catalogDiagnosticsBounded=true localHtmlOnly=true");
        } finally {
            page.close();
        }
    }

    private static Object read(Page page) {
        return page.evaluate(MarketPublicationScripts.EXTRACT_PUBLISHED_AT_SCRIPT, "10632");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
