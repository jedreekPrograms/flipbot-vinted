import { formatElapsedDuration } from "../../shared/duration";
import type { BotDailyActivity, BotRuntimeState } from "../../api/botsApi";

export function BotDailyLimitCell({ activity }: { activity: BotDailyActivity }) {
    const safeLimit = Math.max(activity.dailyLimit, 1);
    const safeUsed = Math.min(Math.max(activity.dailyLimitUsed, 0), safeLimit);
    const percentage = activity.dailyLimit > 0
        ? Math.round((safeUsed / safeLimit) * 100)
        : 0;

    return (
        <div className="bot-quota-cell">
            <div className="bot-quota-header">
                <strong className="bot-quota-value">
                    {activity.dailyLimitUsed} / {activity.dailyLimit}
                </strong>
                <span className="bot-quota-percentage">{percentage}%</span>
            </div>
            <progress
                className="bot-quota-progress"
                max={safeLimit}
                value={safeUsed}
                aria-label={`Wykorzystano ${activity.dailyLimitUsed} z ${activity.dailyLimit} dziennego limitu realnych akcji ofertowych`}
            />
            <span className="bot-quota-remaining">
                Pozostało: <strong>{activity.dailyLimitRemaining}</strong>
            </span>
            <span className="bot-quota-remaining">
                Audyt: <strong>{activity.confirmedActionsToday}</strong> potw.
                {activity.ambiguousActionsToday > 0
                    ? ` · ${activity.ambiguousActionsToday} niejedn.`
                    : ""}
            </span>
            {activity.usedSlotsWithoutAuditYet > 0 && (
                <span className="bot-quota-remaining">
                    W toku / bez audytu: <strong>{activity.usedSlotsWithoutAuditYet}</strong>
                </span>
            )}
        </div>
    );
}

export function BotNegotiationStepsCell({ activity }: { activity: BotDailyActivity }) {
    const total = activity.nextStepsInNegotiationsStartedToday
        + activity.nextStepsInOlderNegotiations;

    return (
        <div className="bot-name-cell">
            <strong>{total}</strong>
            <span>Dziś rozpoczęte: {activity.nextStepsInNegotiationsStartedToday}</span>
            <span>Starsze: {activity.nextStepsInOlderNegotiations}</span>
        </div>
    );
}

export function BotStatus({
    status,
    runtime,
    nowMs,
}: {
    status: string;
    runtime: BotRuntimeState | undefined;
    nowMs: number;
}) {
    const normalizedStatus = status.toUpperCase();
    const isRunning = normalizedStatus === "RUNNING";
    const blockedSince = isRunning ? runtime?.sessionBlockedSince : null;

    if (blockedSince) {
        const nextRetry = runtime?.nextRunAt
            ? Date.parse(runtime.nextRunAt)
            : Number.NaN;
        const retryText = Number.isFinite(nextRetry) && nextRetry > nowMs
            ? `ponownie za ${formatRemainingDuration(nextRetry - nowMs)}`
            : "trwa ponowna próba";

        return (
            <div className="bot-name-cell">
                <span className="bot-status bot-status-stopped">
                    <span className="bot-status-dot" />
                    Sesja zablokowana
                </span>
                <span>
                    od {formatElapsedDuration(blockedSince, nowMs)}
                </span>
                <span>
                    Próba {Math.max(runtime?.sessionBlockCount ?? 1, 1)} · {retryText}
                </span>
            </div>
        );
    }

    const statusClassName = isRunning
        ? "bot-status bot-status-running"
        : "bot-status bot-status-stopped";

    return (
        <span className={statusClassName}>
            <span className="bot-status-dot" />
            {formatBotStatus(status)}
        </span>
    );
}

function formatRemainingDuration(durationMs: number): string {
    const totalMinutes = Math.max(1, Math.ceil(durationMs / 60_000));
    if (totalMinutes < 60) {
        return `${totalMinutes} min`;
    }

    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    return `${hours} godz.${minutes > 0 ? ` ${minutes} min` : ""}`;
}

function formatBotStatus(status: string): string {
    switch (status.toUpperCase()) {
        case "RUNNING":
            return "Uruchomiony";
        case "STOPPED":
            return "Zatrzymany";
        default:
            return status;
    }
}
