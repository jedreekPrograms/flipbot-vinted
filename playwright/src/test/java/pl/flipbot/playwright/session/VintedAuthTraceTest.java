package pl.flipbot.playwright.session;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class VintedAuthTraceTest {
    private final BrowserContext context = mock(BrowserContext.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(VintedSessionDiagnostics.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Consumer<Response> listener;

    @Before
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void captureTrace() {
        appender.start();
        logger.addAppender(appender);
        VintedSessionDiagnostics.install(context, 11L);
        ArgumentCaptor<Consumer<Response>> capture = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(context).onResponse(capture.capture());
        listener = capture.getValue();
    }

    @After
    public void detachTrace() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    public void followsSuccessfulLoginInvalidTokenAndFailedRefreshWithoutExposingCredentials() {
        Cookie cookie = new Cookie("access_token_web", "access-secret")
                .setDomain(".vinted.pl").setPath("/").setExpires(1_900_000_000d);
        when(context.cookies(anyList())).thenReturn(List.of(cookie));
        Response login = response("/web/api/auth/oauth", 200);
        when(login.headerValues("set-cookie")).thenReturn(List.of(
                "access_token_web=access-secret; Domain=.vinted.pl; Path=/; HttpOnly; Secure",
                "refresh_token_web=refresh-secret; Path=/; Max-Age=3600",
                "irrelevant=unrelated-secret; Path=/"));
        listener.accept(login);
        Response inbox = response("/api/v2/inbox?private=hidden-query", 401);
        when(inbox.request().headerValue("cookie")).thenReturn(
                "unrelated=unrelated-secret; access_token_web=access-secret; refresh_token_web=refresh-secret");
        when(inbox.request().headerValue("authorization")).thenReturn("Bearer access-secret");
        when(inbox.headerValue("www-authenticate")).thenReturn("Bearer error=\"invalid_token\"");
        listener.accept(inbox);
        Response refresh = response("/web/api/auth/refresh", 404);
        when(refresh.request().headerValue("cookie")).thenReturn("refresh_token_web=refresh-secret");
        listener.accept(refresh);

        List<String> trace = messages("[VINTED AUTH TRACE]");
        assertEquals(3, trace.size());
        String accessLabel = labelAfter(trace.getFirst(), "access_token_web=");
        String refreshLabel = labelAfter(trace.getFirst(), "refresh_token_web=");
        assertTrue(trace.get(1).contains("sent=access=[" + accessLabel + "],refresh=[" + refreshLabel + "]"));
        assertTrue(trace.get(1).contains("bearer=" + accessLabel));
        assertTrue(trace.get(1).contains("invalidToken=true"));
        assertTrue(trace.get(2).contains("status=404"));
        assertEquals(3, messages("[VINTED AUTH JAR]").size());
        assertTrue(messages("[VINTED AUTH JAR]").getFirst().contains("domain=.vinted.pl;path=/;expires="));
        for (String text : messages("")) {
            assertFalse(text.contains("secret"));
            assertFalse(text.contains("hidden-query"));
            assertFalse(text.contains("?private"));
        }
        verify(login, never()).body();
        verify(inbox, never()).body();
        verify(refresh, never()).body();
        verify(login.request(), never()).postData();
        verify(context, never()).clearCookies();
        verify(context, never()).addCookies(anyList());
    }

    @Test
    public void exposesDuplicateCookieOrderAndDeletionButRedactsConversationId() {
        Response response = response("/api/v2/conversations/987654321?access_token=hidden-query", 403);
        when(response.request().headerValue("cookie")).thenReturn(
                "access_token_web=old-secret; access_token_web=new-secret; refresh_token_web=");
        when(response.headerValues("set-cookie")).thenReturn(List.of(
                "access_token_web=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.vinted.pl; Path=/"));
        listener.accept(response);
        String text = messages("[VINTED AUTH TRACE]").getFirst();
        assertTrue(text.contains("endpoint=www.vinted.pl/api/v2/conversations/:id"));
        assertTrue(text.matches(".*sent=access=\\[id-[0-9a-f]{16}, id-[0-9a-f]{16}\\],refresh=\\[empty].*"));
        assertTrue(text.contains("access_token_web=empty;maxAge=0;expires=1970-01-01T00:00:00Z;domain=.vinted.pl;path=/"));
        assertFalse(text.contains("987654321"));
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("hidden-query"));
        assertTrue(text.contains("dataDomeChallengeHeader=false"));
    }

    @Test
    public void avoidsPreflightsUnrelatedEndpointsAndThirdPartyTraffic() {
        Response preflight = response("/messaging/real-time/subscribe", 204);
        when(preflight.request().method()).thenReturn("OPTIONS");
        Response asset = response("/some-script.js", 200);
        Response external = response("/web/api/auth/oauth", 403);
        when(external.url()).thenReturn("https://ads.example/web/api/auth/oauth");
        for (Response response : List.of(preflight, asset, external)) {
            listener.accept(response);
            verify(response.request(), never()).headerValue(anyString());
            verify(response, never()).headerValues(anyString());
        }
        assertTrue(messages("[VINTED AUTH TRACE]").isEmpty());
    }

    @Test
    public void boundsHeaderReadsAndCookieJarSnapshotsDuringFailureStorm() {
        Response response = response("/api/v2/inbox", 401);
        for (int i = 0; i < 1000; i++) {
            listener.accept(response);
        }
        assertEquals(60, messages("[VINTED AUTH TRACE]").size());
        assertEquals(6, messages("[VINTED AUTH JAR]").size());
        verify(response.request(), times(60)).headerValue("cookie");
        verify(context, times(6)).cookies(anyList());
        verify(response, never()).body();
    }

    @Test
    public void unavailableHeadersDoNotInterruptExistingWarningsOrSubsequentTracing() {
        Response broken = response("/api/v2/inbox", 401);
        when(broken.request().headerValue("cookie")).thenThrow(new IllegalStateException("private-secret"));
        listener.accept(broken);
        listener.accept(response("/api/v2/users/current", 200));
        assertEquals(1, messages("[VINTED HTTP]").size());
        assertEquals(1, messages("[VINTED AUTH TRACE]").size());
        assertEquals(1, messages("[VINTED AUTH TRACE UNAVAILABLE]").size());
        assertFalse(String.join("", messages("")).contains("private-secret"));
    }

    @Test
    public void snapshotsTokenOmissionOn307WithoutAssumingTheCookieWasDeleted() {
        Cookie cookie = new Cookie("access_token_web", "still-in-browser-secret")
                .setDomain(".vinted.pl").setPath("/").setHttpOnly(true).setSecure(true);
        when(context.cookies(anyList())).thenReturn(List.of(cookie));
        Response accepted = response("/api/v2/users/current", 200);
        when(accepted.request().headerValue("cookie")).thenReturn("access_token_web=still-in-browser-secret");
        listener.accept(accepted);
        Response redirect = response("/inbox/12345?private=hidden-query", 307);
        when(redirect.request().isNavigationRequest()).thenReturn(true);
        when(redirect.request().resourceType()).thenReturn("document");
        listener.accept(redirect);
        listener.accept(redirect);
        List<String> jar = messages("[VINTED AUTH JAR]");
        assertEquals(1, jar.size());
        assertTrue(jar.getFirst().contains("reason=tokensMissingOnPreviouslyTokenBearingHost"));
        assertTrue(jar.getFirst().contains("access_token_web=id-"));
        assertTrue(jar.getFirst().contains("httpOnly=true;secure=true"));
        assertFalse(String.join("", messages("")).contains("secret"));
        assertFalse(String.join("", messages("")).contains("hidden-query"));
        verify(context, never()).clearCookies();
    }

    private Response response(String path, int status) {
        Response response = mock(Response.class);
        Request request = mock(Request.class);
        when(response.url()).thenReturn("https://www.vinted.pl" + path);
        when(response.status()).thenReturn(status);
        when(response.request()).thenReturn(request);
        // Match the real Java API, rather than Mockito's default empty list.
        when(response.headerValues("set-cookie")).thenReturn(null);
        when(request.method()).thenReturn("GET");
        when(request.resourceType()).thenReturn("xhr");
        return response;
    }

    private List<String> messages(String prefix) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(text -> text.startsWith(prefix)).toList();
    }

    private String labelAfter(String text, String prefix) {
        int start = text.indexOf(prefix) + prefix.length();
        return text.substring(start, start + 19);
    }
}
