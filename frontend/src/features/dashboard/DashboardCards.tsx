import type { DashboardStat } from "./dashboardSummary";

export function DashboardFilterGroup<T extends string>({
    title,
    allLabel,
    options,
    selected,
    disabled,
    onChange,
}: {
    title: string;
    allLabel: string;
    options: Array<{ value: T; label: string }>;
    selected: T[];
    disabled: boolean;
    onChange: (value: T[]) => void;
}) {
    function toggle(value: T) {
        onChange(
            selected.includes(value)
                ? selected.filter(item => item !== value)
                : [...selected, value],
        );
    }

    return (
        <div className="dashboard-filter-group">
            <span>{title}</span>
            <div className="dashboard-period-switch">
                <button
                    className={
                        selected.length === 0
                            ? "dashboard-period-button dashboard-period-button-active"
                            : "dashboard-period-button"
                    }
                    type="button"
                    disabled={disabled}
                    onClick={() => onChange([])}
                >
                    {allLabel}
                </button>

                {options.map(option => (
                    <button
                        key={option.value}
                        className={
                            selected.includes(option.value)
                                ? "dashboard-period-button dashboard-period-button-active"
                                : "dashboard-period-button"
                        }
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

export function StatCard({
    stat,
}: {
    stat: DashboardStat;
}) {
    return (
        <article
            className={
                [
                    "stat-card",
                    stat.variant !== undefined
                        ? `stat-card-${stat.variant}`
                        : "",
                ]
                    .filter(Boolean)
                    .join(" ")
            }
        >
            <div className="stat-label">{stat.label}</div>
            <div className="stat-value">{stat.value}</div>
            <div className="stat-description">{stat.description}</div>
        </article>
    );
}
