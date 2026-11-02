package pl.flipbot.playwright.util;

public final class FailureMessages {
    private FailureMessages() {}

    public static String withType(Throwable exception) {
        if (exception == null) return "unknown error";
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + summary(exception);
    }

    public static String summary(Throwable exception) {
        if (exception == null) return "Unknown error";
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        int end = message.indexOf('\n');
        return (end >= 0 ? message.substring(0, end) : message).trim();
    }
}
