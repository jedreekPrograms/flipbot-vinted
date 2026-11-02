package pl.flipbot.playwright.worker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;

import java.util.Set;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class PreviewHandoverWaitLogTest {
    @Test
    public void requestedLiveStartsOnTheFirstSyncAfterWorkerReleasesOwnership() throws Exception {
        SessionPreviewManager manager = new SessionPreviewManager(
                WorkerRuntimeConfig.fromEnvironment(), mock(RuntimeTelemetryReporter.class));
        ExecutorService executor = mock(ExecutorService.class);
        Future<?> future = mock(Future.class);
        doReturn(future).when(executor).submit(any(Runnable.class));
        when(executor.awaitTermination(anyLong(), any())).thenReturn(true);
        Field field = SessionPreviewManager.class.getDeclaredField("executor");
        field.setAccessible(true);
        ((ExecutorService) field.get(manager)).shutdown();
        field.set(manager, executor);
        BotRunScheduler scheduler = mock(BotRunScheduler.class);
        AtomicBoolean working = new AtomicBoolean(true);
        when(scheduler.isWorking(8L)).thenAnswer(call -> working.get());
        try {
            manager.startRequestedWhenSafe(Set.of(8L), scheduler);
            verify(executor, never()).submit(any(Runnable.class));
            assertEquals(Set.of(), manager.activeBotIds());
            working.set(false);
            manager.startRequestedWhenSafe(Set.of(8L), scheduler);
            manager.startRequestedWhenSafe(Set.of(8L), scheduler);
            verify(executor).submit(any(Runnable.class));
            assertEquals(Set.of(8L), manager.activeBotIds());
        } finally {
            manager.close();
        }
    }

    @Test
    public void aPendingRequestLogsOnceAndCanBeRequestedAgainAfterHiding() {
        Logger logger = (Logger) LoggerFactory.getLogger(SessionPreviewManager.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        SessionPreviewManager manager = new SessionPreviewManager(
                WorkerRuntimeConfig.fromEnvironment(), mock(RuntimeTelemetryReporter.class));
        BotRunScheduler scheduler = mock(BotRunScheduler.class);
        when(scheduler.isWorking(8L)).thenReturn(true);
        try {
            for (int i = 0; i < 30; i++) {
                manager.startRequestedWhenSafe(Set.of(8L), scheduler);
            }
            assertEquals(1, waitingMessages(logs));
            assertEquals(Set.of(), manager.activeBotIds());
            manager.stopUnrequested(Set.of());
            manager.startRequestedWhenSafe(Set.of(8L), scheduler);
            assertEquals(2, waitingMessages(logs));
        } finally {
            manager.close();
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private long waitingMessages(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("waiting for the current job to release ownership")).count();
    }
}
