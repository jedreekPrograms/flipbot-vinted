package pl.flipbot.playwright.verification;

import com.microsoft.playwright.Locator;

/** One finite consent-action budget shared by injected JS and Java in a document. */
public final class CookieConsentClickBudget {
    private static final String SCRIPT = """
            () => {
                const key = "__flipbotConsentClickBudgetV1";
                const state = window[key] || (window[key] = {
                    attempts: 0,
                    nextAllowedAt: -Infinity
                });
                const now = Date.now();
                if (state.attempts >= 3 || now < state.nextAllowedAt) {
                    return false;
                }
                state.attempts++;
                state.nextAllowedAt = now + 5000;
                return true;
            }
            """;

    private CookieConsentClickBudget() {
    }

    public static String script() {
        return SCRIPT;
    }

    static boolean reserve(Locator control) {
        return Boolean.TRUE.equals(control.evaluate(SCRIPT, null,
                new Locator.EvaluateOptions().setTimeout(300)));
    }
}
