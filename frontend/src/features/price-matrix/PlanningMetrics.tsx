import type { ModelPlanning } from "../../types/marketStats";

export function TodayMetricCell({
    planning,
}: {
    planning: ModelPlanning | undefined;
}) {
    return (
        <CalendarMetricCell
            label="Dzisiaj"
            planning={planning}
            value={planning?.offersToday ?? null}
            complete={planning?.todayWindowComplete ?? false}
            completeText="od 00:00"
            partialText="brak pełnego skanu dzisiaj"
            showBaseline
        />
    );
}

export function CurrentWeekMetricCell({
    planning,
}: {
    planning: ModelPlanning | undefined;
}) {
    return (
        <CalendarMetricCell
            label="Ten tydzień"
            planning={planning}
            value={planning?.offersCurrentWeek ?? null}
            complete={planning?.currentWeekWindowComplete ?? false}
            completeText="od pon. 00:00"
            partialText="brak pełnego skanu dzisiaj"
        />
    );
}

function CalendarMetricCell({
    label,
    planning,
    value,
    complete,
    completeText,
    partialText,
    showBaseline = false,
}: {
    label: string;
    planning: ModelPlanning | undefined;
    value: number | null;
    complete: boolean;
    completeText: string;
    partialText: string;
    showBaseline?: boolean;
}) {
    if (planning === undefined) {
        return (
            <div className="price-metric-cell" data-label={label}>
                <strong>—</strong>
                <span>Brak danych</span>
            </div>
        );
    }

    if (value === null) {
        return (
            <div className="price-metric-cell" data-label={label}>
                <strong>—</strong>
                <span>
                    {planning.lastStatsUpdatedAt === null
                        ? "Czeka na pierwszy skan"
                        : "Czeka na pełny baseline"}
                </span>
            </div>
        );
    }

    const displayedValue = complete
        ? String(value)
        : value > 0
            ? `≥ ${value}`
            : "—";

    const successfulScanLabel = planning.lastSuccessfulScanAt === null
        ? null
        : formatPlanningScanTime(planning.lastSuccessfulScanAt);

    const coverageText = complete
        ? successfulScanLabel === null
            ? completeText
            : `${completeText} · stan na ${successfulScanLabel}`
        : value > 0
            ? `${partialText} · minimum wykryte`
            : `${partialText} · brak pełnego pokrycia`;

    return (
        <div className="price-metric-cell" data-label={label}>
            <strong>{displayedValue}</strong>
            <span>{coverageText}</span>
            {!complete && value === 0 && (
                <span className="price-metric-note">
                    Wykryto 0, ale Observer nie ma podstaw, by uznać 0 za pełny wynik.
                </span>
            )}
            {showBaseline && planning.baselineOffers !== null && (
                <span className="price-metric-note">
                    punkt startowy: {planning.baselineOffers} ofert
                </span>
            )}
            {complete && !planning.currentWindowFresh && (
                <span className="price-metric-warning">
                    Pełny skan jest starszy niż bieżący cykl Observera.
                </span>
            )}
            {!complete && successfulScanLabel !== null && (
                <span className="price-metric-note">
                    ostatni pełny skan: {successfulScanLabel}
                </span>
            )}
            {!planning.lastScanComplete && (
                <span className="price-metric-warning">Ostatni skan niepełny</span>
            )}
        </div>
    );
}

export function PreviousFullWeekMetricCell({
    planning,
}: {
    planning: ModelPlanning | undefined;
}) {
    if (planning === undefined) {
        return (
            <div className="price-metric-cell" data-label="Ostatni pełny tydzień">
                <strong>—</strong>
                <span>Brak danych</span>
            </div>
        );
    }

    if (!planning.previousFullWeekAvailable) {
        return (
            <div className="price-metric-cell" data-label="Ostatni pełny tydzień">
                <strong>—</strong>
                <span>jeszcze brak pełnego tygodnia</span>
                {planning.trackedDays > 0 && (
                    <span className="price-metric-note">
                        śledzenie: {planning.trackedDays} dni
                    </span>
                )}
            </div>
        );
    }

    return (
        <div className="price-metric-cell" data-label="Ostatni pełny tydzień">
            <strong>{planning.offersPreviousFullWeek ?? 0}</strong>
            <span>poprzedni pon.–niedz.</span>
            {!planning.lastScanComplete && (
                <span className="price-metric-warning">Ostatni skan niepełny</span>
            )}
        </div>
    );
}

export function RecommendedBotsMetricCell({
    planning,
}: {
    planning: ModelPlanning | undefined;
}) {
    if (planning === undefined || planning.recommendedBots === null) {
        return (
            <div className="price-metric-cell" data-label="Potrzebne boty">
                <strong>—</strong>
                <span>Czeka na baseline</span>
            </div>
        );
    }

    return (
        <div className="price-metric-cell" data-label="Potrzebne boty">
            <strong>{planning.recommendedBots}</strong>
            <span>
                {planning.recommendationEstimated
                    ? `estymacja: ${planning.recommendationWeeklyOffers ?? 0} ofert/tydz.`
                    : `pełny tydzień: ${planning.recommendationWeeklyOffers ?? 0} ofert`}
            </span>
            {planning.recommendationEstimated && (
                <span className="price-metric-note">
                    średnia z {Math.max(planning.trackedDays, 1)} dni × 7
                </span>
            )}
            <span className="price-metric-note">
                realna przepustowość: {planning.dailyConversationCapacityPerBot}/dzień
                {" · "}{planning.weeklyConversationCapacityPerBot}/tydz.
            </span>
            <span className="price-metric-note">
                per model: 75. percentyl aktywnych dni z ostatnich 28 dni; minimum awaryjne 5/dzień
            </span>
        </div>
    );
}

function formatPlanningScanTime(value: string): string {
    const parsed = new Date(value);

    if (Number.isNaN(parsed.getTime())) {
        return value;
    }

    return new Intl.DateTimeFormat("pl-PL", {
        day: "2-digit",
        month: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    }).format(parsed);
}
