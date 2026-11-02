package pl.flipbot.playwright.login;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;
import pl.flipbot.playwright.util.ErrorMessages;

import java.net.URI;
import java.util.Locale;
import java.util.function.LongSupplier;

import static pl.flipbot.playwright.login.LoginElements.isVisible;
import static pl.flipbot.playwright.login.LoginElements.safeAttribute;
import static pl.flipbot.playwright.login.LoginElements.waitForVisible;
import static pl.flipbot.playwright.login.LoginLimits.AUTH_ACTION_TIMEOUT_MS;
import static pl.flipbot.playwright.login.LoginLimits.AUTH_POLL_INTERVAL_MS;
import static pl.flipbot.playwright.login.LoginLimits.AUTH_SWITCH_RETRY_DELAY_MS;
import static pl.flipbot.playwright.login.LoginLimits.AUTH_VIEW_TIMEOUT_MS;
import static pl.flipbot.playwright.login.LoginLimits.MAX_REGISTER_SWITCH_ATTEMPTS;

@Slf4j
@RequiredArgsConstructor
final class LoginNavigation {
    private static final String REGISTER_VIEW_TEST_ID = "select-type-register-view";

    private static final String LOGIN_VIEW_TEST_ID = "select-type-login-view";

    private static final String REGISTER_SWITCH_TEST_ID = "auth-select-type--register-switch";

    private static final String LOGIN_EMAIL_TEST_ID = "auth-select-type--login-email";

    private static final String REGISTER_SELECT_TYPE_PATH = "/member/register/select_type";

    private final LongSupplier authClock;
    private final LoginSessionVerifier session;

    void openEmailForm(Page page) {
        openLoginWindow(page);
        openEmailLogin(page);
    }

    private void openLoginWindow(Page page) {
        Locator loginButton = page.getByTestId(LoginSelectors.LOGIN_BUTTON);

        loginButton.waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(10_000)
        );

