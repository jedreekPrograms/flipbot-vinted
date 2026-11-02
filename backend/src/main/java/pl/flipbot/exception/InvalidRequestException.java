package pl.flipbot.exception;

/** An existing domain validation rejected input supplied by the API caller. */
public class InvalidRequestException extends IllegalArgumentException {
    public InvalidRequestException(String message) { super(message); }
}
