import type { AnalyticsSource, AnalyticsTimelinePoint } from "../../api/analyticsApi";
import type { PriceSeries } from "./analyticsOptions";
import { formatPrice } from "./analyticsFormat";
import EmptyChart from "./EmptyChart";

function PriceTimelineChart({
    points,
    selectedSeries,
    source,
}: {
    points: AnalyticsTimelinePoint[];
    selectedSeries: PriceSeries[];
    source: AnalyticsSource;
}) {
    const series = [
        {
            id: "MARKET_MEDIAN" as const,
            label: "Mediana rynku",
            className: "analytics-line-market",
            dotClass: "analytics-dot-market",
            value: (point: AnalyticsTimelinePoint) => point.medianMarketPrice,
            sampleCount: (point: AnalyticsTimelinePoint) => point.marketPriceSampleCount,
        },
        {
            id: "MARKET_AVERAGE" as const,
            label: "Średnia rynku",
            className: "analytics-line-market-average",
            dotClass: "analytics-dot-market-average",
            value: (point: AnalyticsTimelinePoint) => point.averageMarketPrice,
            sampleCount: (point: AnalyticsTimelinePoint) => point.marketPriceSampleCount,
        },
        {
            id: "PURCHASE_AVERAGE" as const,
            label: "Średnia zakupu",
            className: "analytics-line-purchase",
            dotClass: "analytics-dot-purchase",
            value: (point: AnalyticsTimelinePoint) => point.averagePurchasePrice,
            sampleCount: (point: AnalyticsTimelinePoint) => point.purchasePriceSampleCount,
        },
        {
            id: "PURCHASE_MEDIAN" as const,
            label: "Mediana zakupu",
            className: "analytics-line-purchase-median",
            dotClass: "analytics-dot-purchase-median",
            value: (point: AnalyticsTimelinePoint) => point.medianPurchasePrice,
            sampleCount: (point: AnalyticsTimelinePoint) => point.purchasePriceSampleCount,
        },
    ].filter(item => {
        const sourceAllowed = source === "OBSERVER"
            ? item.id.startsWith("MARKET")
            : source === "HISTORY"
                ? item.id.startsWith("PURCHASE")
                : true;

        return sourceAllowed && (
            selectedSeries.length === 0
            || selectedSeries.includes(item.id)
        );
    });

    const usableValues = points.flatMap(point =>
        series.map(item => item.value(point)),
    ).filter((value): value is number => value !== null);

    if (points.length === 0 || usableValues.length === 0 || series.length === 0) {
        return <EmptyChart text="Brak danych dla wybranych serii i filtrów." />;
    }

    const min = Math.min(...usableValues);
    const max = Math.max(...usableValues);
    const range = Math.max(max - min, 1);
    const width = 900;
    const height = 280;
    const paddingX = 48;
    const paddingY = 28;
    const chartWidth = width - paddingX * 2;
    const chartHeight = height - paddingY * 2;

    const x = (index: number) =>
        paddingX + (
            points.length === 1
                ? chartWidth / 2
                : (index / (points.length - 1)) * chartWidth
        );
    const y = (value: number) =>
        paddingY + chartHeight - ((value - min) / range) * chartHeight;

    return (
        <div className="analytics-timeline">
            <div className="analytics-legend">
                {series.map(item => (
                    <span
                        key={item.id}
                        className={`analytics-legend-series analytics-legend-${item.id.toLowerCase()}`}
                    >
                        {item.label}
                    </span>
                ))}
            </div>

            <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Wykres cen w czasie">
                <line className="analytics-axis" x1={paddingX} x2={paddingX} y1={paddingY} y2={height - paddingY} />
                <line className="analytics-axis" x1={paddingX} x2={width - paddingX} y1={height - paddingY} y2={height - paddingY} />

                {series.map(item => {
                    const path = buildSvgPath(points, item.value, x, y);

                    return (
                        <g key={item.id}>
                            {path.length > 0 && (
                                <path
                                    className={`analytics-line ${item.className}`}
                                    d={path}
                                />
                            )}
                            {points.map((point, index) => {
                                const value = item.value(point);

                                return value === null ? null : (
                                    <circle
                                        key={`${item.id}-${point.date}`}
                                        className={`analytics-dot ${item.dotClass}`}
                                        cx={x(index)}
                                        cy={y(value)}
                                        r="3.5"
                                    >
                                        <title>
                                            {`${point.label}: ${item.label} ${formatPrice(value)} · próba: ${item.sampleCount(point)}`}
                                        </title>
                                    </circle>
                                );
                            })}
                        </g>
                    );
                })}
            </svg>

            <div className="analytics-chart-footer">
                <span>{points[0]?.label ?? ""}</span>
                <span>{formatPrice(max)} – {formatPrice(min)}</span>
                <span>{points[points.length - 1]?.label ?? ""}</span>
            </div>
        </div>
    );
}

function buildSvgPath(
    points: AnalyticsTimelinePoint[],
    value: (point: AnalyticsTimelinePoint) => number | null,
    x: (index: number) => number,
    y: (value: number) => number,
): string {
    let path = "";
    let drawing = false;

    points.forEach((point, index) => {
        const current = value(point);

        if (current === null) {
            drawing = false;
            return;
        }

        path += `${drawing ? " L" : " M"} ${x(index)} ${y(current)}`;
        drawing = true;
    });

    return path.trim();
}

export default PriceTimelineChart;
