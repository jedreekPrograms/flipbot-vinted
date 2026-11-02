package pl.flipbot.playwright.worker;

import org.junit.Test;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;
import pl.flipbot.playwright.target.VintedRateLimitException;
import pl.flipbot.playwright.target.VintedSessionBlockedException;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class JobFailurePolicyTest {
    private final RuntimeTelemetryReporter telemetry = mock(RuntimeTelemetryReporter.class);
    private final WorkerRuntimeConfig config = new WorkerRuntimeConfig(2, 1, 120, 900, 7, 31, 180, 30, true);
    private final JobFailurePolicy policy = new JobFailurePolicy(config, telemetry);

    @Test public void authenticationLossDelaysEveryJob() {
        var retry = policy.afterFailure(8L, ScheduledJobType.NEGOTIATION_CHECK, 10,
                new VintedAuthenticationRequiredException("lost"));
        assertEquals(7000, retry.delayMillis());
        assertTrue(retry.delayAllJobs());
        verify(telemetry).runFailed(eq(8L), eq(10L), anyLong(), eq("lost"));
    }

    @Test public void rateLimitUsesItsOwnDelayForEveryJob() {
        var retry = policy.afterFailure(8L, ScheduledJobType.CATALOG_SCAN, 10,
                new VintedRateLimitException("rate limited"));
        assertEquals(31000, retry.delayMillis());
        assertTrue(retry.delayAllJobs());
        verify(telemetry).rateLimited(eq(8L), eq(10L), anyLong(), eq("rate limited"));
    }

    @Test public void ordinaryFailureDoesNotPostponeOtherJobTypes() {
        var retry = policy.afterFailure(8L, ScheduledJobType.CATALOG_SCAN, 10,
                new IllegalArgumentException("bad candidate"));
        assertEquals(7000, retry.delayMillis());
        assertFalse(retry.delayAllJobs());
    }

    @Test public void persistedSessionCooldownRemainsAuthoritative() {
        when(telemetry.sessionBlocked(eq(8L), anyLong(), anyString()))
                .thenReturn(new RuntimeTelemetryReporter.SessionBlockCooldown(
                        System.currentTimeMillis() + 50_000, 3, "2026-10-15T12:00:00Z"));
        var retry = policy.afterFailure(8L, ScheduledJobType.CATALOG_SCAN, 10,
                new VintedSessionBlockedException("verification"));
        assertTrue(retry.delayMillis() > 40_000 && retry.delayMillis() <= 50_000);
        assertTrue(retry.delayAllJobs());
    }

    @Test public void unavailableCooldownTelemetryUsesTheSameProtectiveFallback() {
        when(telemetry.sessionBlocked(eq(8L), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("offline"));
        var retry = policy.afterFailure(8L, ScheduledJobType.CATALOG_SCAN, 10,
                new VintedSessionBlockedException("verification"));
        assertEquals(900_000, retry.delayMillis());
        assertTrue(retry.delayAllJobs());
    }

    @Test public void telemetryFailureDoesNotLoseTheAuthenticationRetryScope() {
        doThrow(new IllegalStateException("offline")).when(telemetry)
                .runFailed(anyLong(), anyLong(), anyLong(), anyString());
        var retry = policy.afterFailure(8L, ScheduledJobType.NEGOTIATION_CHECK, 10,
                new VintedAuthenticationRequiredException("lost"));
        assertEquals(7000, retry.delayMillis());
        assertTrue(retry.delayAllJobs());
    }
}
