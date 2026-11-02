(() => {
    const normalizedTarget = (target) =>
        String(target ?? "").trim().toLowerCase();

    const opensNewBrowsingContext = (target) => {
        const normalized = normalizedTarget(target);

        if (!normalized || normalized === "_self") {
            return false;
        }

        return normalized !== "_top"
            && normalized !== "_parent";
    };

    const effectiveTarget = (element) => {
        const ownTarget = normalizedTarget(element?.target);
        if (ownTarget) {
            return ownTarget;
        }

        const base = document.querySelector("base[target]");
        return normalizedTarget(base?.getAttribute("target"));
    };

    const blockedWindowOpen = () => null;

    try {
        Object.defineProperty(window, "open", {
            value: blockedWindowOpen,
            writable: false,
            configurable: false
        });
    } catch (_) {
        try {
            window.open = blockedWindowOpen;
        } catch (_) {
            // BrowserContext.onPage remains the fail-safe.
        }
    }

    const blockNewContextClick = (event) => {
        const path = typeof event.composedPath === "function"
            ? event.composedPath()
            : [];

        for (const node of path) {
            const isLink = node instanceof HTMLAnchorElement
                || node instanceof HTMLAreaElement;

            if (isLink
                    && opensNewBrowsingContext(
                        effectiveTarget(node)
                    )) {
                event.preventDefault();
                event.stopImmediatePropagation();
                return;
            }
        }
    };

    document.addEventListener("click", blockNewContextClick, true);
    document.addEventListener("auxclick", blockNewContextClick, true);

    document.addEventListener("submit", (event) => {
        const form = event.target;

        if (form instanceof HTMLFormElement
                && opensNewBrowsingContext(
                    effectiveTarget(form)
                )) {
            event.preventDefault();
            event.stopImmediatePropagation();
        }
    }, true);

    /*
     * form.submit() bypasses the submit event entirely. RTB/ad
     * scripts can use it to create a popup even when the capture
     * listener above is present, so guard the imperative APIs too.
     */
    try {
        const nativeFormSubmit =
            HTMLFormElement.prototype.submit;

        HTMLFormElement.prototype.submit =
            function(...args) {
                if (opensNewBrowsingContext(
                        effectiveTarget(this)
                )) {
                    return;
                }

                return nativeFormSubmit.apply(this, args);
            };
    } catch (_) {
        // Capture listener + onPage remain fail-safes.
    }

    try {
        const nativeRequestSubmit =
            HTMLFormElement.prototype.requestSubmit;

        if (typeof nativeRequestSubmit === "function") {
            HTMLFormElement.prototype.requestSubmit =
                function(...args) {
                    if (opensNewBrowsingContext(
                            effectiveTarget(this)
                    )) {
                        return;
                    }

                    return nativeRequestSubmit.apply(this, args);
                };
        }
    } catch (_) {
        // Capture listener + onPage remain fail-safes.
    }
})();
