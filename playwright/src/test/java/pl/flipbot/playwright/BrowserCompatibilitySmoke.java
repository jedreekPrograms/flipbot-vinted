package pl.flipbot.playwright;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import pl.flipbot.playwright.browser.BrowserFactory;
import pl.flipbot.playwright.context.ContextBrowserSmoke;
import pl.flipbot.playwright.filters.FilterBrowserSmoke;
import pl.flipbot.playwright.login.LoginBrowserSmoke;
import pl.flipbot.playwright.marketstats.MarketPublicationBrowserSmoke;
import pl.flipbot.playwright.negotiation.ConversationAvailabilityBrowserSmoke;
import pl.flipbot.playwright.session.VintedSessionDiagnostics;
import pl.flipbot.playwright.tools.browser.BrowserDiagnostic;
import pl.flipbot.playwright.tools.browser.PersistentBrowserDiagnostic;
import pl.flipbot.playwright.verification.CookieConsentHandler;

/** Real engine check on local HTML only; never contacts Vinted or the backend. */
public final class BrowserCompatibilitySmoke {
    public static void main(String[] args) throws IOException {
        boolean bundled = args.length == 0;
        if (!bundled && !(args.length == 1 && "chrome".equals(args[0]))) {
            throw new IllegalArgumentException("Use no arguments for bundled Chromium, or chrome for the system browser");
        }
        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = bundled ? BrowserFactory.createBundledBrowser(playwright, true)
                         : BrowserFactory.createSystemChromeBrowser(playwright, true);
                 BrowserContext context = browser.newContext()) {
                checkDomInteraction(context.newPage());
                checkConsentHandling(context.newPage());
                FilterBrowserSmoke.verify(context);
                LoginBrowserSmoke.verify(context);
                ConversationAvailabilityBrowserSmoke.verify(context);
                MarketPublicationBrowserSmoke.verify(context.newPage());
                ContextBrowserSmoke.verify(browser);
                checkAuthHeaders(context);
                System.out.println("BROWSER SMOKE PASS playwright=" + BrowserDiagnostic.playwrightVersion()
                        + " engine=" + (bundled ? "BUNDLED" : "SYSTEM_CHROME")
                        + " browser=" + browser.version() + " localHtmlOnly=true");
            }
            Path profile = Files.createTempDirectory("flipbot-persistent-smoke-");
            try (BrowserContext context = PersistentBrowserDiagnostic.openContext(
                    playwright.chromium(), profile, bundled, true)) {
                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().getFirst();
                checkDomInteraction(page);
                System.out.println("PERSISTENT BROWSER SMOKE PASS playwright=" + BrowserDiagnostic.playwrightVersion()
                        + " freshProfile=true localHtmlOnly=true");
            }
            NativeCookieBrowserSmoke.verify(playwright, bundled);
        }
    }

    private static void checkConsentHandling(Page page) {
        CookieConsentHandler handler = new CookieConsentHandler();
        page.setContent("""
                <button onclick="window.unrelatedClicked = true">Wybierz niezbędne</button>
                <button>Zarządzaj plikami cookies</button>
                """);
        if (handler.acceptAllIfVisible(page)) {
            throw new AssertionError("Consent handler accepted a page without an accept-all control");
        }
        page.setContent("""
                <button onclick="window.consentClicks = (window.consentClicks || 0) + 1">  ZGODA NA WSZYSTKIE  </button>
                <button onclick="window.unrelatedClicked = true">Wybierz niezbędne</button>
                """);
        // No OneTrust id: this must exercise the real getByRole regex
        // serializer. Mocks hid the unsupported UNICODE_CASE flag.
        if (!handler.acceptAllIfVisible(page)
                || ((Number) page.evaluate("window.consentClicks || 0")).intValue() != 1) {
            throw new AssertionError("Real consent fallback could not accept the Polish button label");
        }
        if (handler.acceptAllIfVisible(page)
                || ((Number) page.evaluate("window.consentClicks || 0")).intValue() != 1) {
            throw new AssertionError("Consent handler exceeded the shared click cooldown");
        }
        if (Boolean.TRUE.equals(page.evaluate("window.unrelatedClicked === true"))) {
            throw new AssertionError("Consent handler clicked an unrelated preference control");
        }
        System.out.println("CONSENT BROWSER SMOKE PASS realRegexSerialization=true polishFallbackClicked=true cooldown=true unrelatedControlsUntouched=true");
        page.close();
    }

    private static void checkAuthHeaders(BrowserContext context) {
        Logger logger = (Logger) LoggerFactory.getLogger(VintedSessionDiagnostics.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        // Every request is fulfilled locally, including the synthetic Vinted
        // origin required by the production diagnostic's strict host filter.
        context.route("**/*", route -> {
            if (route.request().url().endsWith("/api/v2/inbox")) {
                route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                        .setStatus(401).setContentType("application/json").setBody("{}")
                        .setHeaders(Map.of("www-authenticate", "Bearer error=\"invalid_token\"")));
            } else {
                route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                        .setStatus(200).setContentType("text/html").setBody("<p>Local auth trace fixture</p>")
                        .setHeaders(Map.of("set-cookie", "access_token_web=browser-access-secret; Path=/; Secure; HttpOnly")));
            }
        });
        context.addCookies(List.of(new Cookie("refresh_token_web", "browser-refresh-secret")
                .setDomain("www.vinted.pl").setPath("/").setSecure(true)));
        VintedSessionDiagnostics.install(context, 0L);
        try {
            Page page = context.newPage();
            page.navigate("https://www.vinted.pl/auth-trace-local-fixture");
            Object status = page.evaluate("() => fetch('/api/v2/inbox').then(response => response.status)");
            if (!(status instanceof Number number) || number.intValue() != 401) {
                throw new AssertionError("Trace changed the local fixture's HTTP result");
            }
            String unauthorized = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(text -> text.startsWith("[VINTED AUTH TRACE]") && text.contains("status=401"))
                    .findFirst().orElseThrow(() -> new AssertionError("No real-engine auth trace"));
            if (!unauthorized.contains("sent=access=[id-") || !unauthorized.contains("refresh=[id-")
                    || !unauthorized.contains("invalidToken=true")) {
                throw new AssertionError("Real-engine trace could not read outgoing auth cookies/challenge");
            }
            if (context.cookies().stream().noneMatch(cookie -> "browser-access-secret".equals(cookie.value))) {
                throw new AssertionError("Passive trace removed a browser cookie");
            }
            if (logs.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(text -> text.contains("secret"))) {
                throw new AssertionError("Trace exposed a synthetic credential");
            }
            System.out.println("AUTH TRACE BROWSER SMOKE PASS outgoingCookies=true invalidToken=true noCredentialValues=true allRequestsFulfilledLocally=true");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private static void checkDomInteraction(Page page) {
        page.setContent("""
                    <label for="value">Value</label><input id="value">
                    <button onclick="document.querySelector('#result').textContent = document.querySelector('#value').value">Copy</button>
                    <p id="result"></p>
                    """);
        page.getByLabel("Value").fill("driver-ok");
        page.getByText("Copy").click();
        if (!"driver-ok".equals(page.locator("#result").innerText())) {
            throw new AssertionError("Supported browser pair did not execute the DOM interaction correctly");
        }
    }
}
