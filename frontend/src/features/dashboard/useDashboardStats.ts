import { useCallback, useEffect, useRef, useState } from "react";
import { getDashboardStats, type DashboardPeriod, type DashboardStatsResponse } from "../../api/dashboardApi";
import type { HistoryOutcome, OfferAssessment } from "../../api/historyApi";
import { getErrorMessage } from "../../shared/errorMessage";

export default function useDashboardStats() {
    const [stats, setStats] = useState<DashboardStatsResponse | null>(null);
    const [period, setPeriod] = useState<DashboardPeriod>("ALL");
    const [outcomes, setOutcomes] = useState<HistoryOutcome[]>([]);
    const [assessments, setAssessments] = useState<OfferAssessment[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const latestRequest = useRef(0);

    const loadStats = useCallback(async () => {
        const request = ++latestRequest.current;
        setIsLoading(true);
        setErrorMessage(null);
        try {
            const response = await getDashboardStats(period, outcomes, assessments);
            if (request === latestRequest.current) setStats(response);
        } catch (error) {
            if (request === latestRequest.current) {
                setErrorMessage(getErrorMessage(error, "Nie udało się pobrać statystyk."));
            }
        } finally {
            if (request === latestRequest.current) setIsLoading(false);
        }
    }, [period, outcomes, assessments]);

    useEffect(() => {
        void loadStats();
        return () => { latestRequest.current++; };
    }, [loadStats]);

    return {
        stats, period, outcomes, assessments, isLoading, errorMessage, loadStats,
        setPeriod, setOutcomes, setAssessments,
    };
}
