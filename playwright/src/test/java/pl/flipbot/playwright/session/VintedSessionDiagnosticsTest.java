package pl.flipbot.playwright.session;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class VintedSessionDiagnosticsTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void logsOnlyBoundedVintedFailuresAndAuthDocumentsWithoutSecrets() {
        BrowserContext context = mock(BrowserContext.class);
        VintedSessionDiagnostics.install(context, 3L);
        ArgumentCaptor<Consumer<Response>> listener = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(context).onResponse(listener.capture());
        Logger logger = (Logger) LoggerFactory.getLogger(VintedSessionDiagnostics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            listener.getValue().accept(response("https://ads.example/noise", 403, false));
            listener.getValue().accept(response("https://www.vinted.pl/api/v2/items", 200, false));
            Response unauthorized = response("https://www.vinted.pl/api/v2/conversations?token=secret-value", 401, false);
            listener.getValue().accept(unauthorized);
            listener.getValue().accept(unauthorized);
            listener.getValue().accept(response("https://www.vinted.pl/member/login/email?token=secret-value", 200, true));
            assertEquals(2, appender.list.size());
            assertTrue(appender.list.get(0).getFormattedMessage().contains("status=401"));
            assertTrue(appender.list.get(1).getFormattedMessage().contains("authDocument=true"));
            for (int i = 0; i < 30; i++) {
                listener.getValue().accept(response("https://www.vinted.pl/api/v2/test" + i, 503, false));
            }
            assertEquals(20, appender.list.size());
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("secret-value"));
                assertFalse(event.getFormattedMessage().contains("?token"));
            }
            verify(unauthorized, never()).body();
            verify(unauthorized, never()).allHeaders();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void capturesFunctionalNetworkAbortsWithoutLeakingFailureTextOrAdNoise() {
        BrowserContext context = mock(BrowserContext.class);
        VintedSessionDiagnostics.install(context, 3L);
        ArgumentCaptor<Consumer<Request>> listener = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(context).onRequestFailed(listener.capture());
        Logger logger = (Logger) LoggerFactory.getLogger(VintedSessionDiagnostics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            listener.getValue().accept(failedRequest("https://ads.example/script", "fetch", "net::ERR_ABORTED"));
            listener.getValue().accept(failedRequest("https://www.vinted.pl/image.jpg", "image", "net::ERR_ABORTED"));
            Request abort = failedRequest("https://www.vinted.pl/api/v2/oauth/token?secret=hidden", "fetch",
                    "net::ERR_ABORTED https://user:password@example.com/?secret=hidden");
            listener.getValue().accept(abort);
            listener.getValue().accept(abort);
            listener.getValue().accept(failedRequest("https://www.vinted.pl/api/v2/conversations/123", "xhr", "net::ERR_CONNECTION_RESET"));
            assertEquals(2, appender.list.size());
            assertTrue(appender.list.get(0).getFormattedMessage().contains("failure=net::ERR_ABORTED"));
            assertTrue(appender.list.get(1).getFormattedMessage().contains("failure=net::ERR_CONNECTION_RESET"));
            for (ILoggingEvent event : appender.list) {
                String text = event.getFormattedMessage();
                assertFalse(text.contains("hidden"));
                assertFalse(text.contains("password"));
                assertFalse(text.contains("?secret"));
            }
            verify(abort, never()).postData();
            verify(abort, never()).allHeaders();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private Request failedRequest(String url, String type, String failure) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        when(request.resourceType()).thenReturn(type);
        when(request.failure()).thenReturn(failure);
        return request;
    }

    private Response response(String url, int status, boolean navigation) {
        Response response = mock(Response.class);
        Request request = mock(Request.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        when(response.request()).thenReturn(request);
        when(request.isNavigationRequest()).thenReturn(navigation);
        return response;
    }
}
