package pl.flipbot.playwright.session;

import com.microsoft.playwright.Page;
import pl.flipbot.playwright.context.BotContext;

import java.util.Locale;

/**
 * Final guard before a scheduled job is allowed to replace bot-X.json.
 * A structurally valid storageState is not enough: the current page must still
 * prove that Vinted considers this browser authenticated.
 */
public final class VintedSessionPersistenceGuard {

    public Check check(BotContext context) {
        if (context == null || context.getPage() == null) {
            return new Check(false, "browser page is unavailable");
        }

        Page page = context.getPage();
        String url = page.url() == null ? "" : page.url().trim();
        String normalizedUrl = url.toLowerCase(Locale.ROOT);

        if (normalizedUrl.contains("/session-refresh")) {
            return new Check(false, "page is still on Vinted session-refresh: " + url);
        }

        if (normalizedUrl.contains("/member/login")
                || normalizedUrl.contains("/member/register")) {
            return new Check(false, "page is on Vinted authentication UI: " + url);
        }

        String signal = VintedAuthenticationEvidence.signal(page);
        if (signal != null) {
            return new Check(true, signal);
        }

        return new Check(
                false,
                "no strong authenticated Vinted signal is visible at job end; url=" + url
        );
    }

    public record Check(boolean healthy, String reason) {
    }
}
