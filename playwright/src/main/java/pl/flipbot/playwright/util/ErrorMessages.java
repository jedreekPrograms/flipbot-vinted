package pl.flipbot.playwright.util;

/** Shared formatting only; callers keep their existing fallback text and limits. */
public final class ErrorMessages {
    private ErrorMessages() {}

    public static String fullMessage(Throwable error, String unknownMessage) {
        if (error == null) return unknownMessage;
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public static String firstLine(Throwable error, String unknownMessage) {
        String message = fullMessage(error, unknownMessage);
        return message.lines().findFirst().orElse(message).trim();
    }

    /** Legacy conversation formatting keeps the whole message when LF is its first character. */
    public static String conversationFailure(Throwable error) {
        String message = fullMessage(error, "Unknown error");
        int firstLineEnd = message.indexOf('\n');
        return firstLineEnd > 0 ? message.substring(0, firstLineEnd).trim() : message.trim();
    }
}
