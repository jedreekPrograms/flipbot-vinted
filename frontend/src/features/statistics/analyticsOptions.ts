import type { DashboardPeriod } from "../../api/dashboardApi";
import type { HistoryOutcome, OfferAssessment } from "../../api/historyApi";
import type { AppSelectOption } from "../../components/AppSelect";

export const periodOptions: Array<{ value: DashboardPeriod; label: string }> = [
    { value: "TODAY", label: "Dzisiaj" },
    { value: "LAST_7_DAYS", label: "7 dni" },
    { value: "LAST_30_DAYS", label: "30 dni" },
    { value: "THIS_MONTH", label: "Ten miesiąc" },
    { value: "THIS_YEAR", label: "Ten rok" },
    { value: "ALL", label: "Całość" },
];

export const sourceOptions: AppSelectOption[] = [
    { value: "ALL", label: "Rynek + nasze decyzje" },
    { value: "OBSERVER", label: "Tylko rynek (Observer)" },
    { value: "HISTORY", label: "Tylko nasze decyzje" },
];

export const granularityOptions: AppSelectOption[] = [
    { value: "DAY", label: "Dzień" },
    { value: "WEEK", label: "Tydzień" },
    { value: "MONTH", label: "Miesiąc" },
    { value: "YEAR", label: "Rok" },
];

export const outcomeOptions: Array<{ value: HistoryOutcome; label: string }> = [
    { value: "PURCHASED", label: "Kupiłem" },
    { value: "REJECTED", label: "Nie kupiłem" },
    { value: "UNCLASSIFIED", label: "Do oznaczenia" },
];

export const assessmentOptions: Array<{ value: OfferAssessment; label: string }> = [
    { value: "LEGIT", label: "Legit" },
    { value: "SCAM", label: "Oszustwo" },
    { value: "UNASSESSED", label: "Nieocenione" },
];

export type PriceSeries =
    | "MARKET_MEDIAN"
    | "MARKET_AVERAGE"
    | "PURCHASE_AVERAGE"
    | "PURCHASE_MEDIAN";

export const priceSeriesOptions: Array<{ value: PriceSeries; label: string }> = [
    { value: "MARKET_MEDIAN", label: "Mediana rynku" },
    { value: "MARKET_AVERAGE", label: "Średnia rynku" },
    { value: "PURCHASE_AVERAGE", label: "Średnia zakupu" },
    { value: "PURCHASE_MEDIAN", label: "Mediana zakupu" },
];
