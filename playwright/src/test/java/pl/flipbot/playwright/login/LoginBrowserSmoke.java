package pl.flipbot.playwright.login;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotDetailsDto;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

/** Uses synthetic credentials and intercepted HTML; no account or backend is contacted. */
public final class LoginBrowserSmoke {
    private LoginBrowserSmoke() {}

    public static void verify(BrowserContext browser) {
        AtomicInteger requests = new AtomicInteger();
        browser.route("**/*", route -> {
            if (route.request().isNavigationRequest()) {
                requests.incrementAndGet();
            }
            route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody(FIXTURE));
        });
        Page page = browser.newPage();
        try {
            page.navigate("https://www.vinted.pl/");
            BotContext context = mock(BotContext.class);
            when(context.getPage()).thenReturn(page);
            BotDetailsDto bot = new BotDetailsDto();
            bot.setId(0L);
            bot.setEmail("fixture@example.invalid");
            bot.setPassword("local-fixture-password");
            when(context.getBot()).thenReturn(bot);

            LoginService login = new LoginService(context);
            login.login();
            require("header-conversations-button".equals(new LoginSessionVerifier(context).authenticatedSignal(page)),
                    "Login did not wait for a strong authenticated header");
            org.mockito.Mockito.verify(context, times(1)).saveSession();
            require(((Number) page.evaluate("window.submits")).intValue() == 1, "Login submitted more than once");
            require(!page.locator("#onetrust-accept-btn-handler").isVisible(), "Cookie consent was not accepted");
            int requestsBeforeReuse = requests.get();
            login.login();
            require(requests.get() == requestsBeforeReuse, "Healthy login was reloaded");
            require(((Number) page.evaluate("window.submits")).intValue() == 1, "Healthy login submitted again");
            org.mockito.Mockito.verify(context, times(1)).saveSession();
            System.out.println("LOGIN BROWSER SMOKE PASS registerToEmail=true credentialsStable=true singleSubmit=true "
                    + "verifiedSessionSaved=true healthyPageReused=true allRequestsFulfilledLocally=true");
        } finally {
            page.close();
            browser.unroute("**/*");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final String FIXTURE = """
            <!doctype html><meta charset="utf-8">
            <script>window.submits = 0;</script>
            <button id="onetrust-accept-btn-handler" onclick="this.hidden = true">Zgoda na wszystkie</button>
            <button data-testid="header--login-button" id="login" onclick="document.querySelector('#register').hidden = false">
              Zaloguj się
            </button>
            <section id="register" data-testid="select-type-register-view" hidden>
              <button data-testid="auth-select-type--register-switch"
                onclick="this.parentNode.hidden = true; document.querySelector('#select').hidden = false;
                         history.replaceState(null, '', '/member/login/select_type')">Zaloguj się</button>
            </section>
            <section id="select" data-testid="select-type-login-view" hidden>
              <button data-testid="auth-select-type--login-email"
                onclick="this.parentNode.hidden = true; document.querySelector('#credentials').hidden = false;
                         history.replaceState(null, '', '/member/login/email')">E-mail</button>
            </section>
            <form id="credentials" hidden onsubmit="event.preventDefault(); window.submits++; this.hidden = true;
                 document.querySelector('#login').hidden = true; document.querySelector('#conversations').hidden = false;
                 history.replaceState(null, '', '/')">
              <label for="username">E-mail</label><input id="username">
              <label for="password">Hasło</label><input id="password" type="password">
              <button type="submit">Kontynuuj</button>
            </form>
            <button id="conversations" data-testid="header-conversations-button" hidden>Wiadomości</button>
            """;
}
