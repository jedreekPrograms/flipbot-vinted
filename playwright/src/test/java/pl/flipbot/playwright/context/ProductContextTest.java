package pl.flipbot.playwright.context;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.session.SessionManager;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ProductContextTest {
    @Test public void loginInitScriptIsInstalledOnceOnlyForAnAccountSession() {
        for (String mode : java.util.List.of("account", "observer")) {
            BotDetailsDto bot = new BotDetailsDto();
            bot.setId(3L);
            bot.setName(mode.equals("observer") ? "Anonymous Market Observer" : "fixture");
            BrowserManager browser = mock(BrowserManager.class);
            BrowserContext pageContext = mock(BrowserContext.class);
            when(browser.createContext(any())).thenReturn(pageContext);
            when(pageContext.newPage()).thenReturn(mock(Page.class));
            try (MockedConstruction<SessionManager> sessions = mockConstruction(SessionManager.class)) {
                BotContext owner = new BotContext(bot, browser);
                owner.forProduct(new BotConfigurationDto());
                owner.forProduct(new BotConfigurationDto());
                var scripts = org.mockito.ArgumentCaptor.forClass(String.class);
                verify(pageContext, atLeastOnce()).addInitScript(scripts.capture());
                long loginScripts = scripts.getAllValues().stream().filter(script -> script.contains("Object.getPrototypeOf(navigator)")).count();
                assertEquals(mode.equals("account") ? 1L : 0L, loginScripts);
                owner.close();
            }
        }
    }

    @Test public void productViewCannotChangeAccountConfigurationCloseItsPageOrPersistState() {
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(3L);
        BotConfigurationDto main = new BotConfigurationDto();
        BotConfigurationDto product = new BotConfigurationDto();
        bot.setConfiguration(main);
        BrowserManager browser = mock(BrowserManager.class);
        BrowserContext pageContext = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(browser.createContext(any())).thenReturn(pageContext);
        when(pageContext.newPage()).thenReturn(page);
        try (MockedConstruction<SessionManager> sessions = mockConstruction(SessionManager.class)) {
            BotContext owner = new BotContext(bot, browser);
            BotContext view = owner.forProduct(product);
            assertSame(page, view.getPage());
            assertSame(main, bot.getConfiguration());
            assertSame(product, view.getBot().getConfiguration());
            view.getBot().setConfiguration(new BotConfigurationDto());
            assertSame(main, bot.getConfiguration());
            view.saveSession();
            view.close();
            verify(pageContext, never()).close();
            verify(sessions.constructed().getFirst(), never()).saveSession(any(), any());
            owner.close();
            verify(pageContext).close();
        }
    }
}
