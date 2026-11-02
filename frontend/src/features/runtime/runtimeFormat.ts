export function formatDateTime(value: string | null): string {
    if (value === null) {
        return "—";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return "—";
    }

    return new Intl.DateTimeFormat(
        "pl-PL",
        {
            dateStyle: "short",
            timeStyle: "medium",
        },
    ).format(date);
}

export function formatDuration(value: number | null): string {
    if (value === null || !Number.isFinite(value) || value < 0) {
        return "—";
    }

    if (value < 1_000) {
        return `${Math.round(value)} ms`;
    }

    return `${(value / 1_000).toFixed(1)} s`;
}

export function formatRetryCountdown(value: string | null, nowMs: number): string {
    if (value === null) {
        return "trwa ponowna próba";
    }

    const nextRunAt = Date.parse(value);
    if (!Number.isFinite(nextRunAt) || nextRunAt <= nowMs) {
        return "trwa ponowna próba";
    }

    const totalSeconds = Math.max(1, Math.ceil((nextRunAt - nowMs) / 1_000));
    if (totalSeconds < 60) {
        return `ponownie za ${totalSeconds} s`;
    }

    const totalMinutes = Math.ceil(totalSeconds / 60);
    if (totalMinutes < 60) {
        return `ponownie za ${totalMinutes} min`;
    }

    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    return `ponownie za ${hours} godz.${minutes > 0 ? ` ${minutes} min` : ""}`;
}
