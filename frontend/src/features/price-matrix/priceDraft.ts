import type { DictionaryModel } from "../../types/dictionaries";

export interface PriceDraft {
    proposedOfferPrice: string;
    expectedResalePrice: string;
    marketMinPrice: string;
    marketMaxPrice: string;
}

export function emptyPriceDraft(): PriceDraft {
    return {
        proposedOfferPrice: "",
        expectedResalePrice: "",
        marketMinPrice: "",
        marketMaxPrice: "",
    };
}
interface ParsedPriceSuccess {
    valid: true;
    value: number | null;
}

interface ParsedPriceFailure {
    valid: false;
    errorMessage: string;
}

function parseOptionalPositivePrice(
    rawValue: string,
    errorMessage: string,
): ParsedPriceSuccess | ParsedPriceFailure {
    const normalized = rawValue.trim().replace(",", ".");

    if (normalized.length === 0) {
        return { valid: true, value: null };
    }

    const value = Number(normalized);

    if (!Number.isFinite(value) || value <= 0) {
        return { valid: false, errorMessage };
    }

    return { valid: true, value };
}

export function draftFromModel(model: DictionaryModel): PriceDraft {
    return {
        proposedOfferPrice: formatInputPrice(model.proposedOfferPrice),
        expectedResalePrice: formatInputPrice(model.expectedResalePrice),
        marketMinPrice: formatInputPrice(model.marketMinPrice),
        marketMaxPrice: formatInputPrice(model.marketMaxPrice),
    };
}

function formatInputPrice(value: number | null): string {
    return value === null ? "" : String(value);
}

export function samePrice(left: number | null, right: number | null): boolean {
    if (left === null || right === null) {
        return left === right;
    }

    return Math.abs(left - right) < 0.0001;
}

const priceFields = [
    { field: "proposedOfferPrice", label: "Proponowana cena", placeholder: "—", error: "Proponowana cena dla bota musi być większa od 0." },
    { field: "expectedResalePrice", label: "Sprzedaż", placeholder: "—", error: "Cena sprzedaży musi być większa od 0." },
    { field: "marketMinPrice", label: "Min obserwacji", placeholder: "bez min", error: "Minimalna cena obserwacji musi być większa od 0." },
    { field: "marketMaxPrice", label: "Max obserwacji", placeholder: "bez max", error: "Maksymalna cena obserwacji musi być większa od 0." },
] as const;

export { priceFields };

export function parsePriceDraft(draft: PriceDraft) {
    const values = {} as Record<keyof PriceDraft, number | null>;
    for (const { field, error } of priceFields) {
        const result = parseOptionalPositivePrice(draft[field], error);
        if (!result.valid) return result;
        values[field] = result.value;
    }
    if (values.marketMinPrice !== null && values.marketMaxPrice !== null
        && values.marketMinPrice > values.marketMaxPrice) {
        return { valid: false as const, errorMessage: "Minimalna cena obserwacji nie może być większa od maksymalnej." };
    }
    return { valid: true as const, value: values };
}
