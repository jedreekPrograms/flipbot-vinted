import { getErrorMessage } from "../../shared/errorMessage";
import { useCallback, useEffect, useMemo, useState } from "react";
import { getBotsOverview, getBots, startBot, stopBot } from "../../api/botsApi";
import type { BotDailyActivity, BotRuntimeState } from "../../api/botsApi";
import type { BotListItem } from "../../types/bots";

type BulkAction = "START" | "STOP";
type LoadMode = "initial" | "background";

export default function useBotsOverview() {
    const [bots, setBots] = useState<BotListItem[]>([]);
    const [activityByBotId, setActivityByBotId] =
        useState<Record<number, BotDailyActivity>>({});
    const [runtimeByBotId, setRuntimeByBotId] =
        useState<Record<number, BotRuntimeState>>({});
    const [nowMs, setNowMs] = useState(() => Date.now());
    const [isInitialLoading, setIsInitialLoading] = useState(true);
    const [isRefreshing, setIsRefreshing] = useState(false);
    const [actionBotId, setActionBotId] = useState<number | null>(null);
    const [bulkAction, setBulkAction] = useState<BulkAction | null>(null);
    const [pendingBulkAction, setPendingBulkAction] = useState<BulkAction | null>(null);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const [successMessage, setSuccessMessage] = useState<string | null>(null);

    const loadBots = useCallback(async (mode: LoadMode) => {
        if (mode === "initial") {
            setIsInitialLoading(true);
        } else {
            setIsRefreshing(true);
        }
        setErrorMessage(null);

        try {
            const loadedBots = await getBots();
            setBots(loadedBots);

            const supplementaryResults = await getBotsOverview();

            const nextActivityByBotId: Record<number, BotDailyActivity> = {};
            const nextRuntimeByBotId: Record<number, BotRuntimeState> = {};

            for (const result of supplementaryResults) {
                if (result.activity !== null) {
                    nextActivityByBotId[result.botId] = result.activity;
                }
                if (result.runtime !== null) {
                    nextRuntimeByBotId[result.botId] = result.runtime;
                }
            }

            setActivityByBotId(nextActivityByBotId);
            setRuntimeByBotId(nextRuntimeByBotId);
            setNowMs(Date.now());
        } catch (error) {
            setErrorMessage(getErrorMessage(error, "Nie udało się pobrać botów."));
        } finally {
            if (mode === "initial") {
                setIsInitialLoading(false);
            } else {
                setIsRefreshing(false);
            }
        }
    }, []);

    useEffect(() => {
        void loadBots("initial");
    }, [loadBots]);

    useEffect(() => {
        const timer = window.setInterval(() => setNowMs(Date.now()), 15_000);
        return () => window.clearInterval(timer);
    }, []);

    useEffect(() => {
        if (bots.length === 0) {
            return;
        }

        let active = true;
        let inFlight = false;

        const refreshSupplementaryData = async () => {
            if (!active || inFlight || document.hidden) {
                return;
            }
            inFlight = true;
            try {
                const results = await getBotsOverview();

                if (!active) {
                    return;
                }

                setActivityByBotId((previous) => {
                    const next = { ...previous };
                    for (const result of results) {
                        if (result.activity !== null) {
                            next[result.botId] = result.activity;
                        }
                    }
                    return next;
                });

                setRuntimeByBotId((previous) => {
                    const next = { ...previous };
                    for (const result of results) {
                        if (result.runtime !== null) {
                            next[result.botId] = result.runtime;
                        }
                    }
                    return next;
                });
                setNowMs(Date.now());
            } catch (error) {
                console.error("Nie udało się odświeżyć aktywności botów.", error);
            } finally {
                inFlight = false;
            }
        };

        const timer = window.setInterval(() => {
            void refreshSupplementaryData();
        }, 5_000);

        const onVisibility = () => { if (!document.hidden) void refreshSupplementaryData(); };
        document.addEventListener("visibilitychange", onVisibility);
        return () => {
            document.removeEventListener("visibilitychange", onVisibility);
            active = false;
            window.clearInterval(timer);
        };
    }, [bots]);

    const runningBots = useMemo(
        () => bots.filter((bot) => bot.status.toUpperCase() === "RUNNING"),
        [bots],
    );
    const stoppedBots = useMemo(
        () => bots.filter((bot) => bot.status.toUpperCase() !== "RUNNING"),
        [bots],
    );

    const anyActionBusy = actionBotId !== null || bulkAction !== null;

    async function handleStartBot(botId: number) {
        if (anyActionBusy) {
            return;
        }

        setActionBotId(botId);
        setErrorMessage(null);
        setSuccessMessage(null);

        try {
            await startBot(botId);
            await loadBots("background");
        } catch (error) {
            setErrorMessage(getErrorMessage(error, "Nie udało się uruchomić bota."));
        } finally {
            setActionBotId(null);
        }
    }

    async function handleStopBot(botId: number) {
        if (anyActionBusy) {
            return;
        }

        setActionBotId(botId);
        setErrorMessage(null);
        setSuccessMessage(null);

        try {
            await stopBot(botId);
            await loadBots("background");
        } catch (error) {
            setErrorMessage(getErrorMessage(error, "Nie udało się zatrzymać bota."));
        } finally {
            setActionBotId(null);
        }
    }

    async function runBulkAction(action: BulkAction) {
        if (anyActionBusy) {
            return;
        }

        const targets = action === "START" ? stoppedBots : runningBots;
        setPendingBulkAction(null);

        if (targets.length === 0) {
            return;
        }

        setBulkAction(action);
        setErrorMessage(null);
        setSuccessMessage(null);

        try {
            const results = await Promise.allSettled(
                targets.map((bot) => action === "START"
                    ? startBot(bot.id)
                    : stopBot(bot.id)),
            );

            const failedBots = results
                .map((result, index) => result.status === "rejected" ? targets[index] : null)
                .filter((bot): bot is BotListItem => bot !== null);

            await loadBots("background");

            const completedCount = targets.length - failedBots.length;
            const actionLabel = action === "START" ? "uruchomiono" : "zatrzymano";

            if (failedBots.length === 0) {
                setSuccessMessage(
                    `${action === "START" ? "Uruchomiono" : "Zatrzymano"} wszystkie boty (${completedCount}).`,
                );
            } else {
                setErrorMessage(
                    `${action === "START" ? "Nie udało się uruchomić" : "Nie udało się zatrzymać"} ${failedBots.length} z ${targets.length} botów. `
                    + `Poprawnie ${actionLabel}: ${completedCount}. Problem: ${failedBots.map((bot) => bot.name).join(", ")}.`,
                );
            }
        } finally {
            setBulkAction(null);
        }
    }

    const pendingTargets = pendingBulkAction === "START" ? stoppedBots : runningBots;

    return {
        bots, activityByBotId, runtimeByBotId, nowMs, isInitialLoading, isRefreshing,
        actionBotId, bulkAction, pendingBulkAction, setPendingBulkAction, errorMessage,
        successMessage, runningBots, stoppedBots, pendingTargets, anyActionBusy,
        loadBots, handleStartBot, handleStopBot, runBulkAction,
    };
}
