package pl.flipbot.playwright.worker;

import com.microsoft.playwright.Page;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryStateResponse;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.session.VintedSessionPersistenceGuard;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class LiveSessionRecoveryTest {
    @Test public void idleHealthChecksAreBoundedWithoutDelayingForcedPostJobInspection() {
        BotContext context = mock(BotContext.class);
        when(context.getPage()).thenReturn(mock(Page.class));
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1);
        try (MockedConstruction<VintedSessionPersistenceGuard> guards = mockConstruction(
                VintedSessionPersistenceGuard.class, (guard, construction) -> when(guard.check(any()))
                        .thenReturn(new VintedSessionPersistenceGuard.Check(true, "fixture")));
             MockedConstruction<HumanVerificationHandler> verification = mockConstruction(HumanVerificationHandler.class)) {
            LiveSessionRecovery recovery = new LiveSessionRecovery(mock(RuntimeTelemetryReporter.class), mock(BotRunScheduler.class), now::get);
            for (int tick = 0; tick < 100; tick++) recovery.observeWhenDue(context);
            verify(guards.constructed().getFirst(), times(1)).check(context);
            now.addAndGet(750_000_000L);
            recovery.observeWhenDue(context);
            verify(guards.constructed().getFirst(), times(2)).check(context);
            recovery.afterScheduledJob(context);
            verify(guards.constructed().getFirst(), times(3)).check(context);
        }
    }
    @Test public void unsafeStorageSnapshotCannotReleaseWaitingJobs() {
        RuntimeTelemetryReporter telemetry = mock(RuntimeTelemetryReporter.class);
        BotRunScheduler scheduler = mock(BotRunScheduler.class);
        BotContext context = mock(BotContext.class);
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(3L);
        when(context.getBot()).thenReturn(bot);
        when(context.getPage()).thenReturn(mock(Page.class));
        AtomicBoolean healthy = new AtomicBoolean(false);
        try (MockedConstruction<VintedSessionPersistenceGuard> guards = mockConstruction(
                    VintedSessionPersistenceGuard.class, (mock, construction) ->
                            when(mock.check(any())).thenAnswer(call ->
                                    new VintedSessionPersistenceGuard.Check(healthy.get(), "fixture")));
             MockedConstruction<HumanVerificationHandler> verification = mockConstruction(HumanVerificationHandler.class)) {
            LiveSessionRecovery recovery = new LiveSessionRecovery(telemetry, scheduler);
            recovery.afterScheduledJob(context);
            assertTrue(recovery.awaitingManualRecovery());
            healthy.set(true);
            doThrow(new IllegalStateException("Unsafe session snapshot")).doNothing().when(context).saveSession();
            recovery.observe(context);
            assertTrue(recovery.awaitingManualRecovery());
            verify(scheduler, never()).resumeAfterSessionRecovery(3L);
            verify(telemetry, never()).sessionRecovered(3L);
            recovery.observe(context);
            assertFalse(recovery.awaitingManualRecovery());
            verify(scheduler).resumeAfterSessionRecovery(3L);
            verify(telemetry).sessionRecovered(3L);
            verify(context, never()).close();
        }
    }

    @Test public void manuallyRequestedBlockedPreviewGetsExactlyOneProbe() {
        RuntimeTelemetryReporter telemetry = mock(RuntimeTelemetryReporter.class);
        when(telemetry.currentState(3L)).thenReturn(new RuntimeTelemetryStateResponse(null, 1, "blocked"));
        BotRunScheduler scheduler = mock(BotRunScheduler.class);
        LiveSessionRecovery recovery = new LiveSessionRecovery(telemetry, scheduler);
        AtomicBoolean used = new AtomicBoolean();
        recovery.armManualInterventionProbe(3L, used);
        assertTrue(recovery.consumeManualInterventionProbe());
        assertFalse(recovery.consumeManualInterventionProbe());
        recovery.armManualInterventionProbe(3L, used);
        assertFalse(recovery.consumeManualInterventionProbe());
        verify(scheduler).wakeForManualSessionIntervention(3L);
        verify(telemetry, never()).sessionRecovered(3L);
    }
}
