import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getAnalyticsOverview, type AnalyticsOverview, type AnalyticsSource, type AnalyticsGranularity } from "../../api/analyticsApi";
import type { DashboardPeriod } from "../../api/dashboardApi";
import type { HistoryOutcome, OfferAssessment } from "../../api/historyApi";
import { priceSeriesOptions, type PriceSeries } from "./analyticsOptions";
import { formatObserverRange } from "./analyticsFormat";

export default function useStatisticsReport() {
    const [overview, setOverview] = useState<AnalyticsOverview | null>(null);
    const [period, setPeriod] = useState<DashboardPeriod>("THIS_MONTH");
    const [modelIds, setModelIds] = useState<number[]>([]);
    const [source, setSource] = useState<AnalyticsSource>("ALL");
    const [granularity, setGranularity] =
        useState<AnalyticsGranularity>("DAY");
    const [outcomes, setOutcomes] = useState<HistoryOutcome[]>([]);
    const [assessments, setAssessments] = useState<OfferAssessment[]>([]);
    const [from, setFrom] = useState("");
    const [to, setTo] = useState("");
    const [priceSeries, setPriceSeries] = useState<PriceSeries[]>([
        "MARKET_MEDIAN",
        "PURCHASE_AVERAGE",
    ]);
    const [isLoading, setIsLoading] = useState(true);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const latestRequest = useRef(0);

    const load = useCallback(async () => {
        const request = ++latestRequest.current;
        setIsLoading(true);
        setErrorMessage(null);

        try {
            const response = await getAnalyticsOverview({
                    period,
                    modelIds,
                    outcomes,
                    assessments,
                    source,
                    granularity,
                    from: from.length > 0 ? from : null,
                    to: to.length > 0 ? to : null,
                });
            if (request === latestRequest.current) setOverview(response);
        } catch (error) {
            if (request !== latestRequest.current) return;
            setOverview(null);
            setErrorMessage(
                error instanceof Error
                    ? error.message
                    : "Nie udało się pobrać statystyk.",
            );
        } finally {
            if (request === latestRequest.current) setIsLoading(false);
        }
    }, [
        period,
        modelIds,
        outcomes,
        assessments,
        source,
        granularity,
        from,
        to,
    ]);

    useEffect(() => {
        void load();
        return () => { latestRequest.current++; };
    }, [load]);

    const models = useMemo(() => overview?.models ?? [], [overview]);
    const summary = overview?.summary ?? null;

    const visiblePriceSeriesOptions = useMemo(
        () => priceSeriesOptions.filter(option =>
            source === "OBSERVER"
                ? option.value.startsWith("MARKET")
                : source === "HISTORY"
                    ? option.value.startsWith("PURCHASE")
                    : true,
        ),
        [source],
    );

    const visiblePriceSeries = useMemo(() => {
        const allowed = new Set(
            visiblePriceSeriesOptions.map(option => option.value),
        );
        return priceSeries.filter(series => allowed.has(series));
    }, [priceSeries, visiblePriceSeriesOptions]);

    const observerRangeDetail = useMemo(() => {
        const selected = modelIds.length === 0
            ? []
            : models.filter(model => modelIds.includes(model.modelId));

        if (selected.length === 1) {
            const model = selected[0];
            return `Zakres próbki Observera dla ${model.brand} ${model.model}: ${formatObserverRange(model.marketMinPrice, model.marketMaxPrice)}.`;
        }

        if (selected.length > 1 && selected.length <= 3) {
            return selected
                .map(model =>
                    `${model.brand} ${model.model}: ${formatObserverRange(model.marketMinPrice, model.marketMaxPrice)}`,
                )
                .join(" · ");
        }

        return "Observer używa osobnego zakresu cen ustawionego w Cenniku modeli; statystyki rynku dotyczą tej próbki, a nie ofert poza skonfigurowanym zakresem.";
    }, [modelIds, models]);

    function choosePreset(nextPeriod: DashboardPeriod) {
        setFrom("");
        setTo("");
        setPeriod(nextPeriod);
    }

    return {
        overview, summary, models, period, source, granularity, from, to, modelIds, outcomes, assessments,
        priceSeries, visiblePriceSeries, visiblePriceSeriesOptions, observerRangeDetail,
        isLoading, errorMessage, load, choosePreset, setSource, setGranularity, setFrom, setTo,
        setModelIds, setOutcomes, setAssessments, setPriceSeries,
    };
}
