package pl.flipbot.playwright.login;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.login.LoginCredentials.LoginForm;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;

import static pl.flipbot.playwright.login.LoginLimits.RESTORED_SESSION_SETTLE_POLLS;
import static pl.flipbot.playwright.login.LoginLimits.RESTORED_SESSION_SETTLE_POLL_MS;

@Slf4j
public final class LoginService {
    private final BotContext context;
    private final LoginSessionVerifier session;
    private final LoginNavigation navigation;
    private final LoginCredentials credentials;
    private final LoginSubmission submission;

    public LoginService(BotContext context) {
        this.context = context;
        this.session = new LoginSessionVerifier(context);
        this.navigation = new LoginNavigation(System::currentTimeMillis, session);
        this.credentials = new LoginCredentials(context);
        this.submission = new LoginSubmission(System::currentTimeMillis, credentials, session);
    }

    public boolean login() {
        Page page = context.getPage();

        // A LIVE job already owns this page. Do not reload a healthy session
        // (or throw away a manually completed login) just to check its header.
        String currentSignal = session.authenticatedSignal(page);
        if (currentSignal != null) {
            session.acceptCookiesIfVisible(page);
            log.info("[LOGIN] Bot {} is already logged in on the current page. signal={}",
                    context.getBot().getId(), currentSignal);
            return false;
        }

        log.info("[LOGIN] Opening Vinted homepage for bot {}.", context.getBot().getId());

        /*
         * MarketplaceNavigator.goToHome() already waits for DOMContentLoaded.
         * Waiting again for Playwright's full LOAD state made otherwise usable
         * Vinted pages fail after 30 seconds when a slow/non-essential resource
         * (ads, analytics, CDN) did not finish. Authentication readiness below
         * is verified from concrete UI/session signals instead.
         */
        new MarketplaceNavigator(context).goToHome();

        session.acceptCookiesIfVisible(page);

        String existingSignal = session.authenticatedSignal(page);

        if (existingSignal == null && context.isStoredSessionRestored()) {
            existingSignal = session.waitForRestoredSessionSignal(page);

            if (existingSignal != null) {
                log.info(
                        "[SESSION] Restored session for bot {} became authenticated after UI stabilization. signal={}",
                        context.getBot().getId(),
                        existingSignal
                );
            } else {
                log.warn(
                        "[SESSION] Restored session for bot {} still exposes no strong authenticated signal after {}ms. Proceeding to interactive login without clearing cookies, storage or the persisted session file.",
                        context.getBot().getId(),
                        Math.round(RESTORED_SESSION_SETTLE_POLLS * RESTORED_SESSION_SETTLE_POLL_MS)
                );
            }
        }

        if (existingSignal != null) {
            log.info(
                    "[LOGIN] Bot {} is already logged in. signal={}",
                    context.getBot().getId(),
                    existingSignal
            );
            return false;
        }

        performLogin(page);
        return true;
    }


    private void performLogin(Page page) {
        log.info("[LOGIN] Starting interactive login for bot {}.", context.getBot().getId());

        validateConfiguredCredentials();
        navigation.openEmailForm(page);

        LoginForm form = credentials.resolveForm(page);
        credentials.fillAndVerify(page, form);
        submission.submitAndObserve(page, form);

        String authenticatedBy = session.waitForAuthenticatedSession(page);

        context.saveSession();

        log.info(
                "[LOGIN] Bot {} logged in successfully. verifiedBy={}",
                context.getBot().getId(),
                authenticatedBy
        );
    }

    private void validateConfiguredCredentials() {
        if (context.getBot().getEmail() == null || context.getBot().getEmail().isBlank()) {
            throw new IllegalStateException("Bot has no Vinted e-mail configured");
        }

        if (context.getBot().getPassword() == null || context.getBot().getPassword().isBlank()) {
            throw new IllegalStateException("Bot has no Vinted password configured");
        }
    }
}
