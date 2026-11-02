package pl.flipbot.playwright.api;

import org.junit.Test;
import pl.flipbot.playwright.exception.ApiException;

import java.net.http.HttpResponse;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BotApiClientTest {

    @Test
    public void diagnosticBodyIsCollapsedToOneLine() {
        assertEquals(
                "{ \"message\": \"temporary backend error\" }",
                BotApiClient.summarizeResponseBody(
                        "{\n  \"message\":   \"temporary backend error\"\n}"
                )
        );
    }

    @Test
    public void diagnosticBodyIsBounded() {
        String summary = BotApiClient.summarizeResponseBody("x".repeat(1_000));

        assertTrue(summary.endsWith("..."));
        assertTrue(summary.length() < 300);
    }

    @Test
    public void emptyDiagnosticBodyIsExplicit() {
        assertEquals("<empty>", BotApiClient.summarizeResponseBody("   "));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void backendErrorEnvelopeIsNotParsedAsABotOrPrintedWithPrivateData() {
        for (int status : new int[]{404, 500}) {
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            when(response.body()).thenReturn("{\"message\":\"private-secret\"}");
            BotApiClient client = clientReturning(response);
            ApiException error = assertThrows(ApiException.class, () -> client.getBot(12L));
            assertTrue(error.getMessage().contains("/api/bots/12/playwright"));
            assertTrue(error.getMessage().contains("HTTP " + status));
            assertFalse(error.getMessage().contains("private-secret"));
            assertNull(error.getCause());
            verify(response, never()).body();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void successfulBotDetailsStillUseTheExpectedEndpointAndDto() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"id\":12,\"name\":\"fixture\",\"additionalTargets\":[]}");
        assertEquals(Long.valueOf(12), clientReturning(response).getBot(12L).getId());
    }

    private BotApiClient clientReturning(HttpResponse<String> response) {
        return new BotApiClient() {
            @Override
            protected HttpResponse<String> get(String path) {
                assertEquals("/api/bots/12/playwright", path);
                return response;
            }
        };
    }
}
