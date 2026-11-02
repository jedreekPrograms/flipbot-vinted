package pl.flipbot.playwright.marketstats;

import pl.flipbot.playwright.util.BrowserScripts;

/** Browser scripts are loaded once from the same classpath in Maven, IntelliJ and packaged builds. */
final class MarketPublicationScripts {
    static final String EXTRACT_PUBLISHED_AT_SCRIPT = BrowserScripts.read(MarketPublicationScripts.class, "read-published-at.js");
    static final String BLOCK_PAGE_SCRIPT = BrowserScripts.read(MarketPublicationScripts.class, "is-blocked-page.js");

    private MarketPublicationScripts() {}

}
