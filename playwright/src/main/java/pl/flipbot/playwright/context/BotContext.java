package pl.flipbot.playwright.context;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.session.SessionManager;
import pl.flipbot.playwright.session.VintedSessionDiagnostics;

@Slf4j
@Getter
public class BotContext implements AutoCloseable {

    private static final String ANONYMOUS_MARKET_OBSERVER_NAME =
            "Anonymous Market Observer";

    private final BotDetailsDto bot;

    private final BrowserContext browserContext;

    private final Page page;

    private final SessionManager sessionManager;

    private final boolean storedSessionRestored;
    private final boolean ownsBrowserContext;

    private final AtomicInteger extraPageEvents = new AtomicInteger();

    public BotContext(
            BotDetailsDto bot,
            BrowserManager browserManager
    ) {
        this.bot = bot;
        this.sessionManager = new SessionManager();
        this.ownsBrowserContext = true;

        Path sessionFile = null;

        if (shouldRestoreStoredSession(bot)
                && sessionManager.sessionExists(bot.getId())) {
            sessionFile = sessionManager.sessionFile(bot.getId());
        } else if (isAnonymousMarketObserver(bot)) {
            log.info(
                    "[SESSION] Anonymous market observer {} will not restore any stored Vinted session. "
                            + "Collection stays account-free even if an old sessions/bot-{}.json file still exists locally.",
                    bot.getId(),
                    bot.getId()
            );
        }

        this.storedSessionRestored = sessionFile != null;

        /*
         * A stored production session is authoritative. BrowserManager already
         * fails closed when Playwright cannot restore storageState; do not add
         * a second fallback here that could silently turn a logged-in bot into
         * a clean context. The saved file remains untouched for diagnosis and
         * the scheduled job can retry normally.
         */
        this.browserContext = browserManager.createContext(sessionFile);

        VintedSessionDiagnostics.install(browserContext, bot.getId());

        if (!isAnonymousMarketObserver(bot)) {
            browserContext.addInitScript("delete Object.getPrototypeOf(navigator).webdriver;");
        }
        installPreemptivePopupSuppression();
        installAnonymousObserverUiStabilityIfNeeded();

        this.page = new SinglePageGuard(browserContext, bot.getId(), extraPageEvents).openMainPage();
    }

    private BotContext(BotContext owner, BotConfigurationDto configuration) {
        this.bot = owner.bot.withConfiguration(configuration);
        this.browserContext = owner.browserContext;
        this.page = owner.page;
        this.sessionManager = owner.sessionManager;
        this.storedSessionRestored = owner.storedSessionRestored;
        this.ownsBrowserContext = false;
    }

    /** A product view shares the live page but cannot close/persist its owner. */
    public BotContext forProduct(BotConfigurationDto configuration) {
        return new BotContext(this, configuration);
    }

    static boolean shouldRestoreStoredSession(
            BotDetailsDto bot
    ) {
        return !isAnonymousMarketObserver(bot);
    }

    private static boolean isAnonymousMarketObserver(
            BotDetailsDto bot
    ) {
        if (bot == null) {
            return false;
        }

        return ANONYMOUS_MARKET_OBSERVER_NAME.equals(bot.getName())
                && isBlank(bot.getEmail())
                && isBlank(bot.getPassword());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void installPreemptivePopupSuppression() {
        browserContext.addInitScript(
                ContextScripts.PREVENT_EXTRA_PAGES
        );

        log.info(
                "[BROWSER] Preemptive popup suppression enabled for bot {}. "
                        + "window.open, links/forms targeting a new browsing context, and imperative form submissions are blocked before ad/RTB scripts can create a visible tab.",
                bot.getId()
        );
    }

    private void installAnonymousObserverUiStabilityIfNeeded() {
        if (!isAnonymousMarketObserver(bot)) {
            return;
        }

        browserContext.addInitScript(
                ContextScripts.OBSERVER_MODEL_LABELS
        );

        log.info(
                "[MARKET STATS UI] Anonymous observer {} UI guard enabled. "
                        + "Model-row labels are normalized without relaxing exact model verification. Cookie consent uses the shared handler, with no second observer clicker.",
                bot.getId()
        );
    }

    static String preemptivePopupSuppressionScript() {
        return ContextScripts.PREVENT_EXTRA_PAGES;
    }

    static String anonymousObserverUiStabilityScript() {
        return ContextScripts.OBSERVER_MODEL_LABELS;
    }

    public void saveSession() {
        if (!ownsBrowserContext) {
            return;
        }

        if (isAnonymousMarketObserver(bot)) {
            log.info(
                    "[SESSION] Skipping session save for anonymous market observer {}.",
                    bot.getId()
            );
            return;
        }

        sessionManager.saveSession(
                bot.getId(),
                browserContext
        );
    }

    @Override
    public void close() {
        if (!ownsBrowserContext) {
            return;
        }
        int blockedExtraPages = extraPageEvents.get();

        if (blockedExtraPages > 0) {
            log.info(
                    "[BROWSER] Bot {} job blocked {} additional browser tab/window event(s). Main page remained isolated.",
                    bot.getId(),
                    blockedExtraPages
            );
        }

        browserContext.close();
    }
}
