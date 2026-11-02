import { useEffect } from "react";

/** Background refreshes pause off-screen; foreground actions call the API directly. */
export function useVisiblePolling(refresh: () => Promise<void>, intervalMs: number) {
    useEffect(() => {
        let active = true;
        let inFlight = false;
        const tick = async () => {
            if (!active || document.hidden || inFlight) return;
            inFlight = true;
            try { await refresh(); }
            finally { inFlight = false; }
        };
        const timer = window.setInterval(() => { void tick(); }, intervalMs);
        const onVisibilityChange = () => { void tick(); };
        document.addEventListener("visibilitychange", onVisibilityChange);
        return () => {
            active = false;
            window.clearInterval(timer);
            document.removeEventListener("visibilitychange", onVisibilityChange);
        };
    }, [refresh, intervalMs]);
}
