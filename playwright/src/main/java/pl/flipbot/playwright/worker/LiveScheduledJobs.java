package pl.flipbot.playwright.worker;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotDetailsDto;

/** Executes already-claimed LIVE jobs and releases their scheduler claim on every outcome. */
@Slf4j
final class LiveScheduledJobs {
    private static final int LIVE_PREVIEW_TELEMETRY_SLOT = 0;
    private final WorkerRuntimeConfig config;
    private final RuntimeTelemetryReporter telemetryReporter;
    private final BotRunScheduler scheduler;
    private final SessionCooldown cooldown;

    LiveScheduledJobs(WorkerRuntimeConfig config, RuntimeTelemetryReporter telemetryReporter,
                      BotRunScheduler scheduler) {
        this.config = config;
        this.telemetryReporter = telemetryReporter;
        this.scheduler = scheduler;
        this.cooldown = new SessionCooldown(telemetryReporter);
    }

    void execute(
            Long botId,
            ScheduledBotTask task,
            BotDetailsDto bot,
            BrowserManager browserManager,
            boolean manualInterventionProbe,
            BotContext liveContext
    ) {
        ScheduledJobType jobType = task.jobType();

        Long persistedBlockDelayMillis =
                cooldown.remainingDelay(botId);

        if (!manualInterventionProbe
                && persistedBlockDelayMillis != null
                && persistedBlockDelayMillis > 0L) {
            log.warn(
                    "[SESSION PREVIEW] Bot {} remains under persisted Vinted session cooldown for about {} minute(s). The visible window stays available for manual recovery, but scheduled marketplace actions will not run early.",
                    botId,
                    Math.max(
                            1L,
                            TimeUnit.MILLISECONDS.toMinutes(
                                    persistedBlockDelayMillis
                            )
                    )
            );

            scheduler.completeRun(
                    botId,
                    jobType,
                    persistedBlockDelayMillis,
                    true,
                    false
            );
            return;
        }

        if (manualInterventionProbe
                && persistedBlockDelayMillis != null
                && persistedBlockDelayMillis > 0L) {
            log.warn(
                    "[SESSION PREVIEW] Bot {} is still under a persisted SESSION_BLOCKED cooldown, but the user explicitly opened LIVE preview. Bypassing that cooldown for this ONE visible recovery attempt so CAPTCHA/authentication can be completed interactively.",
                    botId
            );
        }

        long nextDelayMillis = TimeUnit.SECONDS.toMillis(
                config.normalDelaySeconds(jobType)
        );
        boolean delayAllJobs = false;
        boolean reportQueuedAfterRun = true;
        long startedAtNanos = System.nanoTime();

        telemetryReporter.runStarted(
                botId,
                LIVE_PREVIEW_TELEMETRY_SLOT
        );

        try {
            log.info(
                    "[SESSION PREVIEW] Running normal {} for bot {} in LIVE headed browser.",
                    jobType,
                    botId
            );

            ScheduledBotRunExecutor executor = new ScheduledBotRunExecutor(
                    bot,
                    browserManager
            );
            executor.executeJob(jobType, liveContext);

            long durationMs = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
            telemetryReporter.runSucceeded(botId, durationMs);

            log.info(
                    "[SESSION PREVIEW] Bot {} completed normal {} in {} ms while live preview remained enabled.",
                    botId,
                    jobType,
                    durationMs
            );

        } catch (Exception exception) {
            reportQueuedAfterRun = false;
            JobFailurePolicy.RetryPlan retry = new JobFailurePolicy(config, telemetryReporter)
                    .afterFailure(botId, jobType, Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L), exception);
            nextDelayMillis = retry.delayMillis();
            delayAllJobs = retry.delayAllJobs();
        } finally {
            scheduler.completeRun(
                    botId,
                    jobType,
                    nextDelayMillis,
                    delayAllJobs,
                    reportQueuedAfterRun
            );
        }
    }
}
