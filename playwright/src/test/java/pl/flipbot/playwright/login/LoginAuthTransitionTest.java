package pl.flipbot.playwright.login;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.verification.CookieConsentHandler;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class LoginAuthTransitionTest {
    @Test
    public void routeTransitionDoesNotClickDepartingEmailLinkAgain() {
        Fixture f = new Fixture();
        f.url.set("https://www.vinted.pl/member/login/select_type");
        when(f.emailLink.isVisible()).thenReturn(true);
        doAnswer(call -> {
            f.url.set("https://www.vinted.pl/member/login/email");
            return null;
        }).when(f.emailLink).click(any(Locator.ClickOptions.class));
        when(f.email.isVisible()).thenAnswer(call -> f.time.get() >= 1400);
        when(f.password.isVisible()).thenAnswer(call -> f.time.get() >= 1800);
        f.open();
        verify(f.emailLink, times(1)).click(any(Locator.ClickOptions.class));
        verify(f.emailLink, never()).evaluate(anyString(), any(), any(Locator.EvaluateOptions.class));
        assertTrue(f.time.get() >= 1800);
    }

    @Test
    public void formThatArrivesAtTheOldDeadlineWinsBeforeTimeout() {
        Fixture f = new Fixture();
        when(f.emailLink.isVisible()).thenAnswer(call -> f.time.get() >= 19400);
        doAnswer(call -> {
            f.time.addAndGet(800);
            f.url.set("https://www.vinted.pl/member/login/email");
            return null;
        }).when(f.emailLink).click(any(Locator.ClickOptions.class));
        when(f.email.isVisible()).thenAnswer(call -> f.time.get() >= 20200);
        when(f.password.isVisible()).thenAnswer(call -> f.time.get() >= 20200);
        f.open();
        assertTrue(f.time.get() > 20000);
    }

    @Test
    public void realStageProgressGetsTimeToRenderItsForm() {
        Fixture f = new Fixture();
        when(f.page.url()).thenAnswer(call -> f.time.get() >= 19000
                ? "https://www.vinted.pl/member/login/email" : f.url.get());
        when(f.email.isVisible()).thenAnswer(call -> f.time.get() >= 24000);
        when(f.password.isVisible()).thenAnswer(call -> f.time.get() >= 24000);
        f.open();
        assertTrue(f.time.get() >= 24000);
        verify(f.emailLink, never()).click(any(Locator.ClickOptions.class));
    }

    @Test
    public void unchangedRouteWithOnlyOneInputEventuallyFails() {
        Fixture f = new Fixture();
        f.url.set("https://www.vinted.pl/member/login/email");
        when(f.email.isVisible()).thenReturn(true);
        assertThrows(IllegalStateException.class, f::open);
        assertTrue(f.time.get() >= 20000 && f.time.get() <= 21000);
        verify(f.emailLink, never()).click(any(Locator.ClickOptions.class));
        verify(f.page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    private static final class Fixture {
        final Page page = mock(Page.class);
        final Locator empty = mock(Locator.class);
        final Locator email = mock(Locator.class);
        final Locator password = mock(Locator.class);
        final Locator emailLink = mock(Locator.class);
        final AtomicLong time = new AtomicLong();
        final AtomicReference<String> url = new AtomicReference<>("https://www.vinted.pl/");
        Fixture() {
            when(page.url()).thenAnswer(call -> url.get());
            when(page.locator(anyString())).thenReturn(empty);
            when(page.getByTestId(anyString())).thenReturn(empty);
            when(page.locator("#username")).thenReturn(email);
            when(page.locator("#password")).thenReturn(password);
            when(page.getByTestId("auth-select-type--login-email")).thenReturn(emailLink);
            doAnswer(call -> {
                time.addAndGet(((Double) call.getArgument(0)).longValue());
                return null;
            }).when(page).waitForTimeout(anyDouble());
        }
        void open() {
            try (MockedConstruction<CookieConsentHandler> ignored = mockConstruction(CookieConsentHandler.class)) {
                new LoginNavigation(time::get, new LoginSessionVerifier(null)).openEmailLogin(page);
            }
        }
    }
}
