package pl.flipbot.playwright.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BrowserFactory {

    private static final String ENGINE_ENV = "FLIPBOT_BROWSER_ENGINE";

    private static final String PLAYWRIGHT_DISABLE_POPUP_BLOCKING_ARG =
            "--disable-popup-blocking";

    private static final List<String> IGNORED_PLAYWRIGHT_DEFAULT_ARGS =
            List.of(PLAYWRIGHT_DISABLE_POPUP_BLOCKING_ARG);

    private BrowserFactory() {}


    public static Browser createBrowser(
            Playwright playwright,
            boolean headless
    ) {
        return createBrowser(playwright, headless, System.getenv(ENGINE_ENV));
    }

    static Browser createBrowser(Playwright playwright, boolean headless, String requestedEngine) {
        String engine = requestedEngine == null || requestedEngine.isBlank()
                ? "bundled" : requestedEngine.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (engine) {
            case "bundled" -> createBundledBrowser(playwright, headless);
            case "chrome" -> createSystemChromeBrowser(playwright, headless);
            default -> throw new IllegalArgumentException("Invalid FLIPBOT_BROWSER_ENGINE. Use bundled or chrome.");
        };
    }

    /** Explicit supported-binary comparison; never silently falls back to system Chrome. */
    public static Browser createBundledBrowser(Playwright playwright, boolean headless) {
        return launchBrowser(playwright, headless, false);
    }

    public static Browser createSystemChromeBrowser(Playwright playwright, boolean headless) {
        return launchBrowser(playwright, headless, true);
    }

    private static Browser launchBrowser(
            Playwright playwright,
            boolean headless,
            boolean useChromeChannel
    ) {
        BrowserType.LaunchOptions options =
                new BrowserType.LaunchOptions()
                        .setHeadless(headless)
                        .setIgnoreDefaultArgs(
                                IGNORED_PLAYWRIGHT_DEFAULT_ARGS
                        );

        if (useChromeChannel) {
            options.setChannel("chrome");
        }

        return playwright.chromium().launch(options);
    }


    static List<String> ignoredPlaywrightDefaultArgs() {
        return IGNORED_PLAYWRIGHT_DEFAULT_ARGS;
    }
}
