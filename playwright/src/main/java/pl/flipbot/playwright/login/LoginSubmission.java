package pl.flipbot.playwright.login;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.util.function.LongSupplier;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.login.LoginCredentials.LoginForm;

import static pl.flipbot.playwright.login.LoginElements.isVisible;
import static pl.flipbot.playwright.login.LoginLimits.AUTH_ACTION_TIMEOUT_MS;
import static pl.flipbot.playwright.login.LoginLimits.AUTH_POLL_INTERVAL_MS;
import static pl.flipbot.playwright.login.LoginLimits.FORM_SETTLE_MS;
import static pl.flipbot.playwright.login.LoginLimits.MAX_SUBMIT_ATTEMPTS;
import static pl.flipbot.playwright.login.LoginLimits.SUBMIT_TRANSITION_TIMEOUT_MS;

@Slf4j
@RequiredArgsConstructor
final class LoginSubmission {
    private final LongSupplier authClock;
    private final LoginCredentials credentials;
    private final LoginSessionVerifier session;

    void submitAndObserve(Page page, LoginForm form) {
        Locator submitButton = credentials.resolveSubmitButton(form.form());

        if (submitButton == null) {
            LoginDiagnostics.log(page);
            throw new IllegalStateException(
                    "Could not find a visible login submit control inside the Vinted e-mail/password form."
            );
        }

        String submitDescription = credentials.describeSubmitControl(submitButton);
        String initialUrl = page.url();

        for (int attempt = 1; attempt <= MAX_SUBMIT_ATTEMPTS; attempt++) {
            // A response (or manual verification) can finish after the short
            // transition poll. Never read/re-fill a departed form on retry.
            if (attempt > 1 && submissionTransition(page, form, initialUrl) != null) {
                log.info("[LOGIN] Login advanced before retry {}. Waiting for authenticated state without submitting again.", attempt);
                return;
            }
            if (!credentials.matches(form)) {
                log.warn(
                        "[LOGIN] Credential fields changed before submit attempt {}/{}. Stabilizing them again before any submit action.",
                        attempt,
                        MAX_SUBMIT_ATTEMPTS
                );
                credentials.fillAndVerify(page, form);
            }

            String explicitErrorBeforeSubmit = LoginDiagnostics.explicitError(page);
            if (explicitErrorBeforeSubmit != null) {
                throw new IllegalStateException(
                        "Vinted login form exposes an explicit authentication error before retry: "
                                + explicitErrorBeforeSubmit
                );
            }

            performSubmitAttempt(attempt, submitButton, submitDescription, form);

            String transition = waitForSubmissionTransition(page, form, initialUrl);

            if (transition != null) {
                log.info(
                        "[LOGIN] Login submit attempt {}/{} produced an observable transition: {}. Current URL: {}",
                        attempt,
                        MAX_SUBMIT_ATTEMPTS,
                        transition,
                        page.url()
                );
                return;
            }

            String explicitError = LoginDiagnostics.explicitError(page);
            if (explicitError != null) {
                log.error(
                        "[LOGIN] Vinted returned an explicit login error after submit attempt {}/{}: {}",
                        attempt,
                        MAX_SUBMIT_ATTEMPTS,
                        explicitError
                );
                LoginDiagnostics.log(page);
                throw new IllegalStateException("Vinted rejected the login form: " + explicitError);
            }

            log.warn(
                    "[LOGIN] Submit attempt {}/{} completed at Playwright level, but Vinted showed no authenticated state, no real human-verification evidence, no auth URL transition and no explicit login error within {}s.",
                    attempt,
                    MAX_SUBMIT_ATTEMPTS,
                    Math.round(SUBMIT_TRANSITION_TIMEOUT_MS / 1_000)
            );

            if (attempt < MAX_SUBMIT_ATTEMPTS) {
                page.waitForTimeout(FORM_SETTLE_MS);
                if (submissionTransition(page, form, initialUrl) != null) {
                    log.info("[LOGIN] Login advanced while waiting to retry. Leaving the departed form untouched.");
                    return;
                }
                submitButton = credentials.resolveSubmitButton(form.form());
                if (submitButton == null && attempt < 2) {
                    log.warn(
                            "[LOGIN] Submit button disappeared, but credential form remains. Keyboard/requestSubmit fallbacks can still be used."
                    );
                }
            }
        }

        LoginDiagnostics.log(page);
        throw new IllegalStateException(
                "Vinted login form accepted three submit mechanisms without producing any observable authentication transition. Refusing to pretend that login or human verification happened. Current URL: "
                        + page.url()
        );
    }

    private void performSubmitAttempt(int attempt, Locator submitButton, String submitDescription, LoginForm form) {
        if (attempt == 1) {
            log.info(
                    "[LOGIN] Submit attempt 1/{}: clicking the visible submit control INSIDE the credential form. control={}",
                    MAX_SUBMIT_ATTEMPTS,
                    submitDescription
            );

            submitButton.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions()
                    .setTimeout(AUTH_ACTION_TIMEOUT_MS));

            if (!submitButton.isEnabled()) {
                throw new IllegalStateException(
                        "Vinted login submit control is visible but disabled while both credential fields are stable. control="
                                + submitDescription
                );
            }

            submitButton.click(new Locator.ClickOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
        } else if (attempt == 2) {
            log.warn(
                    "[LOGIN] Previous click produced no observable login transition. Submit attempt 2/{}: pressing Enter on the stable password field.",
                    MAX_SUBMIT_ATTEMPTS
            );
            form.passwordInput().press("Enter",
                    new Locator.PressOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
        } else {
            log.warn(
                    "[LOGIN] Click and Enter produced no observable login transition. Submit attempt 3/{}: invoking HTMLFormElement.requestSubmit() on the exact credential form so normal submit handlers and validation still run.",
                    MAX_SUBMIT_ATTEMPTS
            );
            form.form().evaluate("form => form.requestSubmit()");
        }
    }

    private String waitForSubmissionTransition(Page page, LoginForm form, String initialUrl) {
        long deadline = authClock.getAsLong() + (long) SUBMIT_TRANSITION_TIMEOUT_MS;

        while (authClock.getAsLong() <= deadline) {
            session.acceptCookiesIfVisible(page);
            String authenticated = session.authenticatedSignal(page);
            if (authenticated != null) {
                return "authenticated by " + authenticated;
            }

            if (session.completeVisibleHumanVerification(page)) {
                String afterVerification = session.authenticatedSignal(page);
                if (afterVerification != null) {
                    return "human verification completed; authenticated by "
                            + afterVerification;
                }

                return "human verification was positively detected and completed";
            }

            String transition = submissionFormTransition(page, form, initialUrl);
            if (transition != null) {
                return transition;
            }
            page.waitForTimeout(AUTH_POLL_INTERVAL_MS);
        }

        return null;
    }

    private String submissionTransition(Page page, LoginForm form, String initialUrl) {
        String authenticated = session.authenticatedSignal(page);
        if (authenticated != null) {
            return "authenticated by " + authenticated;
        }
        return submissionFormTransition(page, form, initialUrl);
    }

    private String submissionFormTransition(Page page, LoginForm form, String initialUrl) {
        if (!Objects.equals(initialUrl, page.url())) {
            return "URL changed from the submitted login page";
        }
        if (!isVisible(form.emailInput()) || !isVisible(form.passwordInput())) {
            // This is progress, not proof of authentication. The caller still
            // requires waitForAuthenticatedSession() before saving the session.
            return "credential form disappeared";
        }
        return null;
    }

}
