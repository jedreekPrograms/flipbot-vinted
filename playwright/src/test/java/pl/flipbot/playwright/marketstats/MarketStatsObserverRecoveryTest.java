package pl.flipbot.playwright.marketstats;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;
import pl.flipbot.playwright.marketstats.dto.KnownMarketListingIdsDto;
import pl.flipbot.playwright.marketstats.dto.MarketStatsTargetDto;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.scanner.ListingScanner;
import pl.flipbot.playwright.target.VintedHumanVerificationRequiredException;
import pl.flipbot.playwright.verification.CookieConsentHandler;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MarketStatsObserverRecoveryTest {

    @Test
    public void observerAcceptsTheCurrentPolishPrivacyBanner() {
        Page page = mock(Page.class);
        BotContext context = mock(BotContext.class);
        when(context.getPage()).thenReturn(page);
        Locator legacy = mock(Locator.class);
        when(page.locator("#onetrust-accept-btn-handler")).thenReturn(legacy);
        when(legacy.first()).thenReturn(legacy);
        Locator buttons = mock(Locator.class);
        Locator acceptAll = mock(Locator.class);
        when(page.getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class))).thenReturn(buttons);
        when(buttons.count()).thenReturn(1);
        when(buttons.nth(0)).thenReturn(acceptAll);
        when(acceptAll.count()).thenReturn(1);
        when(acceptAll.isVisible()).thenReturn(true);
        when(acceptAll.isEnabled()).thenReturn(true);
        when(acceptAll.innerText(any(Locator.InnerTextOptions.class))).thenReturn("Zgoda na wszystkie");
        when(acceptAll.evaluate(anyString(), isNull(), any(Locator.EvaluateOptions.class))).thenReturn(true);

        try (MockedConstruction<HumanVerificationHandler> verification =
                     mockConstruction(HumanVerificationHandler.class)) {
            new MarketObserverPage().prepare(context);
        }
        verify(acceptAll).click(any(Locator.ClickOptions.class));
        verify(page, never()).waitForTimeout(anyDouble());
    }

    @Test
    public void emptyScannerResultCannotConfirmMarketBaselineCoverage() throws Exception {
        Page page = mock(Page.class);
        BotContext context = mock(BotContext.class);
        when(context.getPage()).thenReturn(page);
        try (MockedConstruction<CookieConsentHandler> consent = mockConstruction(CookieConsentHandler.class);
             MockedConstruction<HumanVerificationHandler> verification = mockConstruction(HumanVerificationHandler.class);
             MockedConstruction<ListingScanner> scanners = mockConstruction(ListingScanner.class,
                     (scanner, ignored) -> when(scanner.scan()).thenReturn(List.of()))) {
            MarketCatalogScan.ScanResult result = new MarketCatalogScan(config(), new MarketObserverPage())
                    .scan(context, 1L, new BotConfigurationDto(), false, true, Set.of(), false);
            assertFalse("A timeout/parse failure must not mark the baseline complete", result.complete());
        }
    }

    @Test
    public void visibleChallengeOnOneTargetPreventsScanningLaterTargets() {
        Page page = mock(Page.class);
        Locator absent = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(absent);
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(5L);
        MarketStatsApiClient api = mock(MarketStatsApiClient.class);
        when(api.getObserverBot()).thenReturn(bot);
        when(api.getTargets()).thenReturn(List.of(target(1L), target(2L)));
        when(api.getKnownListingIds(1L)).thenReturn(new KnownMarketListingIdsDto(1L, List.of(), false));

        try (MockedConstruction<BrowserManager> browsers = mockConstruction(BrowserManager.class);
             MockedConstruction<BotContext> contexts = mockConstruction(BotContext.class,
                     (context, ignored) -> {
                         when(context.getPage()).thenReturn(page);
                         when(context.getBot()).thenReturn(bot);
                     });
             MockedConstruction<MarketplaceNavigator> navigation = mockConstruction(MarketplaceNavigator.class);
             MockedConstruction<CookieConsentHandler> consent = mockConstruction(CookieConsentHandler.class);
             MockedConstruction<HumanVerificationHandler> verification = mockConstruction(HumanVerificationHandler.class,
                     (handler, ignored) -> when(handler.isHumanVerificationVisible(page)).thenReturn(false, true))) {
            assertThrows(VintedHumanVerificationRequiredException.class,
                    () -> new MarketStatsCollector(config(), api).collectOnce());
        }
        verify(api, never()).getKnownListingIds(2L);
        verify(api, never()).recordObservations(anyLong(), anyList(), anyMap(), anyBoolean());
    }

    private MarketStatsRuntimeConfig config() {
        return new MarketStatsRuntimeConfig(true, true, 15L, 600, 20, 0L, 6);
    }

    private MarketStatsTargetDto target(Long id) {
        return new MarketStatsTargetDto(id, "Samsung", "Galaxy S23 FE", "VINTED_MODEL",
                List.of("Elektronika", "Telefony komórkowe"), true, null, null, 1);
    }
}
