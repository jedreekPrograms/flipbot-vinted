package pl.flipbot.playwright.worker;

/** Per-bot timing and claim state. Mutated only under the BotRunScheduler monitor. */
final class BotSchedule {
    private enum State { QUEUED, WORKING }
    private static final long NEVER = Long.MAX_VALUE;

    private boolean enabled = true;
    private boolean hasActiveNegotiations;
    private State state;
    private ScheduledJobType workingJobType;
    private long nextCatalogAt;
    private long nextNegotiationAt;
    private ScheduledJobType queuedJobType;
    private long queuedRunAtNanos;
    boolean reportQueuedStatus = true;

    BotSchedule(boolean hasActiveNegotiations, long now) {
        this.hasActiveNegotiations = hasActiveNegotiations;
        nextCatalogAt = now;
        nextNegotiationAt = hasActiveNegotiations ? now : NEVER;
    }

    boolean enabled() { return enabled; }
    void disable() { enabled = false; }
    boolean isWorking() { return state == State.WORKING; }
    boolean isQueued() { return state == State.QUEUED; }
    boolean isIdle() { return state == null; }
    boolean isWorkingCatalog() {
        return isWorking() && workingJobType == ScheduledJobType.CATALOG_SCAN;
    }

    boolean refresh(boolean activeNegotiations, long now) {
        boolean changed = hasActiveNegotiations != activeNegotiations;
        enabled = true;
        hasActiveNegotiations = activeNegotiations;
        if (changed) nextNegotiationAt = activeNegotiations ? now : NEVER;
        return changed;
    }

    boolean matches(ScheduledBotTask task) {
        return isQueued() && queuedJobType == task.jobType()
                && queuedRunAtNanos == task.runAtNanos();
    }

    void clearQueued() {
        state = null;
        queuedJobType = null;
        queuedRunAtNanos = 0L;
    }

    void queued(ScheduledBotTask task) {
        state = State.QUEUED;
        queuedJobType = task.jobType();
        queuedRunAtNanos = task.runAtNanos();
    }

    void claim(ScheduledJobType jobType) {
        state = State.WORKING;
        workingJobType = jobType;
        queuedJobType = null;
        queuedRunAtNanos = 0L;
    }

    void deferCatalog(long now, long retryDelay) {
        nextCatalogAt = Math.max(nextCatalogAt, safeAdd(now, retryDelay));
    }

    void complete(ScheduledJobType jobType, long now, long delayMillis, boolean delayAll) {
        long readyAt = safeAdd(now, Math.max(0L, delayMillis));
        workingJobType = null;
        if (delayAll) {
            nextCatalogAt = Math.max(nextCatalogAt, readyAt);
            nextNegotiationAt = hasActiveNegotiations ? Math.max(nextNegotiationAt, readyAt) : NEVER;
        } else {
            switch (jobType) {
                case CATALOG_SCAN -> nextCatalogAt = readyAt;
                case NEGOTIATION_CHECK -> nextNegotiationAt = hasActiveNegotiations ? readyAt : NEVER;
            }
        }
        clearQueued();
    }

    void makeDue(long now) {
        nextCatalogAt = Math.min(nextCatalogAt, now);
        if (hasActiveNegotiations) nextNegotiationAt = Math.min(nextNegotiationAt, now);
    }

    NextJob nextJob() {
        ScheduledJobType type = ScheduledJobType.CATALOG_SCAN;
        long runAt = nextCatalogAt;
        // Negotiations win a tie with catalog discovery.
        if (hasActiveNegotiations && nextNegotiationAt <= runAt) {
            type = ScheduledJobType.NEGOTIATION_CHECK;
            runAt = nextNegotiationAt;
        }
        return new NextJob(type, runAt);
    }

    private static long safeAdd(long base, long increment) {
        return increment > Long.MAX_VALUE - base ? Long.MAX_VALUE : base + increment;
    }

    record NextJob(ScheduledJobType jobType, long runAtEpochMs) {}
}
