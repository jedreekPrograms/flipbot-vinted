package pl.flipbot.playwright.login;

final class LoginLimits {
    private LoginLimits() {}

    static final double AUTH_VIEW_TIMEOUT_MS = 20_000;

    static final double AUTH_ACTION_TIMEOUT_MS = 2_000;

    static final double AUTH_POLL_INTERVAL_MS = 200;

    static final double AUTH_SWITCH_RETRY_DELAY_MS = 600;

    static final double FORM_SETTLE_MS = 700;

    static final double CREDENTIAL_STABILITY_WAIT_MS = 650;

    static final double SUBMIT_TRANSITION_TIMEOUT_MS = 6_000;

    static final double POST_LOGIN_TIMEOUT_MS = 60_000;

    static final int RESTORED_SESSION_SETTLE_POLLS = 32;

    static final double RESTORED_SESSION_SETTLE_POLL_MS = 250;

    static final int MAX_REGISTER_SWITCH_ATTEMPTS = 6;

    static final int MAX_CREDENTIAL_FILL_ATTEMPTS = 4;

    static final int MAX_SUBMIT_ATTEMPTS = 3;
}
