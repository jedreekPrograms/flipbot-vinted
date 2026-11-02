import type { AnalyticsTimelinePoint, AnalyticsHistogramBucket } from "../../api/analyticsApi";
import { formatPrice, formatCompactPrice } from "./analyticsFormat";
import EmptyChart from "./EmptyChart";

export function VolumeChart({
    points,
}: {
    points: AnalyticsTimelinePoint[];
}) {
    if (points.length === 0) {
        return <EmptyChart text="Brak obserwacji rynku w tym okresie." />;
    }

    const maxCount = Math.max(
        ...points.map(point => point.marketListingCount),
        1,
    );

    return (
        <div className="analytics-volume">
            <div className="analytics-volume-bars">
                {points.map(point => (
                    <div
                        className="analytics-volume-column"
                        key={point.date}
                        title={`${point.label}: ${point.marketListingCount} ofert`}
                    >
                        <span>{point.marketListingCount}</span>
                        <div
                            className="analytics-volume-bar"
                            style={{
                                height: `${Math.max(
                                    6,
                                    (point.marketListingCount / maxCount) * 180,
                                )}px`,
                            }}
                        />
                        <small>{point.label}</small>
                    </div>
                ))}
            </div>
        </div>
    );
}

export function HistogramChart({
    buckets,
}: {
    buckets: AnalyticsHistogramBucket[];
}) {
    if (buckets.length === 0) {
        return <EmptyChart text="Observer nie ma jeszcze próbek cen dla tych filtrów." />;
    }

    const maxCount = Math.max(...buckets.map(bucket => bucket.count), 1);

    return (
        <div className="analytics-histogram">
            <div className="analytics-bars">
                {buckets.map((bucket, index) => (
                    <div
                        className="analytics-bar-column"
                        key={`${bucket.from}-${bucket.to}-${index}`}
                        title={`${formatPrice(bucket.from)}–${formatPrice(bucket.to)}: ${bucket.count}`}
                    >
                        <span>{bucket.count}</span>
                        <div
                            className="analytics-bar"
                            style={{
                                height: `${Math.max(
                                    8,
                                    (bucket.count / maxCount) * 170,
                                )}px`,
                            }}
                        />
                        <small>{formatCompactPrice(bucket.from)}</small>
                    </div>
                ))}
            </div>
        </div>
    );
}
