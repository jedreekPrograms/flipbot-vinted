export function AnalyticsKpi({
    label,
    value,
    detail,
    positive = false,
}: {
    label: string;
    value: string;
    detail: string;
    positive?: boolean;
}) {
    return (
        <article className={
            positive
                ? "analytics-kpi analytics-kpi-positive"
                : "analytics-kpi"
        }>
            <span>{label}</span>
            <strong>{value}</strong>
            <small>{detail}</small>
        </article>
    );
}

export function Metric({
    label,
    value,
}: {
    label: string;
    value: string;
}) {
    return (
        <div className="analytics-metric">
            <span>{label}</span>
            <strong>{value}</strong>
        </div>
    );
}
