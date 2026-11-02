function FilterPills<T extends string>({
    title,
    allLabel,
    allMode = true,
    options,
    selected,
    disabled,
    onChange,
    onSingleChange,
}: {
    title: string;
    allLabel?: string;
    allMode?: boolean;
    options: Array<{ value: T; label: string }>;
    selected: T[];
    disabled: boolean;
    onChange?: (value: T[]) => void;
    onSingleChange?: (value: T) => void;
}) {
    function toggle(value: T) {
        if (!allMode) {
            onSingleChange?.(value);
            return;
        }

        onChange?.(
            selected.includes(value)
                ? selected.filter(item => item !== value)
                : [...selected, value],
        );
    }

    return (
        <div className="analytics-pill-group">
            <span>{title}</span>
            <div>
                {allMode && (
                    <button
                        className={selected.length === 0
                            ? "analytics-pill analytics-pill-active"
                            : "analytics-pill"}
                        type="button"
                        disabled={disabled}
                        onClick={() => onChange?.([])}
                    >
                        {allLabel ?? "Wszystkie"}
                    </button>
                )}

                {options.map(option => (
                    <button
                        key={option.value}
                        className={selected.includes(option.value)
                            ? "analytics-pill analytics-pill-active"
                            : "analytics-pill"}
                        type="button"
                        disabled={disabled}
                        onClick={() => toggle(option.value)}
                    >
                        {option.label}
                    </button>
                ))}
            </div>
        </div>
    );
}

export default FilterPills;
