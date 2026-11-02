package pl.flipbot.playwright.worker;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.BotApiClient;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.util.FailureMessages;

/**
 * Gives one bot an exclusive, visible Playwright owner while session preview is
 * enabled.
 *
 * <p>The bot is removed from generic worker claiming, but it is NOT paused as
 * business logic: this preview owner claims the same scheduler jobs and runs
 * the normal {@link ScheduledBotRunExecutor} in headed Chromium. The user can
 * therefore watch catalog scans and negotiation checks exactly as they happen.</p>
 *
 * <p>Between scheduled jobs a normal persistent BotContext stays visible. If
 * Vinted presents CAPTCHA/authentication trouble, the user may solve it in that
 * window. The context is persisted only after a strong authenticated signal is
 * visible, using the normal SessionManager staging/validation/backup path. No
 * second browser context for this bot runs concurrently.</p>
 */
@Slf4j
final class SessionPreviewManager implements AutoCloseable {

    /*
     * Keep this short in headed LIVE mode. Synchronous Playwright dispatches
     * browser events while Java calls into Playwright; a long plain-Java poll
     * gap makes an already-created ad popup visibly linger before onPopup /
     * onPage callbacks get a chance to close it.
     */
    private static final long PREVIEW_POLL_MS = 50L;
    private static final double PREVIEW_EVENT_PUMP_MS = 10.0;
    private static final long STOP_TIMEOUT_SECONDS = 8L;

    private final WorkerRuntimeConfig config;
    private final RuntimeTelemetryReporter telemetryReporter;
    private final BotApiClient botApiClient = new BotApiClient();

    private final ExecutorService executor =
            Executors.newCachedThreadPool(
                    namedThreadFactory("flipbot-live-preview-")
            );

    private final Map<Long, PreviewHandle> handles =
            new ConcurrentHashMap<>();
    private final Set<Long> waitingForWorkers = ConcurrentHashMap.newKeySet();

    SessionPreviewManager(
            WorkerRuntimeConfig config,
            RuntimeTelemetryReporter telemetryReporter
    ) {
        this.config = config;
        this.telemetryReporter = telemetryReporter;
    }

    void stopUnrequested(Set<Long> requestedBotIds) {
        Set<Long> requested = normalize(requestedBotIds);
        waitingForWorkers.retainAll(requested);

        for (Map.Entry<Long, PreviewHandle> entry
                : Set.copyOf(handles.entrySet())) {

            Long botId = entry.getKey();
            if (requested.contains(botId)) {
                continue;
            }

            stopAndAwait(botId, entry.getValue());
        }
    }

    void startRequestedWhenSafe(
            Set<Long> requestedBotIds,
            BotRunScheduler scheduler
    ) {
        Set<Long> requested = normalize(requestedBotIds);
        waitingForWorkers.retainAll(requested);

        for (Long botId : requested) {
            if (handles.containsKey(botId)) {
                continue;
            }

            if (scheduler.isWorking(botId)) {
                if (waitingForWorkers.add(botId)) {
                    log.info(
                            "[SESSION PREVIEW] Bot {} live preview is waiting for the current job to release ownership. Headless human verification yields immediately; LIVE starts after safe job completion.",
                            botId
                    );
                }
                continue;
            }

            waitingForWorkers.remove(botId);
            start(botId, scheduler);
        }
    }

    Set<Long> activeBotIds() {
        Set<Long> active = new HashSet<>();

        handles.forEach(
                (botId, handle) -> {
                    Future<?> future = handle.future;
                    if (future != null && !future.isDone()) {
                        active.add(botId);
                    }
                }
        );

        return Set.copyOf(active);
    }

    private void start(
            Long botId,
            BotRunScheduler scheduler
    ) {
        PreviewHandle handle = new PreviewHandle();

        if (handles.putIfAbsent(botId, handle) != null) {
            return;
        }

        try {
            handle.future = executor.submit(
                    () -> runLivePreview(botId, handle, scheduler)
            );

            log.info(
                    "[SESSION PREVIEW] Starting LIVE headed owner for bot {}. The bot keeps its normal scheduler jobs while this window is visible.",
                    botId
            );
        } catch (RuntimeException exception) {
            handles.remove(botId, handle);
            throw exception;
        }
    }

