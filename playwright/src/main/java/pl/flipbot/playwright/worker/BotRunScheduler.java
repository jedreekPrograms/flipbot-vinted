package pl.flipbot.playwright.worker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;

/** Owns queue claims and LIVE handover. All per-bot state changes use this monitor. */
@Slf4j
public class BotRunScheduler {
    private final DelayQueue<ScheduledBotTask> queue = new DelayQueue<>();
    private final Map<Long, BotSchedule> schedules = new HashMap<>();
    // Paused means reserved for LIVE, whose owner may still claim the same due jobs.
    private final Set<Long> pausedBotIds = new HashSet<>();
    private final RuntimeTelemetryReporter telemetryReporter;
    private final CatalogConcurrencyConfig catalogConcurrencyConfig;

    public BotRunScheduler(RuntimeTelemetryReporter telemetryReporter) {
        this(telemetryReporter, CatalogConcurrencyConfig.fromEnvironment());
    }

    BotRunScheduler(RuntimeTelemetryReporter telemetryReporter,
                    CatalogConcurrencyConfig catalogConcurrencyConfig) {
        this.telemetryReporter = telemetryReporter;
        this.catalogConcurrencyConfig = catalogConcurrencyConfig;
        log.info("[SCHEDULER MEMORY] Max concurrent catalog scans={}. Deferred catalog retry={} ms.",
                catalogConcurrencyConfig.maxConcurrentCatalogScans(), catalogConcurrencyConfig.retryDelayMillis());
    }

    public synchronized void reconcileRunningBots(Map<Long, Boolean> runningBots) {
        Map<Long, Boolean> normalized = new HashMap<>();
        runningBots.forEach((id, active) -> {
            if (id != null && id > 0) normalized.put(id, Boolean.TRUE.equals(active));
        });
        Set<Long> removed = new HashSet<>(schedules.keySet());
        removed.removeAll(normalized.keySet());
        removed.forEach(this::disableBot);
        long now = System.currentTimeMillis();
        normalized.forEach((id, active) -> enableOrRefreshBot(id, active, now));
    }

