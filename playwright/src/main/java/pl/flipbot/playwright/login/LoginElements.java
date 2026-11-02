package pl.flipbot.playwright.login;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.WaitForSelectorState;

import static pl.flipbot.playwright.login.LoginLimits.AUTH_ACTION_TIMEOUT_MS;

final class LoginElements {
    private LoginElements() {}

    static boolean waitForVisible(Locator locator, double timeoutMs) {
        try {
            locator.waitFor(
                    new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(timeoutMs)
            );
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    static boolean isVisible(Locator locator) {
        try {
            return locator != null && locator.isVisible();
        } catch (Exception exception) {
            return false;
        }
    }

    static int safeCount(Locator locator) {
        try {
            return locator == null ? 0 : locator.count();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    static String safeAttribute(Locator locator, String attributeName) {
        try {
            String value = locator.getAttribute(attributeName,
                    new Locator.GetAttributeOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
            return value == null ? "" : value;
        } catch (Exception exception) {
            return "";
        }
    }

    static String safeInnerText(Locator locator) {
        try {
            String text = locator.innerText(new Locator.InnerTextOptions().setTimeout(AUTH_ACTION_TIMEOUT_MS));
            return text == null ? "" : text;
        } catch (Exception exception) {
            return "";
        }
    }


}
