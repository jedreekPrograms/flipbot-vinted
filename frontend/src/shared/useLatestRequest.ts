import { useEffect, useState } from "react";

/** Owns response writes, including errors/loading, without queuing user actions. */
export class LatestRequest {
    private version = 0;
    private inFlight = new Set<number>();
    private enabled = true;

    get active() { return this.enabled; }
    activate() { this.enabled = true; }
    dispose() { this.enabled = false; this.invalidate(); }

    get pending() { return this.inFlight.size > 0; }
    start() {
        const request = ++this.version;
        this.inFlight.add(request);
        return request;
    }
    isCurrent(request: number) { return this.active && request === this.version; }
    finish(request: number) { this.inFlight.delete(request); }
    invalidate() { ++this.version; }
}

export function useLatestRequest() {
    const [requests] = useState(() => new LatestRequest());
    useEffect(() => {
        requests.activate();
        return () => requests.dispose();
    }, [requests]);
    return requests;
}
