package pl.flipbot.playwright.worker;

import com.microsoft.playwright.Page;
import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.api.BotApiClient;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.session.VintedSessionPersistenceGuard;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SessionPreviewContextLifecycleTest {

    @Test
    public void consecutiveProductionJobsUseOneVisibleContext() throws Exception {
        runScenario(false, false);
    }

    @Test
    public void ordinaryJobFailureDoesNotReplaceTheVisibleContext() throws Exception {
        runScenario(true, false);
    }

    @Test
    public void lostAuthenticationLeavesPageOpenForManualRecoveryWithoutAnotherJob() throws Exception {
        runScenario(true, true);
    }

    private void runScenario(boolean failFirstJob, boolean loseAuthentication) throws Exception {
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(3L);
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://www.vinted.pl/");
        AtomicBoolean healthy = new AtomicBoolean(true);
        AtomicInteger claimed = new AtomicInteger();
        BotRunScheduler scheduler = mock(BotRunScheduler.class);
        when(scheduler.pollPreviewNext(eq(3L), anyLong())).thenAnswer(call -> {
            int index = claimed.getAndIncrement();
            if (index >= 2) {
                throw new InterruptedException("End of test scenario");
            }
            return ScheduledBotTask.afterDelay(3L,
                    index == 0 ? ScheduledJobType.NEGOTIATION_CHECK : ScheduledJobType.CATALOG_SCAN, 0);
        });

        Class<?> handleType = Class.forName(SessionPreviewManager.class.getName() + "$PreviewHandle");
        Constructor<?> constructor = handleType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object handle = constructor.newInstance();
        Field stopField = handleType.getDeclaredField("stopRequested");
        stopField.setAccessible(true);
        AtomicBoolean stop = (AtomicBoolean) stopField.get(handle);
        doAnswer(call -> {
            if (loseAuthentication && !healthy.get()) {
                stop.set(true);
            }
            return null;
        }).when(page).waitForTimeout(anyDouble());

        try (MockedConstruction<BotApiClient> api = mockConstruction(BotApiClient.class,
                     (mock, construction) -> when(mock.getBot(3L)).thenReturn(bot));
             MockedConstruction<BrowserManager> browsers = mockConstruction(BrowserManager.class);
             MockedConstruction<BotContext> contexts = mockConstruction(BotContext.class,
                     (mock, construction) -> {
                         when(mock.getBot()).thenReturn(bot);
                         when(mock.getPage()).thenReturn(page);
                     });
             MockedConstruction<VintedSessionPersistenceGuard> guards =
                     mockConstruction(VintedSessionPersistenceGuard.class,
                             (mock, construction) -> when(mock.check(any())).thenAnswer(call ->
                                     new VintedSessionPersistenceGuard.Check(healthy.get(), "fixture")));
             MockedConstruction<HumanVerificationHandler> verification =
                     mockConstruction(HumanVerificationHandler.class);
             MockedConstruction<ScheduledBotRunExecutor> jobs =
                     mockConstruction(ScheduledBotRunExecutor.class, (mock, construction) -> {
                         if (failFirstJob && construction.getCount() == 1) {
                             doAnswer(call -> {
                                 if (loseAuthentication) {
                                     healthy.set(false);
                                 }
                                 if (loseAuthentication) {
                                     throw new VintedAuthenticationRequiredException("Vinted redirected to login");
                                 }
                                 throw new IllegalStateException("Simulated job failure");
                             }).when(mock).executeJob(any(ScheduledJobType.class), any(BotContext.class));
                         }
                     });
             SessionPreviewManager manager = new SessionPreviewManager(
                     mock(WorkerRuntimeConfig.class), mock(RuntimeTelemetryReporter.class))) {
            Method run = SessionPreviewManager.class.getDeclaredMethod(
                    "runLivePreview", Long.class, handleType, BotRunScheduler.class);
            run.setAccessible(true);
            try {
                run.invoke(manager, 3L, handle, scheduler);
            } finally {
                Thread.interrupted();
            }

            assertEquals(1, contexts.constructed().size());
            BotContext owner = contexts.constructed().get(0);
            assertEquals(loseAuthentication ? 1 : 2, jobs.constructed().size());
            verify(jobs.constructed().get(0)).executeJob(ScheduledJobType.NEGOTIATION_CHECK, owner);
            if (!loseAuthentication) {
                verify(jobs.constructed().get(1)).executeJob(ScheduledJobType.CATALOG_SCAN, owner);
            }
            verify(owner, times(1)).close();
        }
    }
}
