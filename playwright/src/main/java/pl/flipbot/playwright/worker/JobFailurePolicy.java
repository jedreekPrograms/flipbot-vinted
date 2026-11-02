package pl.flipbot.playwright.worker;

import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;
import pl.flipbot.playwright.target.VintedRateLimitException;
import pl.flipbot.playwright.target.VintedSessionBlockedException;
import java.util.concurrent.TimeUnit;

/** Retry timing and scope shared by hidden workers and the visible LIVE owner. */
@Slf4j
final class JobFailurePolicy {
    private static final long BLOCK_FALLBACK_MS = TimeUnit.MINUTES.toMillis(15);
    private final WorkerRuntimeConfig config;
    private final RuntimeTelemetryReporter telemetry;

    JobFailurePolicy(WorkerRuntimeConfig config, RuntimeTelemetryReporter telemetry) {
        this.config = config;
        this.telemetry = telemetry;
    }

    RetryPlan afterFailure(Long botId, ScheduledJobType job, long durationMs, Exception failure) {
        String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
        if (failure instanceof VintedSessionBlockedException) {
            long delay = BLOCK_FALLBACK_MS;
            try {
                var cooldown = telemetry.sessionBlocked(botId, durationMs, reason);
                delay = Math.max(0, cooldown.nextRunAtEpochMs() - System.currentTimeMillis());
            } catch (Exception unavailableTelemetry) {
                log.warn("[JOB RETRY] Cannot persist/read session cooldown for bot {}; using 15 minutes.", botId);
            }
            log.warn("[JOB RETRY] Bot {} / {} requires session recovery. All jobs back off for {}ms. reason={}",
                    botId, job, delay, reason);
            return new RetryPlan(delay, true);
        }

        boolean rateLimited = failure instanceof VintedRateLimitException;
        boolean authenticationLost = failure instanceof VintedAuthenticationRequiredException;
        long seconds = rateLimited ? config.rateLimitDelaySeconds() : config.failureDelaySeconds();
        RetryPlan plan = new RetryPlan(TimeUnit.SECONDS.toMillis(seconds), rateLimited || authenticationLost);
        try {
            long nextRun = System.currentTimeMillis() + plan.delayMillis();
            if (rateLimited) telemetry.rateLimited(botId, durationMs, nextRun, reason);
            else telemetry.runFailed(botId, durationMs, nextRun, reason);
        } catch (Exception unavailableTelemetry) {
            // Ownership must still be released with the correct retry scope.
            log.warn("[JOB RETRY] Failure telemetry unavailable for bot {}; applying the local retry plan.", botId);
        }
        log.warn("[JOB RETRY] Bot {} / {} retries in {}s; delayAllJobs={}. reason={}",
                botId, job, seconds, plan.delayAllJobs(), reason);
        log.debug("[JOB RETRY] Job failure for bot {} / {}", botId, job, failure);
        return plan;
    }

    record RetryPlan(long delayMillis, boolean delayAllJobs) { }
}
