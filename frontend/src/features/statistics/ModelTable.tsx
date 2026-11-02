import type { AnalyticsModelBreakdown } from "../../api/analyticsApi";
import { formatNullablePrice, formatNullableRate, formatNullableSignedPrice, formatNullablePercent } from "./analyticsFormat";
import EmptyChart from "./EmptyChart";

function ModelTable({
    rows,
}: {
    rows: AnalyticsModelBreakdown[];
}) {
    if (rows.length === 0) {
        return <EmptyChart text="Brak danych modelowych dla tych filtrów." />;
    }

    return (
        <div className="analytics-table-wrap">
            <table className="analytics-table">
                <thead>
                    <tr>
                        <th>Model</th>
                        <th>Oferty</th>
                        <th>Śr. rynek</th>
                        <th>Mediana rynku</th>
                        <th>/ dzień</th>
                        <th>/ tydzień</th>
                        <th>/ miesiąc</th>
                        <th>Kupione</th>
                        <th>Śr. zakup</th>
                        <th>Mediana zakupu</th>
                        <th>Nie kupiłem legit</th>
                        <th>Oszustwa</th>
                        <th>Zakup vs rynek</th>
                    </tr>
                </thead>
                <tbody>
                    {rows.map(row => (
                        <tr key={row.modelId}>
                            <td>
                                <strong>{row.model}</strong>
                                <span>{row.brand}</span>
                            </td>
                            <td>{row.marketListingCount}</td>
                            <td>{formatNullablePrice(row.averageMarketPrice)}</td>
                            <td>{formatNullablePrice(row.medianMarketPrice)}</td>
                            <td>{formatNullableRate(row.averageListingsPerDay)}</td>
                            <td>{formatNullableRate(row.averageListingsPerWeek)}</td>
                            <td>{formatNullableRate(row.averageListingsPerMonth)}</td>
                            <td>{row.purchasedCount}</td>
                            <td>{formatNullablePrice(row.averagePurchasePrice)}</td>
                            <td>{formatNullablePrice(row.medianPurchasePrice)}</td>
                            <td>{row.legitRejectedCount}</td>
                            <td>{row.scamCount}</td>
                            <td>
                                {formatNullableSignedPrice(
                                    row.purchaseBelowMarketMedianAmount,
                                )}
                                <span>
                                    {formatNullablePercent(
                                        row.purchaseBelowMarketMedianPercent,
                                    )}
                                </span>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

export default ModelTable;
