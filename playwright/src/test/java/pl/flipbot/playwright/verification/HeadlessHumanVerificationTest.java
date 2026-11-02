package pl.flipbot.playwright.verification;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import pl.flipbot.playwright.browser.BrowserInteractionMode;
import pl.flipbot.playwright.target.VintedHumanVerificationRequiredException;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class HeadlessHumanVerificationTest {
    @Test
    public void hiddenChallengeYieldsWithoutWaitingForAnInvisibleHuman() {
        Fixture f = new Fixture(true, true);
        doThrow(new AssertionError("Headless verification must not wait"))
                .when(f.page).waitForTimeout(anyDouble());
        VintedHumanVerificationRequiredException failure = assertThrows(
                VintedHumanVerificationRequiredException.class,
                () -> new HumanVerificationHandler().waitUntilVerified(f.page));
        assertTrue(failure.getMessage().contains("Releasing the headless job"));
        verify(f.page, never()).waitForTimeout(anyDouble());
        verify(f.context, never()).clearCookies();
        verify(f.page, never()).close();
    }

    @Test
    public void visibleChallengeStillWaitsForManualCompletionOnTheSamePage() {
        Fixture f = new Fixture(false, true);
        doAnswer(call -> {
            f.challenge.set(false);
            return null;
        }).when(f.page).waitForTimeout(anyDouble());
        new HumanVerificationHandler().waitUntilVerified(f.page);
        verify(f.page).waitForTimeout(1000d);
        verify(f.page, never()).close();
        verify(f.context, never()).clearCookies();
    }

    @Test
    public void headlessPageWithoutPositiveChallengeEvidenceContinuesNormally() {
        Fixture f = new Fixture(true, false);
        new HumanVerificationHandler().waitUntilVerified(f.page);
        verify(f.page, never()).waitForTimeout(anyDouble());
        verify(f.page, never()).close();
    }

    private static final class Fixture {
        final Page page = mock(Page.class);
        final BrowserContext context = mock(BrowserContext.class);
        final AtomicBoolean challenge;

        Fixture(boolean headless, boolean initiallyChallenged) {
            challenge = new AtomicBoolean(initiallyChallenged);
            Locator locator = mock(Locator.class);
            when(page.context()).thenReturn(context);
            when(page.url()).thenReturn("https://www.vinted.pl/member/login/email");
            when(page.title()).thenAnswer(call -> challenge.get() ? "Checking your browser" : "Vinted");
            when(page.locator(anyString())).thenReturn(locator);
            when(locator.first()).thenReturn(locator);
            when(locator.innerText()).thenReturn("");
            when(page.getByRole(any(), any(Page.GetByRoleOptions.class))).thenReturn(locator);
            BrowserInteractionMode.register(context, headless);
        }
    }
}
