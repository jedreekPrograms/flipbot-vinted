import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLatestRequest } from "../../shared/useLatestRequest";
import { useVisiblePolling } from "../../shared/useVisiblePolling";
import {
    getRuntimeDashboard, setRuntimeSessionPreview,
    type RuntimeDashboardResponse, type RuntimeStatus,
} from "../../api/dashboardApi";

export default function useRuntimeDashboard() {
    const [data, setData] = useState<RuntimeDashboardResponse | null>(null);
    const [statusFilter, setStatusFilter] = useState<RuntimeStatus | "ALL">("ALL");
    const [search, setSearch] = useState("");
    const [isLoading, setIsLoading] = useState(true);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const [nowMs, setNowMs] = useState(() => Date.now());
    const [previewUpdatingBotIds, setPreviewUpdatingBotIds] = useState<ReadonlySet<number>>(new Set());
    const previewRequests = useRef(new Set<number>());
    const runtimeRequests = useLatestRequest();

    const loadRuntime = useCallback(async (showLoading: boolean) => {
        const request = runtimeRequests.start();
        if (showLoading) {
            setIsLoading(true);
        }

        try {
            const response = await getRuntimeDashboard();
            if (runtimeRequests.isCurrent(request)) {
                setData(response);
                setErrorMessage(null);
            }
        } catch (error) {
            if (runtimeRequests.isCurrent(request)) {
                setErrorMessage(
                    error instanceof Error
                        ? error.message
                        : "Nie udało się pobrać stanu runtime.",
                );
            }
        } finally {
            if (runtimeRequests.isCurrent(request)) setIsLoading(false);
            runtimeRequests.finish(request);
        }
    }, [runtimeRequests]);

    const handleSessionPreview = useCallback(async (
        botId: number,
        enabled: boolean,
    ) => {
        if (previewRequests.current.has(botId)) {
            return;
        }
        previewRequests.current.add(botId);
        setPreviewUpdatingBotIds(new Set(previewRequests.current));

        try {
            await setRuntimeSessionPreview(botId, enabled);
            if (runtimeRequests.active) await loadRuntime(false);
        } catch (error) {
            if (!runtimeRequests.active) return;
            setErrorMessage(
                error instanceof Error
                    ? error.message
                    : "Nie udało się zmienić podglądu sesji.",
            );
        } finally {
            previewRequests.current.delete(botId);
            if (runtimeRequests.active) setPreviewUpdatingBotIds(new Set(previewRequests.current));
        }
    }, [loadRuntime, runtimeRequests]);

    useEffect(() => { void loadRuntime(true); }, [loadRuntime]);
    const refreshRuntime = useCallback(async () => {
        if (!runtimeRequests.pending) await loadRuntime(false);
    }, [loadRuntime, runtimeRequests]);
    useVisiblePolling(refreshRuntime, 5_000);

    useEffect(() => {
        const intervalId = window.setInterval(
            () => setNowMs(Date.now()),
            1_000,
        );

        return () => {
            window.clearInterval(intervalId);
        };
    }, []);

    const filteredBots = useMemo(() => {
        if (data === null) {
            return [];
        }

        const normalizedSearch = search.trim().toLowerCase();

        return data.bots.filter(bot => {
            if (
                statusFilter !== "ALL"
                && bot.runtimeStatus !== statusFilter
            ) {
                return false;
            }

            if (normalizedSearch.length === 0) {
                return true;
            }

            return bot.name.toLowerCase().includes(normalizedSearch)
                || bot.botId.toString().includes(normalizedSearch);
        });
    }, [data, search, statusFilter]);

    return {
        data, statusFilter, setStatusFilter, search, setSearch, isLoading, errorMessage,
        nowMs, previewUpdatingBotIds, loadRuntime, handleSessionPreview, filteredBots,
    };
}
