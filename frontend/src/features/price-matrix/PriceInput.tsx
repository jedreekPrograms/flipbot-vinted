interface PriceInputProps {
    label: string;
    placeholder: string;
    value: string;
    disabled: boolean;
    saving: boolean;
    saved: boolean;
    onChange: (value: string) => void;
    onBlur: () => void;
}

function PriceInput({
    label,
    placeholder,
    value,
    disabled,
    saving,
    saved,
    onChange,
    onBlur,
}: PriceInputProps) {
    return (
        <div className="price-input-cell" data-label={label}>
            <div className="price-input-wrapper">
                <input
                    className="price-matrix-input"
                    type="number"
                    min="0.01"
                    step="0.01"
                    value={value}
                    disabled={disabled || saving}
                    placeholder={placeholder}
                    aria-label={label}
                    onChange={(event) => onChange(event.target.value)}
                    onBlur={onBlur}
                    onKeyDown={(event) => {
                        if (event.key === "Enter") {
                            event.currentTarget.blur();
                        }
                    }}
                />
                {value.trim().length > 0 && (
                    <span className="price-currency">zł</span>
                )}
            </div>
            {saving && (
                <span className="price-save-state">Zapisywanie...</span>
            )}
            {!saving && saved && (
                <span className="price-save-state">Zapisano</span>
            )}
        </div>
    );
}

export default PriceInput;
