package pl.flipbot.playwright.login;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.session.VintedAuthenticationEvidence;
import pl.flipbot.playwright.verification.CookieConsentHandler;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import static pl.flipbot.playwright.login.LoginLimits.AUTH_POLL_INTERVAL_MS;
import static pl.flipbot.playwright.login.LoginLimits.POST_LOGIN_TIMEOUT_MS;
import static pl.flipbot.playwright.login.LoginLimits.RESTORED_SESSION_SETTLE_POLLS;
import static pl.flipbot.playwright.login.LoginLimits.RESTORED_SESSION_SETTLE_POLL_MS;

@Slf4j
final class LoginSessionVerifier {
    private final BotContext context;
    private final HumanVerificationHandler humanVerificationHandler = new HumanVerificationHandler();
    private final CookieConsentHandler cookieConsentHandler = new CookieConsentHandler();
    private long nextCookieProbeNanos;

    LoginSessionVerifier(BotContext context) {
        this.context = context;
    }

    void acceptCookiesIfVisible(Page page) {
        long now = System.nanoTime();
        if (now < nextCookieProbeNanos) {
            return;
        }
        nextCookieProbeNanos = now + 1_000_000_000L;
        cookieConsentHandler.acceptAllIfVisible(page);
    }

    String waitForRestoredSessionSignal(Page page) {
        for (int poll = 0; poll < RESTORED_SESSION_SETTLE_POLLS; poll++) {
            String signal = authenticatedSignal(page);
            if (signal != null) {
                return signal;
            }

            page.waitForTimeout(RESTORED_SESSION_SETTLE_POLL_MS);
        }

        return authenticatedSignal(page);
    }

    String authenticatedSignal(Page page) {
        return VintedAuthenticationEvidence.signal(page);
    }

    String waitForAuthenticatedSession(Page page) {
        long deadline = System.currentTimeMillis() + (long) POST_LOGIN_TIMEOUT_MS;

        while (System.currentTimeMillis() <= deadline) {
            acceptCookiesIfVisible(page);
            String strongSignal = authenticatedSignal(page);

            if (strongSignal != null) {
                return strongSignal;
            }

            if (completeVisibleHumanVerification(page)) {
                continue;
            }

            String explicitError = LoginDiagnostics.explicitError(page);
            if (explicitError != null) {
                LoginDiagnostics.log(page);
                throw new IllegalStateException("Vinted rejected the login form: " + explicitError);
            }

            page.waitForTimeout(AUTH_POLL_INTERVAL_MS);
        }

        log.error(
                "[LOGIN] Login submission could not be verified by a strong authenticated signal within {}s for bot {}. Current URL: {}. The session will NOT be saved as authenticated.",
                Math.round(POST_LOGIN_TIMEOUT_MS / 1_000),
                context.getBot().getId(),
                page.url()
        );
        LoginDiagnostics.log(page);

        throw new IllegalStateException(
                "Vinted login submission could not be verified by a strong authenticated signal. Current URL: "
                        + page.url()
        );
    }

    boolean completeVisibleHumanVerification(Page page) {
        if (!humanVerificationHandler.isHumanVerificationVisible(page)) {
            return false;
        }
        log.warn("[LOGIN] Human verification is visibly present. Waiting for it to be completed.");
        humanVerificationHandler.waitUntilVerified(page);
        return true;
    }
}
