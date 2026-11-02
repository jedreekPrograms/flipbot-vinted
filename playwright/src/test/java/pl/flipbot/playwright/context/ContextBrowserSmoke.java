package pl.flipbot.playwright.context;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;

/** Real engine execution of the same script bytes installed by BotContext. */
public final class ContextBrowserSmoke {
    private ContextBrowserSmoke() {}

    public static void verify(Browser browser) {
        try (BrowserContext context = browser.newContext()) {
            context.route("**/*", route -> route.fulfill(new Route.FulfillOptions()
                    .setStatus(200).setContentType("text/html").setBody("""
                            <style>button{width:24px;height:24px} .row{display:flex;gap:8px}</style>
                            <div class="row"><button data-testid="selectable-item-brand_collection-123"></button>
                              <span>Galaxy A56</span></div>
                            <div class="row"><button data-testid="selectable-item-brand_collection-124"></button>
                              <button data-testid="selectable-item-brand_collection-125"></button>
                              <span>Shared panel text</span><span>Pokaż wyniki</span></div>
                            <button id="consent" onclick="window.consentClicks=(window.consentClicks||0)+1">Zgoda na wszystkie</button>
                            """)));
            context.addInitScript(ContextScripts.OBSERVER_MODEL_LABELS);
            context.addInitScript(ContextScripts.PREVENT_EXTRA_PAGES);
            Page page = context.newPage();
            page.navigate("https://www.vinted.pl/context-script-local-fixture");
            page.waitForFunction("() => document.querySelector('[data-testid$=\"-123\"]').title === 'Galaxy A56'");
            if (page.locator("[data-testid$='-124']").getAttribute("title") != null) {
                throw new AssertionError("Observer guessed a label from a panel containing multiple model IDs");
            }
            page.evaluate("""
                    () => {
                        const row = document.createElement('div');
                        row.className = 'row';
                        row.innerHTML = '<button data-testid="selectable-item-brand_collection-126"></button><span>iPhone 17</span>';
                        document.body.append(row);
                    }
                    """);
            page.waitForFunction("() => document.querySelector('[data-testid$=\"-126\"]').title === 'iPhone 17'");
            if (!Boolean.TRUE.equals(page.evaluate("() => window.open('about:blank') === null"))
                    || context.pages().size() != 1) {
                throw new AssertionError("Context popup script allowed an extra browsing context");
            }
            if (((Number) page.evaluate("window.consentClicks || 0")).intValue() != 0) {
                throw new AssertionError("Observer label normalization clicked the consent dialog");
            }
            Page foreign = context.newPage();
            foreign.navigate("https://example.org/context-script-local-fixture");
            foreign.waitForTimeout(250);
            if (foreign.locator("[data-testid$='-123']").getAttribute("title") != null) {
                throw new AssertionError("Observer script modified a foreign host");
            }
            System.out.println("CONTEXT SCRIPTS SMOKE PASS siblingModelLabels=true dynamicRows=true ambiguousLabelsRejected=true popupBlocked=true noConsentClicker=true foreignHostUntouched=true localHtmlOnly=true");
        }
    }
}
