package pl.flipbot.playwright.login;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.context.BotContext;

import static pl.flipbot.playwright.login.LoginElements.isVisible;
import static pl.flipbot.playwright.login.LoginElements.safeAttribute;
import static pl.flipbot.playwright.login.LoginElements.safeCount;
import static pl.flipbot.playwright.login.LoginElements.safeInnerText;
import static pl.flipbot.playwright.login.LoginLimits.AUTH_ACTION_TIMEOUT_MS;
import static pl.flipbot.playwright.login.LoginLimits.CREDENTIAL_STABILITY_WAIT_MS;
import static pl.flipbot.playwright.login.LoginLimits.FORM_SETTLE_MS;
import static pl.flipbot.playwright.login.LoginLimits.MAX_CREDENTIAL_FILL_ATTEMPTS;

@Slf4j
@RequiredArgsConstructor
final class LoginCredentials {
    private static final List<String> LOGIN_SUBMIT_LABELS =
            List.of("zaloguj się", "zaloguj sie", "log in", "login", "kontynuuj", "continue");

    private final BotContext context;

    LoginForm resolveForm(Page page) {
        Locator emailInput = page.locator("#" + LoginSelectors.EMAIL_INPUT);
        Locator passwordInput = page.locator("#" + LoginSelectors.PASSWORD_INPUT);

        emailInput.waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(10_000)
        );
        passwordInput.waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(10_000)
        );

        /*
         * The log that exposed the bug showed the form becoming visible and
         * the old code trying to submit only ~20 ms later. Give Vinted's React
         * form time to finish hydrating before writing controlled inputs.
         */
        page.waitForTimeout(FORM_SETTLE_MS);

        Locator form = passwordInput.locator("xpath=ancestor::form[1]");
        if (form.count() == 0) {
            form = emailInput.locator("xpath=ancestor::form[1]");
        }

        if (form.count() == 0) {
            throw new IllegalStateException(
                    "Vinted e-mail/password inputs are visible, but no containing login form could be resolved."
            );
        }

        return new LoginForm(emailInput, passwordInput, form.first());
    }

    void fillAndVerify(Page page, LoginForm form) {
        String expectedEmail = context.getBot().getEmail();
        String expectedPassword = context.getBot().getPassword();

        for (int attempt = 1; attempt <= MAX_CREDENTIAL_FILL_ATTEMPTS; attempt++) {
            form.emailInput().fill(expectedEmail,
                    new Locator.FillOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
            form.passwordInput().fill(expectedPassword,
                    new Locator.FillOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));

            page.waitForTimeout(CREDENTIAL_STABILITY_WAIT_MS);

            boolean emailStable = inputValueEquals(form.emailInput(), expectedEmail);
            boolean passwordStable = inputValueEquals(form.passwordInput(), expectedPassword);

            if (emailStable && passwordStable) {
                log.info(
                        "[LOGIN] Credentials are stable in the Vinted form after fill attempt {}/{}. Values are not logged.",
                        attempt,
                        MAX_CREDENTIAL_FILL_ATTEMPTS
                );
                return;
            }

            log.warn(
                    "[LOGIN] Vinted cleared or rewrote credential field(s) after fill attempt {}/{}. emailStable={}, passwordStable={}. Re-filling only after the form has settled.",
                    attempt,
                    MAX_CREDENTIAL_FILL_ATTEMPTS,
                    emailStable,
                    passwordStable
            );

            page.waitForTimeout(FORM_SETTLE_MS);
        }

        LoginDiagnostics.log(page);
        throw new IllegalStateException(
                "Vinted login form did not retain configured credentials long enough to submit safely."
        );
    }

    boolean matches(LoginForm form) {
        return inputValueEquals(
                form.emailInput(),
                context.getBot().getEmail()
        ) && inputValueEquals(form.passwordInput(), context.getBot().getPassword());
    }

    private boolean inputValueEquals(Locator input, String expected) {
        try {
            return expected != null
                    && expected.equals(input.inputValue(new Locator.InputValueOptions()
                            .setTimeout(AUTH_ACTION_TIMEOUT_MS)));
        } catch (PlaywrightException exception) {
            return false;
        }
    }

    Locator resolveSubmitButton(Locator form) {
        Locator typedSubmit = form.locator("button[type='submit']:visible, input[type='submit']:visible");

        int typedCount = safeCount(typedSubmit);
        for (int index = 0; index < typedCount; index++) {
            Locator candidate = typedSubmit.nth(index);
            if (isVisible(candidate)) {
                return candidate;
            }
        }

        Locator buttons = form.locator("button:visible");
        int buttonCount = Math.min(safeCount(buttons), 20);

        for (int index = 0; index < buttonCount; index++) {
            Locator candidate = buttons.nth(index);
            if (!isVisible(candidate)) {
                continue;
            }

            String text = safeInnerText(candidate);
            if (isLoginSubmitLabel(text)) {
                return candidate;
            }
        }

        return null;
    }

    String describeSubmitControl(Locator submitButton) {
        String type = safeAttribute(submitButton, "type");
        String text = safeInnerText(submitButton)
                .replaceAll("\\s+", " ")
                .trim();

        if (text.length() > 80) {
            text = text.substring(0, 80);
        }

        return "type='" + type + "', text='" + text + "'";
    }

    static boolean isLoginSubmitLabel(String text) {
        if (text == null) {
            return false;
        }

        String normalized = text
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        return LOGIN_SUBMIT_LABELS.contains(normalized);
    }

    record LoginForm(Locator emailInput, Locator passwordInput, Locator form) {
    }
}
