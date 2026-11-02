package pl.flipbot.playwright.tools.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.browser.BrowserFactory;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;
import pl.flipbot.playwright.session.PlaywrightRuntimeInstanceLock;
import pl.flipbot.playwright.session.SessionManager;
import pl.flipbot.playwright.session.VintedAuthenticationEvidence;
import pl.flipbot.playwright.session.VintedSessionDiagnostics;

/**
 * Manual comparison window: the production Chrome launch configuration, but
 * no scheduler, backend clients, login actions, injected UI guards or routing.
 * None of the modes saves browser state back to a production session file.
 */
@Slf4j
public final class BrowserDiagnostic {
    private BrowserDiagnostic() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && "persistent".equalsIgnoreCase(args[0])) {
            PersistentBrowserDiagnostic.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Options options = Options.parse(args);
        // Use the worker's existing lock so the comparison is not affected by
        // another FlipBot process performing account work at the same time.
        try (PlaywrightRuntimeInstanceLock runtimeLock = PlaywrightRuntimeInstanceLock.acquireDefault()) {
            Path savedState = options.resolveSessionPath(new SessionManager());
            try (Playwright playwright = Playwright.create();
                 Browser browser = options.bundled()
                         ? BrowserFactory.createBundledBrowser(playwright, false)
                         : BrowserFactory.createSystemChromeBrowser(playwright, false);
                 BrowserContext context = openContext(browser, savedState)) {
                configureObservation(context, options.botId(), options.monitoring());
                Page page = context.newPage();
                log.info("[BROWSER DIAGNOSTIC] mode={}, engine={}, monitoring={}, playwrightVersion={}, chromeVersion={}, savedSession={}. No jobs, automatic clicks, request filters or production session writes. Log in and open conversations manually; close this window to finish.",
                        savedState == null ? "FRESH" : "RESTORED",
                        options.bundled() ? "BUNDLED" : "SYSTEM_CHROME",
                        options.monitoring() ? "ON" : "OFF",
                        playwrightVersion(), browser.version(), savedState);
                try {
                    page.navigate(MarketplaceUrls.HOME, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
                } catch (PlaywrightException exception) {
                    if (page.isClosed()) {
                        return;
                    }
                    log.warn("[BROWSER DIAGNOSTIC] Initial navigation did not settle. The window remains available for manual inspection.");
                }
                observe(page, context, options.monitoring());
                log.info("[BROWSER DIAGNOSTIC] Finished. Production session files were not replaced.");
            }
        }
    }

    static BrowserContext openContext(Browser browser, Path savedState) {
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        if (savedState != null) {
            options.setStorageStatePath(savedState);
        }
        // Deliberately do not use BrowserManager/BotContext: those install the
        // request and page modifications this comparison needs to exclude.
        return browser.newContext(options);
    }

    static void configureObservation(BrowserContext context, Long botId, boolean monitoring) {
        if (monitoring) {
            VintedSessionDiagnostics.install(context, botId);
        }
    }

    static void observe(Page page, BrowserContext context, boolean monitoring) {
        String previous = null;
        int ticks = 0;
        while (!page.isClosed()) {
            try {
                if (monitoring && ticks++ % 4 == 0) {
                    String signal = VintedAuthenticationEvidence.signal(page);
                    String state = "page=" + locationWithoutQuery(page.url())
                            + ", authenticatedUi=" + (signal == null ? "unconfirmed" : signal)
                            + ", cookieCount=" + context.cookies().size();
                    if (!state.equals(previous)) {
                        log.info("[BROWSER DIAGNOSTIC] {}", state);
                        previous = state;
                    }
                }
                // Dispatch Playwright events while the user operates the page.
                page.waitForTimeout(250);
            } catch (PlaywrightException exception) {
                if (page.isClosed()) {
                    return;
                }
                // Navigation can briefly invalidate DOM inspection. Keep the
                // same window and do not infer logout from that transient race.
                page.waitForTimeout(250);
            }
        }
    }

    static String locationWithoutQuery(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            if (uri.getHost() == null) {
                return "<non-web page>";
            }
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        } catch (RuntimeException exception) {
            return "<unavailable>";
        }
    }

    public static String playwrightVersion() {
        try (InputStream source = Playwright.class.getResourceAsStream(
                "/META-INF/maven/com.microsoft.playwright/playwright/pom.properties")) {
            if (source != null) {
                Properties properties = new Properties();
                properties.load(source);
                return properties.getProperty("version", "<unknown>");
            }
        } catch (IOException ignored) {
            // Version reporting is diagnostic and must not prevent launch.
        }
        return "<unknown>";
    }

    record Options(Long botId, boolean bundled, boolean monitoring) {
        static Options parse(String[] args) {
            if (args.length == 0) {
                return new Options(null, true, true);
            }
            if (("fresh".equalsIgnoreCase(args[0]) || "plain".equalsIgnoreCase(args[0]))
                    && args.length <= 2) {
                return new Options(null, args.length < 2 || parseBundled(args[1]),
                        !"plain".equalsIgnoreCase(args[0]));
            }
            if ((args.length == 2 || args.length == 3) && "restore".equalsIgnoreCase(args[0])) {
                try {
                    long id = Long.parseLong(args[1]);
                    if (id > 0) {
                        return new Options(id, args.length < 3 || parseBundled(args[2]), true);
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to the explicit usage error.
                }
            }
            throw new IllegalArgumentException("Use fresh [chrome|bundled], plain [chrome|bundled], or restore <positive bot id> [chrome|bundled]. No arguments means fresh bundled Chromium. Persistent mode: persistent [bundled|chrome].");
        }

        private static boolean parseBundled(String browser) {
            if ("chrome".equalsIgnoreCase(browser)) {
                return false;
            }
            if ("bundled".equalsIgnoreCase(browser)) {
                return true;
            }
            throw new IllegalArgumentException("Diagnostic browser must be chrome or bundled.");
        }

        Path resolveSessionPath(SessionManager sessions) {
            if (botId == null) {
                return null;
            }
            if (!sessions.sessionExists(botId)) {
                throw new IllegalStateException("No saved session exists for bot " + botId
                        + ". Use fresh for a clean comparison. No fresh fallback was performed.");
            }
            return sessions.sessionFile(botId);
        }
    }
}
