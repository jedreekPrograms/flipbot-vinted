package pl.flipbot.playwright.login;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.Test;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotDetailsDto;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class LoginLiveSessionTest {

    @Test
    public void authenticatedLivePageIsReusedAndCurrentPolishConsentIsAccepted() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://www.vinted.pl/");
        BotContext context = mock(BotContext.class);
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(3L);
        when(context.getBot()).thenReturn(bot);
        when(context.getPage()).thenReturn(page);
        when(page.context()).thenReturn(mock(BrowserContext.class));
        doThrow(new AssertionError("A healthy live session must not be reloaded"))
                .when(page).navigate(anyString(), any(Page.NavigateOptions.class));

        Locator conversations = mock(Locator.class);
        when(page.getByTestId(LoginSelectors.CONVERSATIONS_BUTTON)).thenReturn(conversations);
        when(conversations.count()).thenReturn(1);
        when(conversations.nth(0)).thenReturn(conversations);
        when(conversations.isVisible()).thenReturn(true);

        Locator oneTrust = mock(Locator.class);
        when(page.locator("#onetrust-accept-btn-handler")).thenReturn(oneTrust);
        when(oneTrust.first()).thenReturn(oneTrust);
        when(oneTrust.count()).thenReturn(0);

        Locator buttons = mock(Locator.class);
        Locator necessary = visibleButton("Wybierz niezbędne");
        Locator acceptAll = visibleButton("Zgoda na wszystkie");
        when(page.getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class))).thenReturn(buttons);
        when(buttons.count()).thenReturn(2);
        when(buttons.nth(0)).thenReturn(necessary);
        when(buttons.nth(1)).thenReturn(acceptAll);

        new LoginService(context).login();

        verify(acceptAll).click(any(Locator.ClickOptions.class));
        verify(necessary, never()).click(any(Locator.ClickOptions.class));
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page, never()).waitForTimeout(anyDouble());
        verify(context, never()).close();
    }

    private Locator visibleButton(String label) {
        Locator locator = mock(Locator.class);
        when(locator.count()).thenReturn(1);
        when(locator.isVisible()).thenReturn(true);
        when(locator.isEnabled()).thenReturn(true);
        when(locator.innerText(any(Locator.InnerTextOptions.class))).thenReturn(label);
        when(locator.evaluate(anyString(), isNull(), any(Locator.EvaluateOptions.class))).thenReturn(true);
        return locator;
    }
}
