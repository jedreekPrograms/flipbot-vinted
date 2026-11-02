package pl.flipbot.playwright.login;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.verification.CookieConsentHandler;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class LoginLateSubmissionTransitionTest {
    @Test
    public void lateAuthenticatedHeaderStopsRetryBeforeReadingDepartedInputs() {
        Fixture f = new Fixture();
        f.lateAuthentication = true;
        f.rejectLateCredentialReads();

        f.submit();

        f.verifySingleSubmit();
    }

    @Test
    public void disappearingFormAfterPollDeadlineStopsRetryWithoutSavingASession() {
        Fixture f = new Fixture();
        when(f.email.isVisible()).thenAnswer(call -> f.time.get() < 6500);
        f.rejectLateCredentialReads();

        f.submit();

        f.verifySingleSubmit();
        verify(f.context, never()).saveSession();
    }

    @Test
    public void lateRouteTransitionDoesNotSubmitTheOldFormAgain() {
        Fixture f = new Fixture();
        when(f.page.url()).thenAnswer(call -> f.time.get() >= 6500
                ? "https://www.vinted.pl/session-refresh?ref_url=%2F" : Fixture.LOGIN_URL);
        f.rejectLateCredentialReads();

        f.submit();

        f.verifySingleSubmit();
    }

    @Test
    public void unchangedVisibleFormStillHasBoundedRetriesAndNeverSavesAnUnverifiedSession() {
        Fixture f = new Fixture();

        assertThrows(IllegalStateException.class, f::submit);

        verify(f.submitButton).click(any(Locator.ClickOptions.class));
        verify(f.password).press(eq("Enter"), any(Locator.PressOptions.class));
        verify(f.form).evaluate("form => form.requestSubmit()");
        verify(f.context, never()).saveSession();
    }

    private static final class Fixture {
        static final String LOGIN_URL = "https://www.vinted.pl/member/login/email";
        final AtomicLong time = new AtomicLong();
        final Page page = mock(Page.class);
        final BotContext context = mock(BotContext.class);
        final Locator email = mock(Locator.class);
        final Locator password = mock(Locator.class);
        final Locator form = mock(Locator.class);
        final Locator submitButton = mock(Locator.class);
        final Locator empty = mock(Locator.class);
        boolean lateAuthentication;

        Fixture() {
            BotDetailsDto bot = new BotDetailsDto();
            bot.setId(3L);
            bot.setEmail("fixture@example.invalid");
            bot.setPassword("fixture-password");
            when(context.getBot()).thenReturn(bot);
            when(page.url()).thenReturn(LOGIN_URL);
            when(page.locator(anyString())).thenReturn(empty);
            when(page.getByTestId(anyString())).thenReturn(empty);
            when(page.locator("#username")).thenReturn(email);
            when(page.locator("#password")).thenReturn(password);
            when(form.locator(anyString())).thenReturn(empty);
            when(form.locator("button[type='submit']:visible, input[type='submit']:visible"))
                    .thenReturn(submitButton);
            for (Locator locator : new Locator[]{email, password, submitButton}) {
                when(locator.count()).thenReturn(1);
                when(locator.nth(0)).thenReturn(locator);
                when(locator.first()).thenReturn(locator);
                when(locator.isVisible()).thenReturn(true);
                when(locator.isEnabled()).thenReturn(true);
            }
            when(email.inputValue(any(Locator.InputValueOptions.class))).thenReturn(bot.getEmail());
            when(password.inputValue(any(Locator.InputValueOptions.class))).thenReturn(bot.getPassword());
            doAnswer(call -> {
                time.addAndGet(((Double) call.getArgument(0)).longValue());
                return null;
            }).when(page).waitForTimeout(anyDouble());
        }

        void rejectLateCredentialReads() {
            when(email.inputValue(any(Locator.InputValueOptions.class))).thenAnswer(call -> {
                if (time.get() >= 6500) {
                    throw new AssertionError("Must not read a departed form while preparing a retry");
                }
                return context.getBot().getEmail();
            });
        }

        void submit() {
            try (MockedConstruction<CookieConsentHandler> consent = mockConstruction(CookieConsentHandler.class);
                 MockedConstruction<HumanVerificationHandler> verification = mockConstruction(HumanVerificationHandler.class)) {
                LoginSessionVerifier session = spy(new LoginSessionVerifier(context));
                doAnswer(call -> lateAuthentication && time.get() >= 6500
                        ? "header-conversations-button" : null).when(session).authenticatedSignal(page);
                new LoginSubmission(time::get, new LoginCredentials(context), session)
                        .submitAndObserve(page, new LoginCredentials.LoginForm(email, password, form));
            }
        }

        void verifySingleSubmit() {
            verify(submitButton).click(any(Locator.ClickOptions.class));
            verify(password, never()).press(eq("Enter"), any(Locator.PressOptions.class));
            verify(form, never()).evaluate(anyString());
            verify(email, never()).fill(anyString(), any(Locator.FillOptions.class));
            verify(password, never()).fill(anyString(), any(Locator.FillOptions.class));
        }
    }
}