        log.info("[LOGIN] Opening authentication window.");
        loginButton.click();
    }

    void openEmailLogin(Page page) {
        Locator emailInput = page.locator("#" + LoginSelectors.EMAIL_INPUT);
        Locator passwordInput = page.locator("#" + LoginSelectors.PASSWORD_INPUT);
        Locator registerView = page.getByTestId(REGISTER_VIEW_TEST_ID);
        Locator loginView = page.getByTestId(LOGIN_VIEW_TEST_ID);
        Locator switchToLogin = page.getByTestId(REGISTER_SWITCH_TEST_ID);
        Locator emailLogin = page.getByTestId(LOGIN_EMAIL_TEST_ID);

        long deadline = authClock.getAsLong() + (long) AUTH_VIEW_TIMEOUT_MS;

        int registerSwitchAttempts = 0;
        int furthestStage = 0;

        while (true) {
            // Probe the destination before expiring the old stage's budget:
            // a click can finish navigation just as that budget runs out.
            if (isVisible(emailInput) && isVisible(passwordInput)) {
                log.info("[LOGIN] E-mail login form is visible.");
                return;
            }

            int stage = authenticationStage(page, loginView, emailLogin);
            if (stage > furthestStage) {
                furthestStage = stage;
                deadline = authClock.getAsLong() + (long) AUTH_VIEW_TIMEOUT_MS;
                log.debug("[LOGIN] Authentication advanced to stage {}. Waiting for that stage to render.", stage);
            }
            if (authClock.getAsLong() >= deadline) {
                break;
            }

            session.acceptCookiesIfVisible(page);
            if (isVisible(emailInput) && isVisible(passwordInput)) {
                return;
            }

            // The router has already accepted the e-mail link. Let its form
            // render instead of resolving/clicking the departing link again.
            if (stage >= 2) {
                page.waitForTimeout(AUTH_POLL_INTERVAL_MS);
                continue;
            }

            if (isLoginSelectionVisible(loginView, emailLogin)) {
                log.info("[LOGIN] Login selection view detected.");

                if (isVisible(emailLogin)) {
                    log.info("[LOGIN] Selecting e-mail login.");

                    if (clickEmailLoginAndWait(page, emailLogin, emailInput)) {
                        continue;
                    }
                }

                page.waitForTimeout(AUTH_POLL_INTERVAL_MS);
                continue;
            }

            if (stage == 0 && isRegistrationSelectionVisible(page, registerView, switchToLogin)) {
                if (registerSwitchAttempts >= MAX_REGISTER_SWITCH_ATTEMPTS) {
                    page.waitForTimeout(AUTH_POLL_INTERVAL_MS);
                    continue;
                }

                if (!waitForVisible(switchToLogin, AUTH_ACTION_TIMEOUT_MS)) {
                    log.debug("[LOGIN] Registration screen detected, but login switch is not visible yet.");
                    page.waitForTimeout(AUTH_POLL_INTERVAL_MS);
                    continue;
                }

                registerSwitchAttempts++;

                log.info(
                        "[LOGIN] Registration selection is visible. Switching to login view. Attempt {}/{}.",
                        registerSwitchAttempts,
                        MAX_REGISTER_SWITCH_ATTEMPTS
                );

                String href = safeAttribute(switchToLogin, "href");

                if (clickRegistrationSwitchAndWait(
                        page,
                        registerView,
                        loginView,
                        switchToLogin,
                        emailLogin,
                        emailInput,
                        href
                )) {
                    continue;
                }

                log.info(
                        "[LOGIN] Authentication view is still unchanged after attempt {}/{}. Retrying.",
                        registerSwitchAttempts,
                        MAX_REGISTER_SWITCH_ATTEMPTS
                );

                page.waitForTimeout(AUTH_SWITCH_RETRY_DELAY_MS);
                continue;
            }

            page.waitForTimeout(AUTH_POLL_INTERVAL_MS);
        }

        if (isVisible(emailInput) && isVisible(passwordInput)) {
            return;
        }

        log.error(
                "[LOGIN] Could not reach Vinted e-mail login form. Current URL: {}. Register switch attempts: {}.",
                page.url(),
                registerSwitchAttempts
        );

        LoginDiagnostics.log(page);

        throw new IllegalStateException("Vinted authentication flow could not reach the e-mail login form.");
    }

    private boolean clickEmailLoginAndWait(Page page, Locator emailLogin, Locator emailInput) {
        if (isVisible(emailInput) || isEmailLoginUrl(page.url())) {
            return isVisible(emailInput);
        }
        try {
            emailLogin.click(new Locator.ClickOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
        } catch (Exception exception) {
            log.info(
                    "[LOGIN] Normal e-mail-login click failed; DOM fallback will be tried. reason={}",
                    ErrorMessages.firstLine(exception, "unknown error")
            );
        }

        page.waitForTimeout(AUTH_SWITCH_RETRY_DELAY_MS);

        if (isVisible(emailInput)) {
            log.info("[LOGIN] E-mail login form appeared after normal click.");
            return true;
        }

        if (isEmailLoginUrl(page.url()) || !isVisible(emailLogin)) {
            return false;
        }

        try {
            log.info("[LOGIN] Normal e-mail-login click did not open the form. Trying DOM click.");
            emailLogin.evaluate("element => element.click()", null,
                    new Locator.EvaluateOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
        } catch (Exception exception) {
            log.warn("[LOGIN] DOM e-mail-login click failed: {}", ErrorMessages.firstLine(exception, "unknown error"));
        }

        page.waitForTimeout(AUTH_SWITCH_RETRY_DELAY_MS);

        if (isVisible(emailInput)) {
            log.info("[LOGIN] E-mail login form appeared after DOM click.");
            return true;
        }

        return false;
    }

    private boolean clickRegistrationSwitchAndWait(
            Page page,
            Locator registerView,
            Locator loginView,
            Locator switchToLogin,
            Locator emailLogin,
            Locator emailInput,
            String href
    ) {
        try {
            switchToLogin.click(new Locator.ClickOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
        } catch (Exception exception) {
            log.info(
                    "[LOGIN] Normal login-switch click failed; DOM fallback will be tried. reason={}",
                    ErrorMessages.firstLine(exception, "unknown error")
            );
        }

        page.waitForTimeout(AUTH_SWITCH_RETRY_DELAY_MS);

        if (hasAuthenticationViewChanged(
                page,
                registerView,
                loginView,
                switchToLogin,
                emailLogin,
                emailInput
        )) {
            log.info("[LOGIN] Authentication view changed after normal click.");
            return true;
        }

        log.info("[LOGIN] Normal click did not change authentication view. Trying DOM click.");

        try {
            switchToLogin.evaluate("element => element.click()", null,
                    new Locator.EvaluateOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
        } catch (Exception exception) {
            log.warn("[LOGIN] DOM login-switch click failed: {}", ErrorMessages.firstLine(exception, "unknown error"));
        }

        page.waitForTimeout(AUTH_SWITCH_RETRY_DELAY_MS);

        if (hasAuthenticationViewChanged(
                page,
                registerView,
                loginView,
                switchToLogin,
                emailLogin,
                emailInput
        )) {
            log.info("[LOGIN] Authentication view changed after DOM click.");
            return true;
        }

        if (!isNavigableHref(href)) {
            return false;
        }

        String resolvedUrl = resolveUrl(page.url(), href);

        if (!MarketplaceUrls.isVintedUrl(resolvedUrl)) {
            log.warn(
                    "[LOGIN] Refusing authentication href fallback outside trusted Vinted hosts: {}",
                    resolvedUrl
            );
            return false;
        }

        log.info(
                "[LOGIN] Click fallbacks did not change auth view. Navigating directly to trusted href: {}",
                resolvedUrl
        );

        try {
            page.navigate(resolvedUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(AUTH_VIEW_TIMEOUT_MS));
            page.waitForTimeout(AUTH_SWITCH_RETRY_DELAY_MS);
        } catch (Exception exception) {
            log.warn("[LOGIN] Direct trusted href navigation failed: {}", ErrorMessages.firstLine(exception, "unknown error"));
        }

        if (hasAuthenticationViewChanged(
                page,
                registerView,
                loginView,
                switchToLogin,
                emailLogin,
                emailInput
        )) {
            log.info("[LOGIN] Authentication view changed after direct navigation.");
            return true;
        }

        return false;
    }

    private boolean hasAuthenticationViewChanged(
            Page page,
            Locator registerView,
            Locator loginView,
            Locator switchToLogin,
            Locator emailLogin,
            Locator emailInput
    ) {
        return authenticationStage(page, loginView, emailLogin) > 0
                || isVisible(emailInput)
                || isLoginSelectionVisible(loginView, emailLogin)
                || !isRegistrationSelectionVisible(page, registerView, switchToLogin);
    }

    private int authenticationStage(Page page, Locator loginView, Locator emailLogin) {
        String url = page.url();
        if (isEmailLoginUrl(url)) {
            return 2;
        }
        if (isTrustedAuthPath(url, "/member/login/select_type")
                || isLoginSelectionVisible(loginView, emailLogin)) {
            return 1;
        }
        return 0;
    }

    private boolean isEmailLoginUrl(String url) {
        return isTrustedAuthPath(url, "/member/login/email");
    }

    private boolean isTrustedAuthPath(String url, String expectedPath) {
        if (!MarketplaceUrls.isVintedUrl(url)) {
            return false;
        }
        try {
            String path = URI.create(url).getPath();
            return expectedPath.equals(path) || (expectedPath + "/").equals(path);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isLoginSelectionVisible(Locator loginView, Locator emailLogin) {
        return isVisible(loginView) || isVisible(emailLogin);
    }

    private boolean isRegistrationSelectionVisible(Page page, Locator registerView, Locator switchToLogin) {
        return isVisible(registerView)
                || isVisible(switchToLogin)
                || isRegisterSelectTypeUrl(page.url());
    }

    private boolean isRegisterSelectTypeUrl(String url) {
        return isTrustedAuthPath(url, REGISTER_SELECT_TYPE_PATH);
    }

    private boolean isNavigableHref(String href) {
        if (href == null || href.isBlank()) {
            return false;
        }

        String normalized = href.trim().toLowerCase(Locale.ROOT);

        return !normalized.startsWith("#")
                && !normalized.startsWith("javascript:");
    }

    private String resolveUrl(String currentUrl, String href) {
        try {
            return URI.create(currentUrl)
                    .resolve(href)
                    .toString();
        } catch (Exception exception) {
            log.warn("[LOGIN] Could not resolve href {} against current URL {}", href, currentUrl);
            return href;
        }
    }
}
