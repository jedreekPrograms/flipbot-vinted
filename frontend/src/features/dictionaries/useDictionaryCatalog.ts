import { useCallback, useEffect, useRef, useState, type SetStateAction } from "react";
import { getBrands, getCategories, getModelsByBrand } from "../../api/dictionariesApi";
import { useLatestRequest } from "../../shared/useLatestRequest";
import { getErrorMessage } from "../../shared/errorMessage";
import type { DictionaryBrand, DictionaryCategory, DictionaryModel } from "../../types/dictionaries";

export function keepDictionarySelection(items: readonly { id: number }[], current: string): string {
    if (items.some(item => String(item.id) === current)) return current;
    return items.length > 0 ? String(items[0].id) : "";
}

/** Shared administration data; product forms retain their separate loading rules. */
export function useDictionaryCatalog(onCategoriesLoaded?: (categories: DictionaryCategory[]) => void) {
    const [brands, setBrands] = useState<DictionaryBrand[]>([]);
    const [categories, setCategories] = useState<DictionaryCategory[]>([]);
    const [models, setModels] = useState<DictionaryModel[]>([]);
    const [selectedBrandId, updateSelectedBrandId] = useState("");
    const selectedBrand = useRef("");
    const baseRequests = useLatestRequest();
    const modelRequests = useLatestRequest();
    const setSelectedBrandId = useCallback((value: SetStateAction<string>) => {
        const next = typeof value === "function" ? value(selectedBrand.current) : value;
        if (next !== selectedBrand.current) modelRequests.invalidate();
        selectedBrand.current = next;
        updateSelectedBrandId(next);
    }, [modelRequests]);
    const [isLoading, setIsLoading] = useState(true);
    const [isModelsLoading, setIsModelsLoading] = useState(false);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);

    const loadBaseData = useCallback(async () => {
        const request = baseRequests.start();
        setIsLoading(true);
        setErrorMessage(null);
        try {
            const [loadedBrands, loadedCategories] = await Promise.all([getBrands(), getCategories()]);
            if (!baseRequests.isCurrent(request)) return;
            const sortedBrands = [...loadedBrands].sort((left, right) => left.name.localeCompare(right.name, "pl"));
            const sortedCategories = [...loadedCategories].sort((left, right) => left.path.localeCompare(right.path, "pl"));
            setBrands(sortedBrands);
            setCategories(sortedCategories);
            setSelectedBrandId(current => keepDictionarySelection(sortedBrands, current));
            onCategoriesLoaded?.(sortedCategories);
        } catch (error) {
            if (baseRequests.isCurrent(request)) {
                setErrorMessage(getErrorMessage(error, "Nie udało się pobrać słowników."));
            }
        } finally {
            if (baseRequests.isCurrent(request)) setIsLoading(false);
            baseRequests.finish(request);
        }
    }, [onCategoriesLoaded, baseRequests, setSelectedBrandId]);

    const loadModels = useCallback(async (brandId: number) => {
        if (String(brandId) !== selectedBrand.current) return;
        const request = modelRequests.start();
        setIsModelsLoading(true);
        try {
            const loadedModels = await getModelsByBrand(brandId);
            if (!modelRequests.isCurrent(request)) return;
            setModels([...loadedModels].sort((left, right) => left.name.localeCompare(right.name, "pl")));
        } catch (error) {
            if (modelRequests.isCurrent(request)) {
                setModels([]);
                setErrorMessage(getErrorMessage(error, "Nie udało się pobrać modeli."));
            }
        } finally {
            if (modelRequests.isCurrent(request)) setIsModelsLoading(false);
            modelRequests.finish(request);
        }
    }, [modelRequests]);

    useEffect(() => { void loadBaseData(); }, [loadBaseData]);
    useEffect(() => {
        if (selectedBrandId.length === 0) {
            modelRequests.invalidate();
            setIsModelsLoading(false);
            setModels([]);
            return;
        }
        void loadModels(Number(selectedBrandId));
    }, [selectedBrandId, loadModels, modelRequests]);

    return {
        brands, categories, models, selectedBrandId, setSelectedBrandId,
        isLoading, isModelsLoading, errorMessage, setErrorMessage, loadBaseData, loadModels,
    };
}
