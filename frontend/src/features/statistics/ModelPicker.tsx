import { useMemo, useState } from "react";
import type { AnalyticsModelOption } from "../../api/analyticsApi";

function ModelPicker({
    models,
    selectedIds,
    disabled,
    onChange,
}: {
    models: AnalyticsModelOption[];
    selectedIds: number[];
    disabled: boolean;
    onChange: (ids: number[]) => void;
}) {
    const [query, setQuery] = useState("");

    const visibleModels = useMemo(() => {
        const normalized = query.trim().toLowerCase();

        if (normalized.length === 0) {
            return models;
        }

        return models.filter(model =>
            `${model.brand} ${model.model}`.toLowerCase().includes(normalized),
        );
    }, [models, query]);

    function toggle(id: number) {
        onChange(
            selectedIds.includes(id)
                ? selectedIds.filter(value => value !== id)
                : [...selectedIds, id],
        );
    }

    return (
        <div className="analytics-model-picker">
            <div className="analytics-model-picker-heading">
                <div>
                    <span>Modele</span>
                    <strong>
                        {selectedIds.length === 0
                            ? "Wszystkie modele"
                            : `Wybrano: ${selectedIds.length}`}
                    </strong>
                </div>
                {selectedIds.length > 0 && (
                    <button
                        className="analytics-clear-models"
                        type="button"
                        disabled={disabled}
                        onClick={() => onChange([])}
                    >
                        Wyczyść
                    </button>
                )}
            </div>

            <input
                className="form-input analytics-model-search"
                type="search"
                placeholder="Szukaj modelu, np. S26 albo iPad..."
                value={query}
                disabled={disabled}
                onChange={event => setQuery(event.target.value)}
            />

            <div className="analytics-model-options">
                {visibleModels.map(model => {
                    const active = selectedIds.includes(model.modelId);

                    return (
                        <button
                            key={model.modelId}
                            className={
                                active
                                    ? "analytics-model-option analytics-model-option-active"
                                    : "analytics-model-option"
                            }
                            type="button"
                            disabled={disabled}
                            onClick={() => toggle(model.modelId)}
                        >
                            <span>{model.brand}</span>
                            <strong>{model.model}</strong>
                        </button>
                    );
                })}
            </div>
        </div>
    );
}

export default ModelPicker;
