package pl.flipbot.playwright.marketstats;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.verification.CookieConsentHandler;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class MarketObserverAuthenticationTest {
    @Test
    public void staleAuthenticatedHeaderBehindLoginFormDoesNotPermitSessionSaving() {
        assertFalse(openObserver(true));
    }

    @Test
    public void healthyAuthenticatedHeaderPermitsPreservingObserverSession() {
        assertTrue(openObserver(false));
    }

    private boolean openObserver(boolean credentialFormVisible) {
        BotContext context = mock(BotContext.class);
        Page page = mock(Page.class);
        when(context.getPage()).thenReturn(page);
        when(page.url()).thenReturn("https://www.vinted.pl/catalog");
        Locator absent = mock(Locator.class);
        Locator conversations = visible();
        when(page.locator(anyString())).thenReturn(absent);
        when(page.getByTestId(anyString())).thenReturn(absent);
        when(page.getByTestId("header-conversations-button")).thenReturn(conversations);
        Locator credentials = credentialFormVisible ? visible() : absent;
        when(page.locator("#username, #password")).thenReturn(credentials);
        try (MockedConstruction<MarketplaceNavigator> navigation = mockConstruction(MarketplaceNavigator.class);
             MockedConstruction<CookieConsentHandler> consent = mockConstruction(CookieConsentHandler.class);
             MockedConstruction<HumanVerificationHandler> verification = mockConstruction(HumanVerificationHandler.class)) {
            return new MarketObserverPage().openCatalogSession(context, new BotDetailsDto());
        }
    }

    private Locator visible() {
        Locator locator = mock(Locator.class);
        when(locator.count()).thenReturn(1);
        when(locator.nth(0)).thenReturn(locator);
        when(locator.isVisible()).thenReturn(true);
        return locator;
    }
}
