package pl.flipbot.playwright.login;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;
import pl.flipbot.playwright.model.BotDetailsDto;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;

public class LoginSessionPersistenceTest {
    @Test
    public void interactiveSessionIsSavedOnlyAfterStrongAuthenticationIsConfirmed() {
        runInteractiveFlow(false);
    }

    @Test
    public void submittedFormWithoutConfirmedAuthenticationNeverOverwritesSavedSession() {
        runInteractiveFlow(true);
    }

    private void runInteractiveFlow(boolean authenticationFails) {
        BotContext context = mock(BotContext.class);
        Page page = mock(Page.class);
        when(context.getPage()).thenReturn(page);
        when(page.context()).thenReturn(mock(BrowserContext.class));
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(3L);
        bot.setEmail("fixture@example.invalid");
        bot.setPassword("fixture-password");
        when(context.getBot()).thenReturn(bot);
        LoginCredentials.LoginForm form = new LoginCredentials.LoginForm(
                mock(Locator.class), mock(Locator.class), mock(Locator.class));

        try (MockedConstruction<LoginSessionVerifier> sessions = mockConstruction(LoginSessionVerifier.class,
                     (mock, ignored) -> {
                         if (authenticationFails) {
                             when(mock.waitForAuthenticatedSession(page)).thenThrow(
                                     new IllegalStateException("No strong authenticated signal"));
                         } else {
                             when(mock.waitForAuthenticatedSession(page)).thenReturn("header-conversations-button");
                         }
                     });
             MockedConstruction<LoginNavigation> navigation = mockConstruction(LoginNavigation.class);
             MockedConstruction<LoginCredentials> credentials = mockConstruction(LoginCredentials.class,
                     (mock, ignored) -> when(mock.resolveForm(page)).thenReturn(form));
             MockedConstruction<LoginSubmission> submissions = mockConstruction(LoginSubmission.class);
             MockedConstruction<MarketplaceNavigator> home = mockConstruction(MarketplaceNavigator.class)) {
            LoginService service = new LoginService(context);
            if (authenticationFails) {
                assertThrows(IllegalStateException.class, service::login);
            } else {
                service.login();
            }

            LoginNavigation nav = navigation.constructed().getFirst();
            LoginCredentials fields = credentials.constructed().getFirst();
            LoginSubmission submit = submissions.constructed().getFirst();
            LoginSessionVerifier session = sessions.constructed().getFirst();
            InOrder order = inOrder(nav, fields, submit, session, context);
            order.verify(nav).openEmailForm(page);
            order.verify(fields).resolveForm(page);
            order.verify(fields).fillAndVerify(page, form);
            order.verify(submit).submitAndObserve(page, form);
            order.verify(session).waitForAuthenticatedSession(page);
            if (authenticationFails) {
                verify(context, never()).saveSession();
            } else {
                order.verify(context).saveSession();
            }
        }
    }
}
