import type { DictionaryBrand, DictionaryModel } from "../../types/dictionaries";
import type { ModelPlanning } from "../../types/marketStats";
import { emptyPriceDraft, priceFields, type PriceDraft } from "./priceDraft";
import { TodayMetricCell, CurrentWeekMetricCell, PreviousFullWeekMetricCell, RecommendedBotsMetricCell } from "./PlanningMetrics";
import PriceInput from "./PriceInput";

interface BrandPriceSheetProps {
    brand: DictionaryBrand;
    models: DictionaryModel[];
    planningByModel: Record<number, ModelPlanning>;
    drafts: Record<number, PriceDraft>;
    expanded: boolean;
    savingModelId: number | null;
    savedModelId: number | null;
    onToggle: () => void;
    onDraftChange: (
        modelId: number,
        field: keyof PriceDraft,
        value: string,
    ) => void;
    onSave: (model: DictionaryModel) => Promise<void>;
}

function BrandPriceSheet({
    brand,
    models,
    planningByModel,
    drafts,
    expanded,
    savingModelId,
    savedModelId,
    onToggle,
    onDraftChange,
    onSave,
}: BrandPriceSheetProps) {
    return (
        <section className="price-brand-sheet">
            <button
                className="price-brand-title"
                type="button"
                aria-expanded={expanded}
                onClick={onToggle}
            >
                <span className="price-brand-heading">
                    <span className="price-brand-name">{brand.name}</span>
                    <span className="price-brand-count">
                        {formatModelCount(models.length)}
                    </span>
                </span>
                <span
                    className={`price-brand-chevron ${
                        expanded ? "price-brand-chevron-open" : ""
                    }`}
                    aria-hidden="true"
                >
                    ▾
                </span>
            </button>

            {expanded && (
                <div className="price-brand-content">
                    <div className="price-sheet-row price-sheet-header">
                        <div>Model</div>
                        <div>Proponowana cena</div>
                        <div>Sprzedaż</div>
                        <div>Min obserwacji</div>
                        <div>Max obserwacji</div>
                        <div>Dzisiaj</div>
                        <div>Ten tydzień</div>
                        <div>Ostatni pełny tydzień</div>
                        <div>Potrzebne boty</div>
                        <div>Posiadane boty</div>
                    </div>

                    {models.length === 0 ? (
                        <div className="price-sheet-empty">Brak modeli</div>
                    ) : models.map((model) => {
                        const draft = drafts[model.id] ?? emptyPriceDraft();
                        const planning = planningByModel[model.id];

                        return (
                            <div className="price-sheet-row" key={model.id}>
                                <div className="price-model-cell">
                                    <strong>{model.name}</strong>
                                    <span>
                                        {model.targetMode === "SEARCH_QUERY"
                                            ? "Wyszukiwarka"
                                            : "Filtr Vinted"}
                                    </span>
                                </div>

                                {priceFields.map(({ field, label, placeholder }) => (
                                    <PriceInput
                                        key={field}
                                        label={label}
                                        placeholder={placeholder}
                                        value={draft[field]}
                                        disabled={savingModelId !== null && savingModelId !== model.id}
                                        saving={savingModelId === model.id}
                                        saved={savedModelId === model.id}
                                        onChange={value => onDraftChange(model.id, field, value)}
                                        onBlur={() => void onSave(model)}
                                    />
                                ))}

                                <TodayMetricCell planning={planning} />
                                <CurrentWeekMetricCell planning={planning} />
                                <PreviousFullWeekMetricCell planning={planning} />
                                <RecommendedBotsMetricCell planning={planning} />

                                <div
                                    className="price-metric-cell"
                                    data-label="Posiadane boty"
                                >
                                    <strong>{planning?.existingBots ?? 0}</strong>
                                    <span>utworzonych</span>
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}
        </section>
    );
}

function formatModelCount(count: number): string {
    if (count === 1) {
        return "1 model";
    }
    if (count >= 2 && count <= 4) {
        return `${count} modele`;
    }
    return `${count} modeli`;
}

export default BrandPriceSheet;
