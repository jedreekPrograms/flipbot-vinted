export function formatElapsedDuration(value: string, nowMs: number): string {
    const startedAt = Date.parse(value);
    if (!Number.isFinite(startedAt)) {
        return "nieznanego czasu";
    }

    const totalMinutes = Math.max(0, Math.floor((nowMs - startedAt) / 60_000));
    if (totalMinutes < 1) {
        return "mniej niż 1 min";
    }
    if (totalMinutes < 60) {
        return `${totalMinutes} min`;
    }

    const totalHours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    if (totalHours < 24) {
        return `${totalHours} godz.${minutes > 0 ? ` ${minutes} min` : ""}`;
    }

    const days = Math.floor(totalHours / 24);
    const hours = totalHours % 24;
    return `${days} d${hours > 0 ? ` ${hours} godz.` : ""}${minutes > 0 ? ` ${minutes} min` : ""}`;
}
