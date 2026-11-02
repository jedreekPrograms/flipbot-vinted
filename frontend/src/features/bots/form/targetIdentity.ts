import type { TargetMode } from "../../../types/bots";

export function resolveTargetMode(target: { targetMode: TargetMode | null; searchQuery: string | null }): TargetMode {
    if (target.targetMode !== null) return target.targetMode;
    return target.searchQuery !== null && target.searchQuery.trim().length > 0 ? "SEARCH_QUERY" : "VINTED_MODEL";
}

export function categoryPathsEqual(left: string[], right: string[]): boolean {
    return left.length === right.length
        && left.every((element, index) => normalizedText(element) === normalizedText(right[index] ?? ""));
}

export function normalizedText(value: string): string {
    return value.trim().replace(/\s+/g, " ").toLowerCase();
}
