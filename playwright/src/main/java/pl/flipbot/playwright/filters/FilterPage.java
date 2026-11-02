package pl.flipbot.playwright.filters;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.filters.catalog.CatalogUrl;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;

@Slf4j
@RequiredArgsConstructor
public final class FilterPage {
    private static final double FILTER_TIMEOUT_MS = 10_000;

    private static final double OPTION_TIMEOUT_MS = 10_000;

    private static final double RELOAD_TIMEOUT_MS = 30_000;

    private final Page page;
    private String lastKnownSafeVintedUrl;

    public void waitForOption(String option) {
        waitForOption(option, OPTION_TIMEOUT_MS);
    }

    public void waitForOption(String option, double timeoutMilliseconds) {
        waitUntilVisible(getOptionLocator(option), timeoutMilliseconds);
    }

    public void fillInputBySelector(String selector, Object value) {
        ensureVintedBeforeFilterAction("filling selector " + selector);
        Locator input = page.locator(selector);
        waitUntilVisible(input, OPTION_TIMEOUT_MS);
        input.fill(String.valueOf(value));
    }

    public void clickOutsideSafely() {
        ensureVintedBeforeFilterAction("closing filter panel");
        page.keyboard().press("Escape");
        page.waitForTimeout(400);
        assertStillOnVinted("closing filter panel");
    }

    public void reloadCurrentPage() {
        String currentUrl = page.url();

        if (!MarketplaceUrls.isVintedUrl(currentUrl)) {
            String recoveryUrl = safeRecoveryUrl();
            log.warn(
                    "[PAGE SAFETY] Refusing to reload external main-page URL '{}'. Recovering to '{}'.",
                    currentUrl,
                    recoveryUrl
            );
            navigateToSafeVintedUrl(recoveryUrl);
            return;
        }

        rememberCurrentVintedUrl();
        String recoveryUrl = safeRecoveryUrl();

        page.reload(
                new Page.ReloadOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(RELOAD_TIMEOUT_MS)
        );

        if (!MarketplaceUrls.isVintedUrl(page.url())) {
            log.warn(
                    "[PAGE SAFETY] Reload left Vinted and reached '{}'. Recovering to '{}'.",
                    page.url(),
                    recoveryUrl
            );
            navigateToSafeVintedUrl(recoveryUrl);
        } else {
            rememberCurrentVintedUrl();
        }
    }

    public void waitForTimeout(double milliseconds) {
        page.waitForTimeout(milliseconds);
    }

    public boolean waitForUrlParameterPresent(String parameterName, double timeoutMilliseconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutMilliseconds;

        while (System.currentTimeMillis() <= deadline) {
            assertStillOnVinted("waiting for URL parameter '" + parameterName + "'");

            if (getUrlParameter(parameterName) != null) {
                return true;
            }

            page.waitForTimeout(200);
        }

        return false;
    }

    public boolean waitForUrlParameterValue(
            String parameterName,
            String expectedValue,
            double timeoutMilliseconds
    ) {
        long deadline = System.currentTimeMillis() + (long) timeoutMilliseconds;

        while (System.currentTimeMillis() <= deadline) {
            assertStillOnVinted("waiting for exact URL parameter '" + parameterName + "'");

            if (expectedValue.equals(getUrlParameter(parameterName))) {
                return true;
            }

            page.waitForTimeout(200);
        }

        return false;
    }

    public boolean isCatalogPage() {
        return MarketplaceUrls.isCatalogUrl(page.url());
    }

    public Locator getOptionLocator(String option) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(option));
    }

    public void waitUntilVisible(Locator locator, double timeoutMilliseconds) {
        locator.waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(timeoutMilliseconds)
        );
    }

    public void ensureVintedBeforeFilterAction(String action) {
        if (MarketplaceUrls.isVintedUrl(page.url())) {
            rememberCurrentVintedUrl();
            return;
        }

        String externalUrl = page.url();
        String recoveryUrl = safeRecoveryUrl();
        log.warn(
                "[PAGE SAFETY] Main page is outside Vinted before {}. URL='{}'. Recovering to '{}'.",
                action,
                externalUrl,
                recoveryUrl
        );
        navigateToSafeVintedUrl(recoveryUrl);
    }

    public void assertStillOnVinted(String action) {
        String currentUrl = page.url();

        if (MarketplaceUrls.isVintedUrl(currentUrl)) {
            rememberCurrentVintedUrl();
            return;
        }

        String recoveryUrl = safeRecoveryUrl();
        log.warn(
                "[PAGE SAFETY] Main page left Vinted while {}. External URL='{}'. Recovering to '{}'.",
                action,
                currentUrl,
                recoveryUrl
        );

        try {
            navigateToSafeVintedUrl(recoveryUrl);
        } catch (RuntimeException recoveryFailure) {
            throw new IllegalStateException(
                    "[PAGE SAFETY] Main page left Vinted while "
                            + action
                            + ". External URL: "
                            + currentUrl
                            + ". Recovery also failed.",
                    recoveryFailure
            );
        }

        throw new IllegalStateException(
                "[PAGE SAFETY] Main page left Vinted while "
                        + action
                        + ". External URL: "
                        + currentUrl
                        + ". Recovered to: "
                        + page.url()
        );
    }

    private String safeRecoveryUrl() {
        if (MarketplaceUrls.isVintedUrl(lastKnownSafeVintedUrl)) {
            return lastKnownSafeVintedUrl;
        }
        return MarketplaceUrls.CATALOG;
    }

    public void navigateToSafeVintedUrl(String url) {
        if (!MarketplaceUrls.isVintedUrl(url)) {
            throw new IllegalArgumentException(
                    "Refusing to navigate filter recovery to non-Vinted URL: " + url
            );
        }

        page.navigate(
                url,
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(RELOAD_TIMEOUT_MS)
        );

        if (!MarketplaceUrls.isVintedUrl(page.url())) {
            throw new IllegalStateException(
                    "Vinted recovery navigation ended on an external URL: " + page.url()
            );
        }

        rememberCurrentVintedUrl();
    }

    private void rememberCurrentVintedUrl() {
        String currentUrl = page.url();
        if (MarketplaceUrls.isVintedUrl(currentUrl)) {
            lastKnownSafeVintedUrl = currentUrl;
        }
    }

    public String getUrlParameter(String parameterName) {
        return CatalogUrl.parameter(page.url(), parameterName);
    }

    public String openFilter(String filterTestId) {
        ensureVintedBeforeFilterAction("opening filter " + filterTestId);
        String baseUrl = page.url();
        Locator filter = page.getByTestId(filterTestId);
        waitUntilVisible(filter, FILTER_TIMEOUT_MS);
        filter.click();
        assertStillOnVinted("opening filter " + filterTestId);
        return baseUrl;
    }

    public void selectOption(String option) {
        Locator locator = getOptionLocator(option);
        waitUntilVisible(locator, OPTION_TIMEOUT_MS);
        locator.click();
        assertStillOnVinted("selecting filter option '" + option + "'");
    }

    public void clickSelector(String selector) {
        ensureVintedBeforeFilterAction("clicking filter selector " + selector);
        Locator locator = page.locator(selector);
        waitUntilVisible(locator, OPTION_TIMEOUT_MS);
        locator.click();
        assertStillOnVinted("clicking filter selector " + selector);
    }

}
