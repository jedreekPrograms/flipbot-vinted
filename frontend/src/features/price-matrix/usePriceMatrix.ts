import { useLatestRequest } from "../../shared/useLatestRequest";
import { useVisiblePolling } from "../../shared/useVisiblePolling";
import { getErrorMessage } from "../../shared/errorMessage";
import { useCallback, useEffect, useMemo, useState } from "react";
import { getBrands, getModelsByBrand, updateModelPricing } from "../../api/dictionariesApi";
import { getModelPlanning } from "../../api/marketStatsApi";
import type { DictionaryBrand, DictionaryModel } from "../../types/dictionaries";
import type { ModelPlanning } from "../../types/marketStats";
import { draftFromModel, emptyPriceDraft, parsePriceDraft, samePrice, type PriceDraft } from "./priceDraft";

const PLANNING_REFRESH_INTERVAL_MS = 15_000;

export default function usePriceMatrix() {
    const [brands, setBrands] = useState<DictionaryBrand[]>([]);
    const [modelsByBrand, setModelsByBrand] =
        useState<Record<number, DictionaryModel[]>>({});
    const [planningByModel, setPlanningByModel] =
        useState<Record<number, ModelPlanning>>({});
    const [drafts, setDrafts] =
        useState<Record<number, PriceDraft>>({});
    const [expandedBrandIds, setExpandedBrandIds] =
        useState<Set<number>>(new Set());
    const [savingModelId, setSavingModelId] = useState<number | null>(null);
    const [savedModelId, setSavedModelId] = useState<number | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);

    const catalogRequests = useLatestRequest();
    const planningRequests = useLatestRequest();

    const loadData = useCallback(async () => {
        const request = catalogRequests.start();
        const planningRequest = planningRequests.start();
        setIsLoading(true);
        setErrorMessage(null);

        try {
            const [loadedBrandsRaw, planning] = await Promise.all([
                getBrands(),
                getModelPlanning(),
            ]);
            if (!catalogRequests.isCurrent(request)) return;
            const loadedBrands = [...loadedBrandsRaw].sort((left, right) =>
                left.name.localeCompare(right.name, "pl"),
            );
            const modelGroups = await Promise.all(
                loadedBrands.map(async (brand) => ({
                    brandId: brand.id,
                    models: [...await getModelsByBrand(brand.id)].sort((left, right) =>
                        left.name.localeCompare(right.name, "pl"),
                    ),
                })),
            );

            if (!catalogRequests.isCurrent(request)) return;
            const nextModelsByBrand: Record<number, DictionaryModel[]> = {};
            const nextDrafts: Record<number, PriceDraft> = {};
            const nextPlanningByModel: Record<number, ModelPlanning> = {};

            for (const item of planning) {
                nextPlanningByModel[item.modelId] = item;
            }

            for (const group of modelGroups) {
                nextModelsByBrand[group.brandId] = group.models;
                for (const model of group.models) {
                    nextDrafts[model.id] = draftFromModel(model);
                }
            }

            setBrands(loadedBrands);
            setModelsByBrand(nextModelsByBrand);
            if (planningRequests.isCurrent(planningRequest)) setPlanningByModel(nextPlanningByModel);
            setDrafts(nextDrafts);
            setExpandedBrandIds(new Set(loadedBrands.map((brand) => brand.id)));
        } catch (error) {
            if (catalogRequests.isCurrent(request)) {
                setErrorMessage(getErrorMessage(error, "Nie udało się pobrać cennika modeli."));
            }
        } finally {
            if (catalogRequests.isCurrent(request)) setIsLoading(false);
            catalogRequests.finish(request);
            planningRequests.finish(planningRequest);
        }
    }, [catalogRequests, planningRequests]);

    const refreshPlanning = useCallback(async () => {
        const request = planningRequests.start();
        try {
            const planning = await getModelPlanning();
            const nextPlanningByModel: Record<number, ModelPlanning> = {};

            for (const item of planning) {
                nextPlanningByModel[item.modelId] = item;
            }

            if (planningRequests.isCurrent(request)) setPlanningByModel(nextPlanningByModel);
        } catch (error) {
            if (!planningRequests.isCurrent(request)) return;
            console.error(
                "Nie udało się automatycznie odświeżyć statystyk rynku.",
                error,
            );
        } finally {
            planningRequests.finish(request);
        }
    }, [planningRequests]);

    useEffect(() => {
        void loadData();
    }, [loadData]);

    const pollPlanning = useCallback(async () => {
        if (!catalogRequests.pending && !planningRequests.pending) await refreshPlanning();
    }, [catalogRequests, planningRequests, refreshPlanning]);
    useVisiblePolling(pollPlanning, PLANNING_REFRESH_INTERVAL_MS);

    const modelCount = useMemo(
        () => Object.values(modelsByBrand)
            .reduce((sum, models) => sum + models.length, 0),
        [modelsByBrand],
    );

    function toggleBrand(brandId: number) {
        setExpandedBrandIds((current) => {
            const next = new Set(current);

            if (next.has(brandId)) {
                next.delete(brandId);
            } else {
                next.add(brandId);
            }

            return next;
        });
    }

    function updateDraft(
        modelId: number,
        field: keyof PriceDraft,
        value: string,
    ) {
        setDrafts((current) => ({
            ...current,
            [modelId]: {
                ...(current[modelId] ?? emptyPriceDraft()),
                [field]: value,
            },
        }));
        setSavedModelId(null);
    }

    async function saveModel(model: DictionaryModel) {
        const draft = drafts[model.id];

        if (draft === undefined || savingModelId !== null) {
            return;
        }

        const parsed = parsePriceDraft(draft);
        if (!parsed.valid) {
            setErrorMessage(parsed.errorMessage);
            return;
        }
        const prices = parsed.value;

        const marketRangeChanged =
            !samePrice(model.marketMinPrice, prices.marketMinPrice)
            || !samePrice(model.marketMaxPrice, prices.marketMaxPrice);

        if (
            samePrice(model.proposedOfferPrice, prices.proposedOfferPrice)
            && samePrice(model.expectedResalePrice, prices.expectedResalePrice)
            && !marketRangeChanged
        ) {
            return;
        }

        setSavingModelId(model.id);
        setSavedModelId(null);
        setErrorMessage(null);

        try {
            const updated = await updateModelPricing(
                model.brandId,
                model.id,
                prices,
            );

            if (!catalogRequests.active) return;
            setModelsByBrand((current) => ({
                ...current,
                [model.brandId]: (current[model.brandId] ?? []).map((item) =>
                    item.id === updated.id ? updated : item,
                ),
            }));
            setDrafts((current) => ({
                ...current,
                [updated.id]: draftFromModel(updated),
            }));
            setSavedModelId(updated.id);

            if (marketRangeChanged) {
                await refreshPlanning();
            }
        } catch (error) {
            if (!catalogRequests.active) return;
            setErrorMessage(
                getErrorMessage(error, "Nie udało się zapisać cen modelu."),
            );
        } finally {
            if (catalogRequests.active) setSavingModelId(null);
        }
    }

    return { brands, modelsByBrand, planningByModel, drafts, expandedBrandIds, savingModelId, savedModelId,
        isLoading, errorMessage, modelCount, toggleBrand, updateDraft, saveModel };
}
