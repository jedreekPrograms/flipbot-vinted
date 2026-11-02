package pl.flipbot.playwright.api;

import java.net.http.HttpResponse;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ApiResponseValidationTest {
    private final ApiClient client = new ApiClient() {};

    @Test public void everySuccessfulStatusIsAcceptedWithoutReadingItsBody() {
        for (int status : new int[]{200, 204, 299}) {
            HttpResponse<String> response = response(status);
            client.requireSuccessfulResponse(response, "read fixture");
            verify(response, never()).body();
        }
    }

    @Test public void failuresKeepTheExistingExceptionTypeStatusOperationAndBody() {
        for (int status : new int[]{199, 300, 403, 429, 500}) {
            HttpResponse<String> response = response(status);
            when(response.body()).thenReturn("fixture error body");
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client.requireSuccessfulResponse(response, "read fixture"));
            assertEquals("Backend could not read fixture. HTTP status: " + status
                    + ", response: fixture error body", failure.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        return response;
    }
}
