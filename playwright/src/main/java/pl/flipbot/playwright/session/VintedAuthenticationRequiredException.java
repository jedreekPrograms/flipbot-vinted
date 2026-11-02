package pl.flipbot.playwright.session;

import com.microsoft.playwright.Page;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;

/** Authentication loss is bot-wide, but does not by itself prove CAPTCHA or a block. */
public class VintedAuthenticationRequiredException extends RuntimeException {
    public VintedAuthenticationRequiredException(String message) {
        super(message);
    }

    public static void throwIfRedirected(Page page, String operation) {
        if (MarketplaceUrls.isAuthenticationUrl(page.url())) {
            throw new VintedAuthenticationRequiredException(
                    "Vinted requires authentication while " + operation
                            + ". Stopping this bot's job and preserving its current page. url="
                            + page.url());
        }
    }
}
