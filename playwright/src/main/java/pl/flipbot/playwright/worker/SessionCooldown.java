package pl.flipbot.playwright.worker;

import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryStateResponse;
import pl.flipbot.playwright.util.FailureMessages;
import java.time.Instant;
import java.util.function.LongSupplier;

@Slf4j
final class SessionCooldown {
    private final RuntimeTelemetryReporter telemetry;
    private final LongSupplier clock;

    SessionCooldown(RuntimeTelemetryReporter telemetry) { this(telemetry, System::currentTimeMillis); }

    SessionCooldown(RuntimeTelemetryReporter telemetry, LongSupplier clock) {
        this.telemetry = telemetry;
        this.clock = clock;
    }

    boolean hasBlock(Long botId) {
        RuntimeTelemetryStateResponse state = currentState(botId);
        return state != null && state.sessionBlockedSince() != null;
    }

    Long remainingDelay(Long botId) {
        RuntimeTelemetryStateResponse state = currentState(botId);
        if (state == null || state.sessionBlockedSince() == null || state.nextRunAt() == null) return null;
        try {
            return Math.max(0L, Instant.parse(state.nextRunAt()).toEpochMilli() - clock.getAsLong());
        } catch (RuntimeException exception) {
            unavailable(botId, exception);
            return null;
        }
    }

    private RuntimeTelemetryStateResponse currentState(Long botId) {
        try {
            return telemetry.currentState(botId);
        } catch (Exception exception) {
            unavailable(botId, exception);
            return null;
        }
    }

    private void unavailable(Long botId, Exception exception) {
        log.warn("[SESSION BLOCK] Could not read persisted cooldown for bot {}. Using the in-memory scheduler. reason={}",
                botId, FailureMessages.withType(exception));
    }
}
