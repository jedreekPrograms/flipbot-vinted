package pl.flipbot.playwright.login;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

import static pl.flipbot.playwright.login.LoginElements.isVisible;
import static pl.flipbot.playwright.login.LoginElements.safeAttribute;
import static pl.flipbot.playwright.login.LoginElements.safeCount;
import static pl.flipbot.playwright.login.LoginElements.safeInnerText;
import static pl.flipbot.playwright.login.LoginLimits.AUTH_ACTION_TIMEOUT_MS;

@Slf4j
final class LoginDiagnostics {
    private static final List<String> EXPLICIT_LOGIN_ERROR_TEXTS =
            List.of(
                    "nieprawidłowy e-mail lub hasło",
                    "nieprawidlowy e-mail lub haslo",
                    "nieprawidłowy adres e-mail lub hasło",
                    "nieprawidlowy adres e-mail lub haslo",
                    "niepoprawne hasło",
                    "niepoprawne haslo",
                    "incorrect email or password",
                    "invalid email or password",
                    "wrong password",
                    "too many attempts",
                    "zbyt wiele prób",
                    "zbyt wiele prob"
            );

    private LoginDiagnostics() {}

    static String explicitError(Page page) {
        try {
            String bodyText = page.locator("body").innerText();
            if (bodyText == null || bodyText.isBlank()) {
                return null;
            }

            String normalized = bodyText.toLowerCase(Locale.ROOT);
            for (String phrase : EXPLICIT_LOGIN_ERROR_TEXTS) {
                if (normalized.contains(phrase)) {
                    return phrase;
                }
            }
        } catch (PlaywrightException exception) {
            log.debug(
                    "[LOGIN] Login-error probe was inconclusive while the page was changing. This is NOT treated as verification or authentication failure by itself."
            );
        }

        return null;
    }

    static void log(Page page) {
        Locator emailInput = page.locator("#" + LoginSelectors.EMAIL_INPUT);
        Locator passwordInput = page.locator("#" + LoginSelectors.PASSWORD_INPUT);

        log.debug(
                "[LOGIN DIAGNOSTIC] currentUrl={}, emailVisible={}, passwordVisible={}, emailHasValue={}, passwordHasValue={}",
                page.url(),
                isVisible(emailInput),
                isVisible(passwordInput),
                hasAnyInputValue(emailInput),
                hasAnyInputValue(passwordInput)
        );

        Locator buttons = page.locator("button:visible");
        int buttonCount = Math.min(safeCount(buttons), 30);

        for (int index = 0; index < buttonCount; index++) {
            Locator button = buttons.nth(index);
            String text = safeInnerText(button)
                    .replaceAll("\\s+", " ")
                    .trim();

            if (text.length() > 100) {
                text = text.substring(0, 100);
            }

            log.debug(
                    "[LOGIN DIAGNOSTIC] visibleButton index={} type={} text={}",
                    index,
                    safeAttribute(button, "type"),
                    text
            );
        }

        logVisibleTestIds(page);
    }

    static boolean hasAnyInputValue(Locator input) {
        try {
            return input.count() > 0
                    && !input.first().inputValue().isBlank();
        } catch (Exception exception) {
            return false;
        }
    }

    static void logVisibleTestIds(Page page) {
        Locator elements = page.locator("[data-testid]");
        int count = Math.min(safeCount(elements), 100);

        log.debug("[LOGIN DIAGNOSTIC] Visible Vinted data-testid elements:");

        for (int index = 0; index < count; index++) {
            Locator element = elements.nth(index);

            try {
                if (!element.isVisible()) {
                    continue;
                }

                String testId = element.getAttribute("data-testid", new Locator.GetAttributeOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
                String text = element.innerText(new Locator.InnerTextOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));

                if (text != null) {
                    text = text.replaceAll("\\s+", " ").trim();

                    if (text.length() > 150) {
                        text = text.substring(0, 150);
                    }
                }

                log.debug("[LOGIN DIAGNOSTIC] testId={} text={}", testId, text);
            } catch (Exception ignored) {
            }
        }
    }
}
