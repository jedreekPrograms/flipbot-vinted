package pl.flipbot.playwright.verification;

import org.junit.Test;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import org.mockito.ArgumentCaptor;
import java.util.regex.Pattern;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CookieConsentHandlerTest {

    @Test
    public void consentProbeOnlyResolvesExactAcceptAllCandidates() {
        Page page = mock(Page.class);
        Locator legacy = mock(Locator.class);
        Locator candidates = mock(Locator.class);
        Locator accept = mock(Locator.class);
        when(page.locator("#onetrust-accept-btn-handler")).thenReturn(legacy);
        when(legacy.first()).thenReturn(legacy);
        when(page.getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class))).thenReturn(candidates);
        when(candidates.count()).thenReturn(1);
        when(candidates.nth(0)).thenReturn(accept);
        when(accept.count()).thenReturn(1);
        when(accept.isVisible()).thenReturn(true);
        when(accept.isEnabled()).thenReturn(true);
        when(accept.innerText(any(Locator.InnerTextOptions.class))).thenReturn("Zgoda na wszystkie");
        when(accept.evaluate(anyString(), isNull(), any(Locator.EvaluateOptions.class))).thenReturn(true);

        assertTrue(new CookieConsentHandler().acceptAllIfVisible(page));

        ArgumentCaptor<Page.GetByRoleOptions> options = ArgumentCaptor.forClass(Page.GetByRoleOptions.class);
        verify(page).getByRole(eq(AriaRole.BUTTON), options.capture());
        Pattern name = (Pattern) options.getValue().name;
        assertTrue(name.matcher("Zgoda na wszystkie").matches());
        assertFalse(name.matcher("Wybierz niezbędne").matches());
        assertFalse(name.matcher("Nie zgoda na wszystkie").matches());
        verify(page, never()).locator("button");
        verify(accept, never()).innerText();
        verify(accept).click(any(Locator.ClickOptions.class));
    }

    @Test
    public void exhaustedSharedBudgetLeavesVisibleConsentForManualAction() {
        Page page = mock(Page.class);
        Locator control = mock(Locator.class);
        Locator empty = mock(Locator.class);
        when(page.locator("#onetrust-accept-btn-handler")).thenReturn(control);
        when(control.first()).thenReturn(control);
        when(control.count()).thenReturn(1);
        when(control.isVisible()).thenReturn(true);
        when(control.isEnabled()).thenReturn(true);
        when(control.evaluate(anyString(), isNull(), any(Locator.EvaluateOptions.class))).thenReturn(false);
        when(page.getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class))).thenReturn(empty);

        assertFalse(new CookieConsentHandler().acceptAllIfVisible(page));

        verify(control, never()).click(any(Locator.ClickOptions.class));
    }

    @Test
    public void recognizesPolishAcceptAllLabelFromCurrentVintedBanner() {
        assertTrue(CookieConsentHandler.isAcceptAllLabel("Zgoda na wszystkie"));
    }

    @Test
    public void recognizesWhitespaceAndCaseVariants() {
        assertTrue(CookieConsentHandler.isAcceptAllLabel("  ZGODA   NA WSZYSTKIE  "));
        assertTrue(CookieConsentHandler.isAcceptAllLabel("Accept all cookies"));
    }

    @Test
    public void doesNotClickNecessaryOnlyOrManageButtons() {
        assertFalse(CookieConsentHandler.isAcceptAllLabel("Wybierz niezbędne"));
        assertFalse(CookieConsentHandler.isAcceptAllLabel("Zarządzaj plikami cookies"));
        assertFalse(CookieConsentHandler.isAcceptAllLabel("Manage cookies"));
    }
}
