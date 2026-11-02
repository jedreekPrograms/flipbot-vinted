package pl.flipbot.playwright.worker;

import org.junit.Test;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryStateResponse;
import java.time.Instant;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SessionCooldownTest {
    @Test public void persistedBlockUsesItsDeadlineAndExpiredBlockHasZeroDelay() {
        RuntimeTelemetryReporter telemetry = mock(RuntimeTelemetryReporter.class);
        when(telemetry.currentState(3L)).thenReturn(new RuntimeTelemetryStateResponse(
                Instant.ofEpochMilli(2000L).toString(), 1, "blocked"));
        assertEquals(Long.valueOf(1000L), new SessionCooldown(telemetry, () -> 1000L).remainingDelay(3L));
        assertEquals(Long.valueOf(0L), new SessionCooldown(telemetry, () -> 3000L).remainingDelay(3L));
    }

    @Test public void unrelatedRunDelayDoesNotBecomeASessionBlock() {
        RuntimeTelemetryReporter telemetry = mock(RuntimeTelemetryReporter.class);
        when(telemetry.currentState(3L)).thenReturn(new RuntimeTelemetryStateResponse(
                Instant.ofEpochMilli(2000L).toString(), 0, null));
        SessionCooldown cooldown = new SessionCooldown(telemetry, () -> 1000L);
        assertFalse(cooldown.hasBlock(3L));
        assertNull(cooldown.remainingDelay(3L));
        when(telemetry.currentState(3L)).thenReturn(new RuntimeTelemetryStateResponse(null, 1, "blocked"));
        assertTrue(cooldown.hasBlock(3L));
        assertNull(cooldown.remainingDelay(3L));
    }

    @Test public void malformedOrUnavailableTelemetryLeavesLocalSchedulingAuthoritative() {
        RuntimeTelemetryReporter telemetry = mock(RuntimeTelemetryReporter.class);
        SessionCooldown cooldown = new SessionCooldown(telemetry, () -> 1000L);
        when(telemetry.currentState(3L)).thenReturn(new RuntimeTelemetryStateResponse("invalid", 1, "blocked"));
        assertNull(cooldown.remainingDelay(3L));
        when(telemetry.currentState(3L)).thenThrow(new IllegalStateException("offline"));
        assertNull(cooldown.remainingDelay(3L));
        assertFalse(cooldown.hasBlock(3L));
    }
}
