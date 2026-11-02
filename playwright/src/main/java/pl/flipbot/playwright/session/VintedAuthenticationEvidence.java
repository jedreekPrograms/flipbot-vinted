package pl.flipbot.playwright.session;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;

import java.net.URI;

/** Shared positive evidence for login and session persistence. */
public final class VintedAuthenticationEvidence {
    private VintedAuthenticationEvidence() {
    }

    public static String signal(Page page) {
        if (page == null || page.isClosed()) {
            return null;
        }
        String url = page.url();
        if (!MarketplaceUrls.isVintedUrl(url)
                || MarketplaceUrls.isAuthenticationUrl(url)
                || MarketplaceUrls.isSessionRefreshUrl(url)) {
            return null;
        }
        // A stale header behind an auth modal does not prove that login finished.
        if (hasVisible(page.getByTestId("header--login-button"))
                || hasVisible(page.locator("#username, #password"))) {
            return null;
        }
        if (hasVisible(page.getByTestId("header-conversations-button"))) {
            return "header-conversations-button";
        }

        Locator candidates = page.locator("a[href*='/inbox']:visible");
        if (candidates == null) {
            return null;
        }
        for (int index = 0; index < Math.min(candidates.count(), 20); index++) {
            try {
                Locator link = candidates.nth(index);
                if (!link.isVisible()) {
                    continue;
                }
                String href = link.getAttribute("href",
                        new Locator.GetAttributeOptions().setTimeout(300));
                if (href != null && MarketplaceUrls.isInboxUrl(
                        URI.create(url).resolve(href).toString())) {
                    return "visible /inbox link";
                }
            } catch (RuntimeException ignored) {
                // A departing DOM node or malformed href is not evidence.
            }
        }
        return null;
    }

    private static boolean hasVisible(Locator locator) {
        if (locator == null) {
            return false;
        }
        try {
            for (int index = 0; index < Math.min(locator.count(), 20); index++) {
                if (locator.nth(index).isVisible()) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }
}
