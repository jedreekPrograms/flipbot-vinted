package pl.flipbot.playwright.marketstats;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.session.VintedAuthenticationEvidence;
import pl.flipbot.playwright.target.VintedHumanVerificationRequiredException;
import pl.flipbot.playwright.verification.CookieConsentHandler;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

@Slf4j
final class MarketObserverPage {
    private final CookieConsentHandler cookieConsentHandler = new CookieConsentHandler();
    private final HumanVerificationHandler humanVerificationHandler = new HumanVerificationHandler();

    boolean openCatalogSession(BotContext context, BotDetailsDto observerBot) {
        MarketplaceNavigator navigator = new MarketplaceNavigator(context);
        navigator.goToCatalog();
        prepare(context);

        boolean authenticated = VintedAuthenticationEvidence.signal(context.getPage()) != null;

        if (authenticated) {
            log.info(
                    "[MARKET STATS] Observer bot {} restored an authenticated Vinted session. Using it for this read-only collection; no interactive login is needed.",
                    observerBot.getId()
            );
        } else {
            log.info(
                    "[MARKET STATS] Observer bot {} has no verifiably authenticated stored Vinted session. Continuing immediately with anonymous READ-ONLY catalog collection; interactive login is intentionally skipped and anonymous state will not be saved.",
                    observerBot.getId()
            );
        }

        return authenticated;
    }

    void prepare(BotContext context) {
        Page page = context.getPage();
        cookieConsentHandler.acceptAllIfVisible(page);
        if (humanVerificationHandler.isHumanVerificationVisible(page)) {
            throw new VintedHumanVerificationRequiredException(
                    "Market observer encountered visible human verification. "
                            + "Stopping this pass until manual verification is completed."
            );
        }
    }
}
