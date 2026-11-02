import type { DashboardPeriod, DashboardStatsResponse } from "../../api/dashboardApi";
import type { HistoryOutcome, OfferAssessment } from "../../api/historyApi";

export interface DashboardStat {
    label: string;
    value: string;
    description: string;
    variant?: "default" | "success" | "warning";
}

interface PeriodOption {
    value: DashboardPeriod;
    label: string;
}

export const periodOptions: PeriodOption[] = [
    { value: "TODAY", label: "Dzisiaj" },
    { value: "LAST_7_DAYS", label: "7 dni" },
    { value: "LAST_30_DAYS", label: "30 dni" },
    { value: "THIS_MONTH", label: "Ten miesiąc" },
    { value: "THIS_YEAR", label: "Ten rok" },
    { value: "ALL", label: "Całość" },
];

export const outcomeOptions: Array<{
    value: HistoryOutcome;
    label: string;
}> = [
    { value: "PURCHASED", label: "Kupiłem" },
    { value: "REJECTED", label: "Nie kupiłem" },
    { value: "UNCLASSIFIED", label: "Do oznaczenia" },
];

export const assessmentOptions: Array<{
    value: OfferAssessment;
    label: string;
}> = [
    { value: "LEGIT", label: "Legit" },
    { value: "SCAM", label: "Oszustwo" },
    { value: "UNASSESSED", label: "Nieocenione" },
];

export function dashboardSummary(stats: DashboardStatsResponse | null, period: DashboardPeriod) {
    const activityStats: DashboardStat[] =
        stats === null
            ? []
            : [
                {
                    label: "Aktywne boty",
                    value: stats.activeBotsCount.toString(),
                    description: "Boty ze statusem RUNNING",
                },
                {
                    label: "Trwające negocjacje",
                    value: stats.negotiatingCount.toString(),
                    description: "Oferty ze statusem NEGOTIATING",
                },
            ];

    const periodStats: DashboardStat[] =
        stats === null
            ? []
            : [
                {
                    label: "Wybrane oferty",
                    value: stats.selectedHistoryCount.toString(),
                    description: "Po zastosowaniu filtrów",
                },
                {
                    label: "Kupiłem",
                    value: stats.purchasedCount.toString(),
                    description: getPeriodDescription(period),
                    variant: "success",
                },
                {
                    label: "Nie kupiłem",
                    value: stats.skippedByUserCount.toString(),
                    description: "Oferty z etapu „do kupienia”, których finalnie nie kupiłeś",
                },
                {
                    label: "Legit",
                    value: stats.legitCount.toString(),
                    description: "Oferty oznaczone jako wiarygodne",
                    variant: "success",
                },
                {
                    label: "Oszustwa",
                    value: stats.scamCount.toString(),
                    description: "Oferty oznaczone jako oszustwo/podejrzane",
                },
                {
                    label: "Łącznie wydano",
                    value: formatPrice(stats.totalSpent),
                    description: "Suma cen ofert sklasyfikowanych jako kupione",
                },
                {
                    label: "Wynegocjowano",
                    value: formatPrice(stats.totalNegotiatedSavings),
                    description: "Różnica względem cen początkowych",
                    variant: "success",
                },
                {
                    label: "Średnia cena zakupu",
                    value: formatPrice(stats.averagePurchasePrice),
                    description: "Tylko oferty sklasyfikowane jako kupione",
                },
                {
                    label: "Średni rabat",
                    value: formatPercentage(stats.averageDiscountPercentage),
                    description: "Średnia obniżka ceny kupionych ofert",
                    variant: "success",
                },
                {
                    label: "Średnia cena wybranych",
                    value: formatNullablePrice(stats.averageSelectedPrice),
                    description: "Wszystkie oferty przechodzące aktualne filtry",
                },
                {
                    label: "Mediana ceny wybranych",
                    value: formatNullablePrice(stats.medianSelectedPrice),
                    description: "Odporna na pojedyncze bardzo drogie/tanie oferty",
                },
            ];

    const purchaseRate =
        stats === null
            ? 0
            : calculatePurchaseRate(
                stats.purchasedCount,
                stats.skippedByUserCount,
            );

    return { activityStats, periodStats, purchaseRate };
}

function calculatePurchaseRate(
    purchasedCount: number,
    skippedCount: number,
): number {
    const total = purchasedCount + skippedCount;

    if (total === 0) {
        return 0;
    }

    return (purchasedCount / total) * 100;
}

function getPeriodDescription(
    period: DashboardPeriod,
): string {
    switch (period) {
        case "TODAY":
            return "Kupione dzisiaj";
        case "LAST_7_DAYS":
            return "Kupione w ostatnich 7 dniach";
        case "LAST_30_DAYS":
            return "Kupione w ostatnich 30 dniach";
        case "THIS_MONTH":
            return "Kupione w tym miesiącu";
        case "THIS_YEAR":
            return "Kupione w tym roku";
        case "ALL":
            return "Kupione od początku";
    }
}

export function getPeriodLabel(
    period: DashboardPeriod,
): string {
    switch (period) {
        case "TODAY":
            return "dzisiaj";
        case "LAST_7_DAYS":
            return "ostatnie 7 dni";
        case "LAST_30_DAYS":
            return "ostatnie 30 dni";
        case "THIS_MONTH":
            return "ten miesiąc";
        case "THIS_YEAR":
            return "ten rok";
        case "ALL":
            return "cały okres";
    }
}

export function formatSelection<T extends string>(
    selected: T[],
    options: Array<{ value: T; label: string }>,
    fallback: string,
): string {
    if (selected.length === 0) {
        return fallback;
    }

    return selected
        .map(value => options.find(option => option.value === value)?.label ?? value)
        .join(", ");
}

function formatPrice(
    value: number,
): string {
    return new Intl.NumberFormat(
        "pl-PL",
        {
            style: "currency",
            currency: "PLN",
            maximumFractionDigits: 2,
        },
    ).format(value);
}

function formatNullablePrice(
    value: number | null,
): string {
    return value === null
        ? "—"
        : formatPrice(value);
}

export function formatPercentage(
    value: number,
): string {
    return new Intl.NumberFormat(
        "pl-PL",
        {
            minimumFractionDigits: 1,
            maximumFractionDigits: 1,
        },
    ).format(value) + "%";
}
