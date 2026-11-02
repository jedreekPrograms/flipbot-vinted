import type { RuntimeDashboardBot, RuntimeStatus } from "../../api/dashboardApi";
import { formatElapsedDuration } from "../../shared/duration";
import { formatDateTime, formatDuration, formatRetryCountdown } from "./runtimeFormat";

export function RuntimeRow({
    bot,
    nowMs,
    previewUpdating,
    onSessionPreview,
}: {
    bot: RuntimeDashboardBot;
    nowMs: number;
    previewUpdating: boolean;
    onSessionPreview: (botId: number, enabled: boolean) => Promise<void>;
}) {
    const sessionBlocked = bot.sessionBlockedSince !== null;
    const canPreview = bot.botStatus === "RUNNING";

    return (
        <tr>
            <TableCell label="Bot">
                <strong>{bot.name}</strong>
                <div className="runtime-cell-secondary">
                    #{bot.botId}
                </div>
            </TableCell>
            <TableCell label="Bot status">
                {bot.botStatus}
            </TableCell>
            <TableCell label="Runtime">
                <RuntimeStateCell
                    bot={bot}
                    nowMs={nowMs}
                />
            </TableCell>
            <TableCell label="Sesja">
                {canPreview ? (
                    <>
                        <button
                            className="secondary-button"
                            type="button"
                            disabled={previewUpdating}
                            title={bot.sessionPreviewRequested
                                ? "Wyłącza tryb LIVE i po pełnym zamknięciu widocznego Chromium oddaje bota zwykłym workerom."
                                : "Otwiera widoczne Chromium w trybie LIVE. Bot dalej wykonuje normalne skany i negocjacje w tym oknie; możesz obserwować działanie i ręcznie przejść CAPTCHA, a potwierdzona zalogowana sesja zostanie bezpiecznie zapisana."}
                            onClick={() => {
                                void onSessionPreview(
                                    bot.botId,
                                    !bot.sessionPreviewRequested,
                                );
                            }}
                        >
                            {previewUpdating
                                ? "Zapisywanie..."
                                : bot.sessionPreviewRequested
                                    ? "Ukryj sesję"
                                    : "Wyświetl sesję"}
                        </button>
                        {bot.sessionPreviewRequested && (
                            <div className="runtime-cell-secondary">
                                {sessionBlocked
                                    ? "LIVE · ręczne odblokowanie: przejdź CAPTCHA, a bot automatycznie wznowi pracę"
                                    : "LIVE · bot normalnie pracuje w widocznym Chromium"}
                            </div>
                        )}
                    </>
                ) : (
                    "—"
                )}
            </TableCell>
            <TableCell label="Slot">
                {bot.workerSlot === null
                    ? "—"
                    : bot.workerSlot === 0
                        ? "LIVE"
                        : `#${bot.workerSlot}`}
            </TableCell>
            <TableCell label="Ostatni job">
                <div>{formatDateTime(bot.lastRunFinishedAt)}</div>
                <div className="runtime-cell-secondary">
                    {formatDuration(bot.lastRunDurationMs)}
                </div>
            </TableCell>
            <TableCell label="Następny job">
                {sessionBlocked ? (
                    <>
                        <div>{formatDateTime(bot.nextRunAt)}</div>
                        <div className="runtime-cell-secondary">
                            {formatRetryCountdown(bot.nextRunAt, nowMs)}
                        </div>
                    </>
                ) : (
                    formatDateTime(bot.nextRunAt)
                )}
            </TableCell>
            <TableCell label="Błędy z rzędu">
                {bot.consecutiveFailures}
            </TableCell>
            <TableCell label="Ostatni błąd">
                <div
                    className="runtime-error-text"
                    title={bot.lastError ?? undefined}
                >
                    {bot.lastError ?? "—"}
                </div>
            </TableCell>
        </tr>
    );
}

function RuntimeStateCell({
    bot,
    nowMs,
}: {
    bot: RuntimeDashboardBot;
    nowMs: number;
}) {
    if (bot.sessionBlockedSince !== null) {
        return (
            <div>
                <RuntimeBadge
                    status={bot.runtimeStatus}
                    label="SESSION BLOCKED"
                />
                <div className="runtime-cell-secondary">
                    Sesja zablokowana od {formatElapsedDuration(bot.sessionBlockedSince, nowMs)}
                </div>
                <div className="runtime-cell-secondary">
                    Próba {Math.max(bot.sessionBlockCount, 1)} · {formatRetryCountdown(bot.nextRunAt, nowMs)}
                </div>
            </div>
        );
    }

    return <RuntimeBadge status={bot.runtimeStatus} />;
}

function RuntimeBadge({
    status,
    label,
}: {
    status: RuntimeStatus;
    label?: string;
}) {
    return (
        <span className={`runtime-badge runtime-badge-${status.toLowerCase()}`}>
            {label ?? status}
        </span>
    );
}

export function TableHeader({ children }: { children: React.ReactNode }) {
    return <th>{children}</th>;
}

function TableCell({
    children,
    label,
}: {
    children: React.ReactNode;
    label: string;
}) {
    return (
        <td data-label={label}>
            {children}
        </td>
    );
}