    /** A timeout lets an idle worker release its browser or retire. */
    public ScheduledBotTask pollNext(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0L) throw new IllegalArgumentException("Scheduler poll timeout cannot be negative.");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) return null;
            ScheduledBotTask task = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (task == null) return null;
            ScheduledBotTask claimed = claimIfCurrent(task);
            if (claimed != null) return claimed;
        }
    }

    boolean hasReadyWork() {
        ScheduledBotTask task = queue.peek();
        return task != null && task.getDelay(TimeUnit.NANOSECONDS) <= 0L;
    }

    public ScheduledBotTask pollPreviewNext(Long botId, long timeoutMillis) throws InterruptedException {
        if (botId == null || botId <= 0L) throw new IllegalArgumentException("Preview bot ID must be positive.");
        if (timeoutMillis < 0L) throw new IllegalArgumentException("Preview scheduler poll timeout cannot be negative.");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            synchronized (this) {
                ScheduledBotTask claimed = claimPreviewIfDue(botId);
                if (claimed != null) return claimed;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) return null;
            Thread.sleep(Math.min(250L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
        }
    }

    private ScheduledBotTask claimPreviewIfDue(Long botId) {
        BotSchedule schedule = schedules.get(botId);
        if (schedule == null || !schedule.enabled() || !pausedBotIds.contains(botId)
                || schedule.isWorking()) return null;
        if (schedule.isQueued()) removeQueuedTask(botId, schedule);
        BotSchedule.NextJob next = schedule.nextJob();
        long now = System.currentTimeMillis();
        if (next.runAtEpochMs() > now) return null;
        if (catalogAtCapacity(next.jobType())) {
            schedule.deferCatalog(now, catalogConcurrencyConfig.retryDelayMillis());
            return null;
        }
        schedule.claim(next.jobType());
        return ScheduledBotTask.afterDelay(botId, next.jobType(), 0L);
    }

    private synchronized ScheduledBotTask claimIfCurrent(ScheduledBotTask task) {
        BotSchedule schedule = schedules.get(task.botId());
        if (schedule == null || !schedule.enabled()) return null;
        if (pausedBotIds.contains(task.botId())) {
            if (schedule.matches(task)) schedule.clearQueued();
            return null;
        }
        if (!schedule.matches(task)) return null;
        if (catalogAtCapacity(task.jobType())) {
            long now = System.currentTimeMillis();
            schedule.deferCatalog(now, catalogConcurrencyConfig.retryDelayMillis());
            schedule.clearQueued();
            schedule.reportQueuedStatus = false;
            enqueueEarliestJob(task.botId(), schedule, now);
            log.debug("[SCHEDULER MEMORY] Deferred CATALOG_SCAN for bot {} at catalog capacity; negotiation work remains eligible.",
                    task.botId());
            return null;
        }
        schedule.claim(task.jobType());
        return task;
    }

    private boolean catalogAtCapacity(ScheduledJobType type) {
        return type == ScheduledJobType.CATALOG_SCAN
                && workingCatalogCountUnsafe() >= catalogConcurrencyConfig.maxConcurrentCatalogScans();
    }

    public synchronized void completeRun(Long botId, ScheduledJobType jobType, long nextDelayMillis,
                                         boolean delayAllJobs, boolean reportQueued) {
        BotSchedule schedule = schedules.get(botId);
        if (schedule == null) return;
        if (!schedule.enabled()) {
            removeSchedule(botId);
            return;
        }
        long now = System.currentTimeMillis();
        schedule.complete(jobType, now, nextDelayMillis, delayAllJobs);
        schedule.reportQueuedStatus = reportQueued;
        enqueueEarliestJob(botId, schedule, now);
    }

    public synchronized void setPausedBotIds(Set<Long> requestedPausedBotIds) {
        Set<Long> normalized = new HashSet<>();
        if (requestedPausedBotIds != null) {
            requestedPausedBotIds.stream().filter(id -> id != null && id > 0L).forEach(normalized::add);
        }
        Set<Long> newlyPaused = new HashSet<>(normalized);
        newlyPaused.removeAll(pausedBotIds);
        Set<Long> resumed = new HashSet<>(pausedBotIds);
        resumed.removeAll(normalized);
        for (Long botId : newlyPaused) {
            BotSchedule schedule = schedules.get(botId);
            if (schedule == null) continue;
            if (schedule.isQueued()) removeQueuedTask(botId, schedule);
            log.info("[SESSION PREVIEW] Scheduler paused bot {}. A working job may finish; generic workers cannot claim another.", botId);
        }
        pausedBotIds.clear();
        pausedBotIds.addAll(normalized);
        long now = System.currentTimeMillis();
        for (Long botId : resumed) {
            BotSchedule schedule = schedules.get(botId);
            if (schedule == null || !schedule.enabled() || !schedule.isIdle()) continue;
            schedule.reportQueuedStatus = true;
            enqueueEarliestJob(botId, schedule, now);
            log.info("[SESSION PREVIEW] Scheduler resumed bot {} after preview ownership ended.", botId);
        }
    }

    public synchronized void wakeForManualSessionIntervention(Long botId) {
        if (makeDue(botId, false)) {
            log.warn("[SESSION PREVIEW] Manual intervention requested for blocked bot {}. One due job is eligible for the exclusive LIVE owner; this does not clear the persisted block.", botId);
        }
    }

    public synchronized void resumeAfterSessionRecovery(Long botId) {
        if (makeDue(botId, true)) {
            log.info("[SESSION PREVIEW] Verified session recovery for bot {}. Scheduler cooldown was released.", botId);
        }
    }

    private boolean makeDue(Long botId, boolean reportQueued) {
        BotSchedule schedule = schedules.get(botId);
        if (schedule == null || !schedule.enabled()) return false;
        long now = System.currentTimeMillis();
        schedule.makeDue(now);
        if (schedule.isQueued()) removeQueuedTask(botId, schedule);
        schedule.reportQueuedStatus = reportQueued;
        if (schedule.isIdle()) enqueueEarliestJob(botId, schedule, now);
        return true;
    }

    public synchronized boolean isWorking(Long botId) {
        BotSchedule schedule = schedules.get(botId);
        return schedule != null && schedule.isWorking();
    }

    public synchronized boolean isPaused(Long botId) { return botId != null && pausedBotIds.contains(botId); }
    public synchronized int queuedCount() { return queue.size(); }
    public synchronized int workingCount() { return (int) schedules.values().stream().filter(BotSchedule::isWorking).count(); }
    public synchronized int workingCatalogCount() { return workingCatalogCountUnsafe(); }
    public synchronized int enabledBotCount() { return (int) schedules.values().stream().filter(BotSchedule::enabled).count(); }

    public synchronized void shutdown() {
        schedules.clear();
        queue.clear();
        pausedBotIds.clear();
    }

    private int workingCatalogCountUnsafe() {
        return (int) schedules.values().stream().filter(BotSchedule::isWorkingCatalog).count();
    }

    private void disableBot(Long botId) {
        BotSchedule schedule = schedules.get(botId);
        if (schedule == null) return;
        schedule.disable();
        // Retain a working claim until its owner finishes, including during LIVE handover.
        if (!schedule.isWorking()) {
            removeQueuedTask(botId, schedule);
            removeSchedule(botId);
        }
    }

    private void removeSchedule(Long botId) {
        schedules.remove(botId);
        BotProcessStateCleaner.clear(botId);
        telemetryReporter.idle(botId);
    }

    private void enableOrRefreshBot(Long botId, boolean hasActiveNegotiations, long now) {
        BotSchedule schedule = schedules.get(botId);
        if (schedule == null) {
            schedule = new BotSchedule(hasActiveNegotiations, now);
            schedules.put(botId, schedule);
            enqueueEarliestJob(botId, schedule, now);
        } else if (schedule.refresh(hasActiveNegotiations, now) && schedule.isQueued()) {
            removeQueuedTask(botId, schedule);
            enqueueEarliestJob(botId, schedule, now);
        }
    }

    private void enqueueEarliestJob(Long botId, BotSchedule schedule, long now) {
        if (!schedule.enabled() || pausedBotIds.contains(botId)) return;
        BotSchedule.NextJob next = schedule.nextJob();
        ScheduledBotTask task = ScheduledBotTask.afterDelay(botId, next.jobType(), Math.max(0L, next.runAtEpochMs() - now));
        queue.offer(task);
        schedule.queued(task);
        if (schedule.reportQueuedStatus) telemetryReporter.queued(botId, next.runAtEpochMs());
    }

    private void removeQueuedTask(Long botId, BotSchedule schedule) {
        queue.removeIf(task -> botId.equals(task.botId()));
        schedule.clearQueued();
    }
}