    private void runLivePreview(
            Long botId,
            PreviewHandle handle,
            BotRunScheduler scheduler
    ) {
        BotContext idleContext = null;

        try {
            BotDetailsDto bot = botApiClient.getBot(botId);

            try (BrowserManager browserManager = new BrowserManager(false)) {
                LiveSessionRecovery recovery = new LiveSessionRecovery(telemetryReporter, scheduler);
                LiveScheduledJobs jobs = new LiveScheduledJobs(config, telemetryReporter, scheduler);

                idleContext = recovery.open(bot, browserManager);
                recovery.armManualInterventionProbe(botId, handle.manualInterventionProbeUsed);

                while (!handle.stopRequested.get()) {
                    if (idleContext.getPage().isClosed()) {
                        break;
                    }

                    recovery.observeWhenDue(idleContext);
                    if (recovery.awaitingManualRecovery()) {
                        idleContext.getPage().waitForTimeout(250);
                        continue;
                    }

                    ScheduledBotTask task = scheduler.pollPreviewNext(
                            botId,
                            PREVIEW_POLL_MS
                    );

                    if (task == null) {
                        pumpVisibleBrowserEvents(idleContext);

                        recovery.observeWhenDue(idleContext);
                        continue;
                    }

                    boolean manualInterventionProbe =
                            recovery.consumeManualInterventionProbe();

                    jobs.execute(
                            botId,
                            task,
                            bot,
                            browserManager,
                            manualInterventionProbe,
                            idleContext
                    );

                    recovery.afterScheduledJob(idleContext);
                }

                recovery.persistHealthyState(
                        idleContext,
                        "live preview close"
                );
                closeContext(
                        idleContext,
                        botId,
                        "live preview close"
                );
                idleContext = null;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.info(
                    "[SESSION PREVIEW] Live preview owner for bot {} was interrupted.",
                    botId
            );
        } catch (RuntimeException exception) {
            log.error(
                    "[SESSION PREVIEW] Live preview owner for bot {} failed. Generic scheduler ownership remains blocked while preview is requested; the next sync may restart the live preview safely. reason={}",
                    botId,
                    FailureMessages.withType(exception),
                    exception
            );
        } finally {
            closeContext(idleContext, botId, "live preview owner finished");
        }
    }

    private void pumpVisibleBrowserEvents(BotContext context) {
        if (context == null
                || context.getPage() == null
                || context.getPage().isClosed()) {
            return;
        }

        try {
            /*
             * Unlike Thread.sleep(), Page.waitForTimeout() keeps Playwright's
             * synchronous event dispatcher alive. This lets popup/page events
             * be delivered and closed while LIVE preview is otherwise idle.
             */
            context.getPage().waitForTimeout(PREVIEW_EVENT_PUMP_MS);
        } catch (RuntimeException exception) {
            log.trace(
                    "[SESSION PREVIEW] Could not pump headed browser events while idle.",
                    exception
            );
        }
    }

    private void closeContext(
            BotContext context,
            Long botId,
            String reason
    ) {
        if (context == null) {
            return;
        }

        try {
            context.close();
        } catch (Exception exception) {
            log.warn(
                    "[SESSION PREVIEW] Could not close visible context for bot {} cleanly. reason={}, error={}",
                    botId,
                    reason,
                    FailureMessages.withType(exception)
            );
        }
    }

    private void stopAndAwait(
            Long botId,
            PreviewHandle handle
    ) {
        handle.stopRequested.set(true);

        Future<?> future = handle.future;

        if (future == null) {
            handles.remove(botId, handle);
            return;
        }

        try {
            future.get(
                    STOP_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            handles.remove(botId, handle);

            log.info(
                    "[SESSION PREVIEW] LIVE preview for bot {} is fully closed. Generic worker ownership may resume.",
                    botId
            );
        } catch (TimeoutException exception) {
            log.error(
                    "[SESSION PREVIEW] Timed out waiting for bot {} live preview to close. Keeping this bot scheduler-owned by preview until its thread actually exits, preventing two browsers from racing the same session.",
                    botId
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            handles.remove(botId, handle);
            log.warn(
                    "[SESSION PREVIEW] Live preview thread for bot {} finished with an error while closing.",
                    botId,
                    exception
            );
        }
    }

    private Set<Long> normalize(Set<Long> botIds) {
        if (botIds == null || botIds.isEmpty()) {
            return Set.of();
        }

        Set<Long> normalized = new HashSet<>();

        botIds.stream()
                .filter(botId -> botId != null && botId > 0L)
                .forEach(normalized::add);

        return Set.copyOf(normalized);
    }

    @Override
    public void close() {
        Set<Long> botIds = Set.copyOf(handles.keySet());

        for (Long botId : botIds) {
            PreviewHandle handle = handles.get(botId);
            if (handle != null) {
                stopAndAwait(botId, handle);
            }
        }

        executor.shutdownNow();

        try {
            if (!executor.awaitTermination(
                    STOP_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                log.warn(
                        "[SESSION PREVIEW] Live preview executor did not terminate within {} seconds.",
                        STOP_TIMEOUT_SECONDS
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static java.util.concurrent.ThreadFactory namedThreadFactory(
            String prefix
    ) {
        AtomicInteger sequence = new AtomicInteger(1);

        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    prefix + sequence.getAndIncrement()
            );
            thread.setDaemon(false);
            return thread;
        };
    }

    private static final class PreviewHandle {
        private final AtomicBoolean stopRequested =
                new AtomicBoolean(false);
        private final AtomicBoolean manualInterventionProbeUsed =
                new AtomicBoolean(false);
        private volatile Future<?> future;
    }
}
