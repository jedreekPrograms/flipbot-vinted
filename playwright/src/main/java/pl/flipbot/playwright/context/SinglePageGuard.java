package pl.flipbot.playwright.context;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;

/** Chooses the main page and closes additional pages on the context's owning thread. */
@Slf4j
final class SinglePageGuard {
    private static final int EXTRA_PAGE_LOG_FIRST_EVENTS = 3;
    private static final int EXTRA_PAGE_LOG_INTERVAL = 25;
    private final BrowserContext browserContext;
    private final Long botId;
    private final AtomicInteger extraPageEvents;
    private Page page;

    SinglePageGuard(BrowserContext browserContext, Long botId, AtomicInteger extraPageEvents) {
        this.browserContext = browserContext;
        this.botId = botId;
        this.extraPageEvents = extraPageEvents;
    }

    Page openMainPage() {
        page = resolveMainPage();
        closeExistingExtraPages();
        registerSinglePageGuard();
        return page;
    }

    private Page resolveMainPage() {
        List<Page> existingPages = browserContext.pages();

        for (Page existingPage : existingPages) {
            if (isVintedPage(existingPage)) {
                log.info(
                        "[BROWSER] Reusing existing Vinted page: {}",
                        existingPage.url()
                );

                return existingPage;
            }
        }

        if (!existingPages.isEmpty()) {
            Page existingPage = existingPages.getFirst();

            log.info(
                    "[BROWSER] Reusing existing browser page: {}",
                    existingPage.url()
            );

            return existingPage;
        }

        log.info(
                "[BROWSER] No existing page found. Creating new page."
        );

        return browserContext.newPage();
    }

    private void closeExistingExtraPages() {
        List<Page> pages = new ArrayList<>(browserContext.pages());

        for (Page existingPage : pages) {
            if (existingPage == page) {
                continue;
            }

            closeUnexpectedPage(
                    existingPage,
                    "existing extra page",
                    true
            );
        }
    }

    private void registerSinglePageGuard() {
        /*
         * Register both popup-specific and context-wide hooks. Chromium can
         * surface ad-tech windows through slightly different opener paths; the
         * page-level popup hook gives us the earliest owner-specific callback,
         * while BrowserContext.onPage remains the catch-all.
         */
        page.onPopup(
                popup -> handleUnexpectedPageEvent(
                        popup,
                        "main-page popup"
                )
        );

        browserContext.onPage(
                newPage -> handleUnexpectedPageEvent(
                        newPage,
                        "browser-context page"
                )
        );

        log.info(
                "[BROWSER] Single-page fail-safe enabled for bot {}. Any additional browser tab/window that still reaches Chromium will be closed immediately.",
                botId
        );
    }

    private void handleUnexpectedPageEvent(
            Page unexpectedPage,
            String source
    ) {
        try {
            if (unexpectedPage == null
                    || unexpectedPage == page
                    || unexpectedPage.isClosed()) {
                return;
            }

            int eventNumber = extraPageEvents.incrementAndGet();

            closeUnexpectedPage(
                    unexpectedPage,
                    "single-page policy, "
                            + source
                            + ", extra-page event #"
                            + eventNumber,
                    shouldLogExtraPageEvent(eventNumber)
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "[BROWSER] Failed while handling unexpected page event for bot {}. source={}",
                    botId,
                    source,
                    exception
            );
        }
    }

    private boolean shouldLogExtraPageEvent(int eventNumber) {
        return eventNumber <= EXTRA_PAGE_LOG_FIRST_EVENTS
                || eventNumber % EXTRA_PAGE_LOG_INTERVAL == 0;
    }

    private void closeUnexpectedPage(
            Page unexpectedPage,
            String reason,
            boolean logEvent
    ) {
        try {
            if (unexpectedPage == page || unexpectedPage.isClosed()) {
                return;
            }

            String url = normalizeUrl(unexpectedPage.url());

            if (logEvent) {
                log.warn(
                        "[BROWSER] Closing unexpected browser page immediately. Bot: {}, reason: {}, initialURL: {}",
                        botId,
                        reason,
                        url
                );
            } else {
                log.debug(
                        "[BROWSER] Suppressed extra-page log. Bot: {}, reason: {}, initialURL: {}",
                        botId,
                        reason,
                        url
                );
            }

            unexpectedPage.close();

        } catch (Exception exception) {
            log.warn(
                    "[BROWSER] Could not close unexpected page for bot {}. reason={}",
                    botId,
                    reason,
                    exception
            );
        }
    }

    private boolean isVintedPage(Page candidate) {
        try {
            return MarketplaceUrls.isVintedUrl(
                    normalizeUrl(candidate.url())
            );
        } catch (Exception exception) {
            return false;
        }
    }

    private String normalizeUrl(String url) {
        return url == null
                ? ""
                : url.trim();
    }
}
