package pl.flipbot.playwright.tools.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;
import pl.flipbot.playwright.session.PlaywrightRuntimeInstanceLock;

/** Fresh standalone profile comparison. Never attaches to an existing user/bot profile. */
@Slf4j
public final class PersistentBrowserDiagnostic {
    private PersistentBrowserDiagnostic() {
    }

    static void run(String[] args) throws IOException {
        boolean bundled = bundled(args);
        try (PlaywrightRuntimeInstanceLock runtimeLock = PlaywrightRuntimeInstanceLock.acquireDefault()) {
            // Always create our own new directory, outside production sessions.
            Path profile = Files.createTempDirectory("flipbot-persistent-diagnostic-");
            try (Playwright playwright = Playwright.create();
                 BrowserContext context = openContext(playwright.chromium(), profile, bundled, false)) {
                List<Page> pages = context.pages();
                Page page = pages.isEmpty() ? context.newPage() : pages.getFirst();
                Browser browser = context.browser();
                log.info("[BROWSER DIAGNOSTIC] mode=FRESH_PERSISTENT, engine={}, monitoring=OFF, playwrightVersion={}, chromeVersion={}, profile={}. No stored bot state, jobs, UI scripts, HTTP listeners or production session writes. Automation defaults remain enabled. Log in manually; close the window to finish.",
                        bundled ? "BUNDLED" : "SYSTEM_CHROME",
                        BrowserDiagnostic.playwrightVersion(),
                        browser == null ? "<unavailable>" : browser.version(), profile);
                try {
                    page.navigate(MarketplaceUrls.HOME, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
                } catch (PlaywrightException exception) {
                    if (page.isClosed()) {
                        return;
                    }
                    log.warn("[BROWSER DIAGNOSTIC] Initial navigation did not settle. The same window remains available.");
                }
                BrowserDiagnostic.observe(page, context, false);
            }
            log.info("[BROWSER DIAGNOSTIC] Finished. Standalone test profile retained at {}. Production sessions were not replaced.", profile);
        }
    }

    public static BrowserContext openContext(BrowserType chromium, Path profile, boolean bundled, boolean headless) {
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(headless)
                // Same native popup-blocking choice as BrowserFactory. Keep
                // every other Playwright default, including automation flags.
                .setIgnoreDefaultArgs(List.of("--disable-popup-blocking"));
        if (!bundled) {
            options.setChannel("chrome");
        }
        return chromium.launchPersistentContext(profile, options);
    }

    static boolean bundled(String[] args) {
        if (args.length == 0 || (args.length == 1 && "bundled".equalsIgnoreCase(args[0]))) {
            return true;
        }
        if (args.length == 1 && "chrome".equalsIgnoreCase(args[0])) {
            return false;
        }
        throw new IllegalArgumentException("Use bundled or chrome. An existing profile or bot session cannot be supplied.");
    }
}
