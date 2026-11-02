package pl.flipbot.playwright.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ErrorMessagesTest {
    @Test public void callersKeepTheirOwnNullFallbackAndMissingMessageClassName() {
        for (String fallback : new String[]{"unknown error", "Unknown error", "Unknown post-submit failure"}) {
            assertEquals(fallback, ErrorMessages.firstLine(null, fallback));
            assertEquals(fallback, ErrorMessages.fullMessage(null, fallback));
        }
        for (String missing : new String[]{null, "", " \n\t"}) {
            assertEquals("IllegalStateException", ErrorMessages.firstLine(new IllegalStateException(missing), "unused"));
            assertEquals("IllegalStateException", ErrorMessages.conversationFailure(new IllegalStateException(missing)));
        }
    }

    @Test public void lineFormattingAndFullMessagesRetainWhitespaceAndLineBreakDifferences() {
        String[][] cases = {
                {"  first  ", "first", "first"},
                {" first \n second ", "first", "first"},
                {" first \r\n second ", "first", "first"},
                {" first \r second ", "first", "first \r second"},
                {"\n second \n third ", "", "second \n third"}
        };
        for (String[] fixture : cases) {
            Throwable error = new RuntimeException(fixture[0]);
            assertEquals(fixture[1], ErrorMessages.firstLine(error, "unused"));
            assertEquals(fixture[2], ErrorMessages.conversationFailure(error));
            assertEquals(fixture[0], ErrorMessages.fullMessage(error, "unused"));
        }
    }
}
