package pl.flipbot.playwright.verification;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Dismisses optional Vinted/CMP cookie consent overlays before normal bot UI
 * interactions. The handler is deliberately best-effort: absence or a transient
 * DOM change is not a bot failure, while a visible matching control is clicked
 * immediately so it cannot intercept negotiation/catalog actions.
 */
@Slf4j
public class CookieConsentHandler {

    private static final int MAX_BUTTONS_TO_INSPECT = 8;
    private static final double LABEL_READ_TIMEOUT_MS = 300;

    private static final List<String> ACCEPT_ALL_LABELS = List.of(
            "zgoda na wszystkie",
            "akceptuj wszystkie",
            "zaakceptuj wszystkie",
            "accept all",
            "accept all cookies",
            "allow all",
            "allow all cookies",
            "agree to all",
            "alle akzeptieren",
            "alles akzeptieren"
    );

    private static final Pattern ACCEPT_ALL_NAME = Pattern.compile(
            "^\\s*(?:" + String.join("|", ACCEPT_ALL_LABELS).replace(" ", "\\s+") + ")\\s*$",
            // Playwright serializes Java Patterns to JavaScript and rejects
            // UNICODE_CASE, even though Java's own matcher accepts that flag.
            Pattern.CASE_INSENSITIVE
    );

    public boolean acceptAllIfVisible(Page page) {
        Objects.requireNonNull(page, "Page cannot be null");

        if (page.isClosed()) {
            return false;
        }

        try {
            Locator oneTrust = page.locator("#onetrust-accept-btn-handler").first();
            if (clickIfVisible(oneTrust, "#onetrust-accept-btn-handler")) {
                return true;
            }

            // Resolve only explicit accept-all controls in the browser. Walking
            // every header/filter button added many round trips, and a node
            // disappearing before innerText() could consume the default 30s.
            Locator buttons = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(ACCEPT_ALL_NAME));
            int count = Math.min(buttons.count(), MAX_BUTTONS_TO_INSPECT);

            for (int index = 0; index < count; index++) {
                Locator candidate = buttons.nth(index);
                if (!isVisible(candidate)) {
                    continue;
                }

                String label = normalizedLabel(candidate);
                if (!isAcceptAllLabel(label)) {
                    continue;
                }

                if (clickIfVisible(candidate, label)) {
                    return true;
                }
            }
        } catch (PlaywrightException exception) {
            log.debug("[COOKIE CONSENT] Optional consent probe could not complete.");
        } catch (RuntimeException exception) {
            log.debug("[COOKIE CONSENT] Optional consent banner probe failed: {}", exception.getMessage());
        }

        return false;
    }

    static boolean isAcceptAllLabel(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return false;
        }

        for (String expected : ACCEPT_ALL_LABELS) {
            if (normalized.equals(expected)) {
                return true;
            }
        }

        return false;
    }

    private boolean clickIfVisible(Locator locator, String description) {
        if (!isVisible(locator)) {
            return false;
        }

        try {
            if (!CookieConsentClickBudget.reserve(locator)) {
                return false;
            }
            locator.click(new Locator.ClickOptions().setTimeout(2_000));
            log.info("[COOKIE CONSENT] Accepted optional cookie/privacy banner using '{}'.", description);
            return true;
        } catch (PlaywrightException exception) {
            log.debug("[COOKIE CONSENT] Matching control '{}' changed before click.", description);
            return false;
        }
    }

    private boolean isVisible(Locator locator) {
        try {
            return locator != null
                    && locator.count() > 0
                    && locator.isVisible()
                    && locator.isEnabled();
        } catch (PlaywrightException exception) {
            return false;
        }
    }

    private String normalizedLabel(Locator locator) {
        try {
            String text = locator.innerText(new Locator.InnerTextOptions()
                    .setTimeout(LABEL_READ_TIMEOUT_MS));
            if (text != null && !text.isBlank()) {
                return normalize(text);
            }

            String ariaLabel = locator.getAttribute("aria-label", new Locator.GetAttributeOptions()
                    .setTimeout(LABEL_READ_TIMEOUT_MS));
            return normalize(ariaLabel);
        } catch (PlaywrightException exception) {
            return "";
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }
}
