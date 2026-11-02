package pl.flipbot.playwright.session;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class VintedCookieWireDiagnosticsTest {
    private final BrowserContext context = mock(BrowserContext.class);
    private final CDPSession cdp = mock(CDPSession.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(VintedCookieWireDiagnostics.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Map<String, Consumer<JsonObject>> listeners = new HashMap<>();

    @Before
    public void install() {
        Page page = mock(Page.class);
        when(context.pages()).thenReturn(List.of(page));
        when(context.newCDPSession(page)).thenReturn(cdp);
        doAnswer(call -> {
            listeners.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(cdp).on(anyString(), any());
        logs.start();
        logger.addAppender(logs);
        VintedCookieWireDiagnostics.install(context, 12L, 7L);
    }

    @After
    public void detach() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    public void explainsExcludedCookieEvenWhenExtraInfoArrivesBeforeTarget() {
        event("requestWillBeSentExtraInfo", """
                {"requestId":"request-1", "headers":{"Cookie":"unrelated=private-secret"}, "associatedCookies":[
                  {"cookie":{"name":"access_token_web","value":"private-secret","domain":".vinted.pl","path":"/",
                    "httpOnly":true,"secure":true,"sameSite":"Lax"},"blockedReasons":["UserPreferences"]}
                ]}
                """);
        target("request-1", "https://www.vinted.pl/inbox/987654321?token=private-secret");
        String text = messages();
        assertTrue(text.contains("target=www.vinted.pl/inbox/:id"));
        assertTrue(text.contains("request sent=access=[],refresh=[]"));
        assertTrue(text.contains("associated=[access_token_web=id-"));
        assertTrue(text.contains("httpOnly=true;secure=true;sameSite=Lax;blocked=[UserPreferences]"));
        assertFalse(text.contains("987654321"));
        assertFalse(text.contains("private-secret"));
        // The only native command subscribes to events; it never changes
        // routing, cache, cookie controls, headers or the cookie store.
        verify(cdp).send("Network.enable");
        verify(cdp, never()).send(anyString(), any());
        verify(context, never()).clearCookies();
        verify(context, never()).addCookies(anyList());
    }

    @Test
    public void recordsServerDeletionAndRejectionOutsideTheAuthEndpointAllowlist() {
        target("request-2", "https://www.vinted.pl/some-new-endpoint?token=private-secret");
        event("responseReceivedExtraInfo", """
                {"requestId":"request-2","statusCode":200,"headers":{
                  "set-cookie":"refresh_token_web=; Domain=.www.vinted.pl; Path=/; Max-Age=0; Secure; HttpOnly\\nother=private-secret",
                  "clear-site-data":"\\\"cookies\\\""},
                  "blockedCookies":[{"cookieLine":"access_token_web=private-secret; Domain=.vinted.pl; SameSite=None; Secure",
                    "blockedReasons":["ThirdPartyPhaseout"]}]}
                """);
        String text = messages();
        assertTrue(text.contains("refresh_token_web=empty;domain=.www.vinted.pl;path=/;maxAge=0;secure;httponly"));
        assertTrue(text.contains("sameSite=none;secure;blocked=[ThirdPartyPhaseout]"));
        assertTrue(text.contains("clearSiteCookies=true"));
        assertFalse(text.contains("private-secret"));
    }

    @Test
    public void keepsNativeRequestIdAcrossRedirectWithoutClaimingHopOrder() {
        event("responseReceivedExtraInfo", """
                {"requestId":"redirect-1","statusCode":307,"headers":{"set-cookie":"access_token_web=; Max-Age=0"}}
                """);
        target("redirect-1", "https://www.vinted.pl/inbox/987654321");
        target("redirect-1", "https://www.vinted.pl/member/login/email?token=private-secret");
        assertTrue(messages().contains("response status=307"));
        assertFalse(messages().contains("wire=2"));
        assertFalse(messages().contains("private-secret"));
        assertFalse(messages().contains("987654321"));
    }

    @Test
    public void ignoresThirdPartyEventsAndBoundsFailureStorm() {
        target("external", "https://vinted.pl.attacker.example/inbox/987654321");
        event("requestWillBeSentExtraInfo", """
                {"requestId":"external","headers":{"Cookie":"access_token_web=private-secret"},"associatedCookies":[]}
                """);
        assertEquals("", messages());
        for (int i = 0; i < 1000; i++) {
            target("request-" + i, "https://www.vinted.pl/api/v2/users/current");
            event("requestWillBeSentExtraInfo", "{\"requestId\":\"request-" + i + "\",\"headers\":{},\"associatedCookies\":[]}");
        }
        assertEquals(200, logs.list.size());
    }

    private void target(String id, String url) {
        JsonObject event = new JsonObject();
        event.addProperty("requestId", id);
        event.addProperty("type", "Document");
        JsonObject request = new JsonObject();
        request.addProperty("url", url);
        request.addProperty("method", "GET");
        event.add("request", request);
        listeners.get("Network.requestWillBeSent").accept(event);
    }

    private void event(String name, String json) {
        listeners.get("Network." + name).accept(JsonParser.parseString(json).getAsJsonObject());
    }

    private String messages() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
