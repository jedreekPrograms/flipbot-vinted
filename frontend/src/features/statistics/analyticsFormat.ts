export function formatObserverRange(
    min: number | null,
    max: number | null,
): string {
    if (min === null && max === null) {
        return "bez ograniczenia ceny";
    }
    if (min === null) {
        return `do ${formatPrice(max as number)}`;
    }
    if (max === null) {
        return `od ${formatPrice(min)}`;
    }
    return `${formatPrice(min)}–${formatPrice(max)}`;
}

export function formatNullablePrice(value: number | null): string {
    return value === null ? "—" : formatPrice(value);
}

export function formatNullableSignedPrice(value: number | null): string {
    if (value === null) {
        return "—";
    }

    return `${value > 0 ? "+" : ""}${formatPrice(value)}`;
}

export function formatNullablePercent(value: number | null): string {
    if (value === null) {
        return "Brak porównania";
    }

    return `${value > 0 ? "+" : ""}${formatNumber(value, 1)}%`;
}

export function formatNullableRate(value: number | null): string {
    return value === null
        ? "—"
        : formatNumber(value, value < 10 ? 2 : 1);
}

export function formatPrice(value: number): string {
    return new Intl.NumberFormat("pl-PL", {
        style: "currency",
        currency: "PLN",
        maximumFractionDigits: 2,
    }).format(value);
}

export function formatCompactPrice(value: number): string {
    return new Intl.NumberFormat("pl-PL", {
        maximumFractionDigits: 0,
    }).format(value);
}

export function formatNumber(value: number, digits: number): string {
    return new Intl.NumberFormat("pl-PL", {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
    }).format(value);
}
