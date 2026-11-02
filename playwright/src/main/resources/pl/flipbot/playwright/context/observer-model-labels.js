(() => {
    // UI controls belong to the main Vinted document, never ad or challenge frames.
    if (window.top !== window
            || !/(^|\.)vinted\.pl$/.test(location.hostname)) {
        return;
    }
    const MODEL_PREFIX = "selectable-item-brand_collection-";
    const MODEL_SELECTOR = `[data-testid^="${MODEL_PREFIX}"]`;
    const normalize = (value) =>
        String(value ?? "")
            .replace(/\s+/g, " ")
            .trim();

    const isVisible = (element) => {
        if (!(element instanceof Element)) {
            return false;
        }

        const style = window.getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return style.display !== "none"
            && style.visibility !== "hidden"
            && Number(style.opacity || "1") > 0
            && rect.width > 0
            && rect.height > 0;
    };

    const collectionIdFromTestId = (testId) => {
        const match = /^selectable-item-brand_collection-(\d+)(?:--title)?$/
            .exec(String(testId ?? "").trim());
        return match ? match[1] : null;
    };

    const readableText = (element) => {
        if (!(element instanceof Element) || !isVisible(element)) {
            return "";
        }

        return normalize(
            element.innerText
                || element.textContent
                || element.getAttribute("aria-label")
                || element.getAttribute("title")
        );
    };

    const uniqueCollectionIdsInside = (element) => {
        const ids = new Set();

        if (!(element instanceof Element)) {
            return ids;
        }

        const ownId = collectionIdFromTestId(
            element.getAttribute("data-testid")
        );
        if (ownId) {
            ids.add(ownId);
        }

        for (const candidate of element.querySelectorAll(MODEL_SELECTOR)) {
            const id = collectionIdFromTestId(
                candidate.getAttribute("data-testid")
            );
            if (id) {
                ids.add(id);
            }
        }

        return ids;
    };

    const looksLikeWholeFilterPanel = (text) => {
        const lower = normalize(text).toLowerCase();
        return lower.includes("pokaż wyniki")
            || lower.includes("pokaz wyniki")
            || lower.includes("show results")
            || lower.includes("wyczyść filtry")
            || lower.includes("wyczysc filtry")
            || lower.includes("clear filters");
    };

    const resolveAssociatedModelText = (candidate, collectionId) => {
        const directTitle = document.querySelector(
            `[data-testid="${MODEL_PREFIX}${collectionId}--title"]`
        );
        const directTitleText = readableText(directTitle);
        if (directTitleText) {
            return directTitleText;
        }

        let current = candidate.parentElement;

        for (let depth = 0; current && depth < 7; depth++) {
            const ids = uniqueCollectionIdsInside(current);

            if (ids.size === 1 && ids.has(collectionId)) {
                const leafTexts = [];

                for (const leaf of current.querySelectorAll("*")) {
                    if (leaf.children.length !== 0 || !isVisible(leaf)) {
                        continue;
                    }

                    const text = readableText(leaf);
                    if (text && !leafTexts.includes(text)) {
                        leafTexts.push(text);
                    }
                }

                const joinedLeaves = normalize(leafTexts.join("\n"));
                if (joinedLeaves
                        && joinedLeaves.length <= 220
                        && !looksLikeWholeFilterPanel(joinedLeaves)) {
                    return joinedLeaves;
                }

                const wholeText = readableText(current);
                if (wholeText
                        && wholeText.length <= 220
                        && !looksLikeWholeFilterPanel(wholeText)) {
                    return wholeText;
                }
            }

            current = current.parentElement;
        }

        return "";
    };

    const exposeModelLabels = () => {
        const candidates = Array.from(
            document.querySelectorAll(MODEL_SELECTOR)
        );

        for (const candidate of candidates) {
            const collectionId = collectionIdFromTestId(
                candidate.getAttribute("data-testid")
            );
            if (!collectionId) {
                continue;
            }

            const ownReadableText = normalize(
                candidate.innerText
                    || candidate.textContent
                    || candidate.getAttribute("aria-label")
                    || candidate.getAttribute("title")
            );
            if (ownReadableText) {
                continue;
            }

            const associatedText = resolveAssociatedModelText(
                candidate,
                collectionId
            );

            if (associatedText) {
                candidate.setAttribute("title", associatedText);
            }
        }
    };

    let scheduled = false;
    const stabilize = () => {
        if (scheduled) {
            return;
        }

        scheduled = true;
        setTimeout(() => {
            scheduled = false;
            exposeModelLabels();
        }, 150);
    };

    stabilize();

    const observer = new MutationObserver(stabilize);
    observer.observe(document, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: [
            "class",
            "style",
            "aria-hidden",
            "disabled"
        ]
    });
})();
