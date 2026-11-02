import useStatisticsReport from "../features/statistics/useStatisticsReport";
import StatisticsFilters from "../features/statistics/StatisticsFilters";
import { AnalyticsKpi, Metric } from "../features/statistics/AnalyticsMetrics";
import FilterPills from "../features/statistics/FilterPills";
import PriceTimelineChart from "../features/statistics/PriceTimelineChart";
import { VolumeChart, HistogramChart } from "../features/statistics/AnalyticsDistributions";
import ModelTable from "../features/statistics/ModelTable";
import { formatNullablePrice, formatNullableRate, formatNullableSignedPrice, formatNullablePercent } from "../features/statistics/analyticsFormat";
import "../styles/analytics.css";

function StatisticsPage() {
    const report = useStatisticsReport();
    const { overview, summary, source, visiblePriceSeries, visiblePriceSeriesOptions, observerRangeDetail, isLoading, errorMessage, load, setPriceSeries } = report;
    return (
        <section className="page analytics-page">
            <header className="page-header">
                <div>
                    <p className="page-eyebrow">Analiza rynku i zakupów</p>
                    <h1 className="page-title">Statystyki</h1>
                    <p className="page-description">
                        Rynek pochodzi z Observera. Nasze wyniki obejmują wyłącznie
                        oferty, które wcześniej trafiły do „Ofert do kupienia”.
                        Dane rynku są liczone od momentu wiarygodnego uruchomienia
                        trackingu danego modelu — nie udajemy, że znamy sprzedane
                        oferty z okresu sprzed startu Observera.
                    </p>
                </div>

                <button
                    className="secondary-button"
                    type="button"
                    disabled={isLoading}
                    onClick={() => void load()}
                >
                    {isLoading ? "Odświeżanie..." : "Odśwież"}
                </button>
            </header>

            {errorMessage !== null && (
                <div className="form-message form-message-error" role="alert">
                    {errorMessage}
                </div>
            )}

            <StatisticsFilters report={report} />

            {isLoading && overview === null ? (
                <article className="content-card">
                    <div className="dictionary-list-state">
                        Pobieranie statystyk...
                    </div>
                </article>
            ) : summary !== null && overview !== null ? (
                <>
                    {source !== "HISTORY" && (
                    <section className="analytics-section">
                        <div className="analytics-section-heading">
                            <div>
                                <span>Rynek / Observer</span>
                                <h2>Co dzieje się z wybranymi modelami</h2>
                                <p className="content-card-text">
                                    Oferta pozostaje w statystykach po sprzedaży lub usunięciu,
                                    jeżeli Observer zdążył ją wcześniej zarejestrować.
                                    Średnie tempo nie jest rozcieńczane dniami sprzed startu trackingu.
                                </p>
                                <p className="content-card-text">
                                    {observerRangeDetail}
                                </p>
                            </div>
                        </div>

                        <div className="analytics-kpi-grid">
                            <AnalyticsKpi
                                label="Oferty rynku"
                                value={String(summary.marketListingCount)}
                                detail={`${summary.marketPriceSampleCount} obserwacji ma zapisaną cenę`}
                            />
                            <AnalyticsKpi
                                label="Średnia cena rynku"
                                value={formatNullablePrice(summary.averageMarketPrice)}
                                detail="Cena pierwszej zapisanej obserwacji"
                            />
                            <AnalyticsKpi
                                label="Mediana rynku"
                                value={formatNullablePrice(summary.medianMarketPrice)}
                                detail="Odporna na pojedyncze skrajne ceny"
                            />
                            <AnalyticsKpi
                                label="Średnio / dzień"
                                value={formatNullableRate(summary.averageListingsPerDay)}
                                detail="Nowe oferty w wybranym okresie"
                            />
                            <AnalyticsKpi
                                label="Średnio / tydzień"
                                value={formatNullableRate(summary.averageListingsPerWeek)}
                                detail="Nowe oferty w wybranym okresie"
                            />
                            <AnalyticsKpi
                                label="Średnio / miesiąc"
                                value={formatNullableRate(summary.averageListingsPerMonth)}
                                detail="Nowe oferty w wybranym okresie"
                            />
                        </div>
                    </section>
                    )}

                    {source !== "OBSERVER" && (
                    <section className="analytics-section">
                        <div className="analytics-section-heading">
                            <div>
                                <span>Nasze decyzje</span>
                                <h2>Tylko oferty, które były „do kupienia”</h2>
                            </div>
                        </div>

                        <div className="analytics-kpi-grid">
                            <AnalyticsKpi
                                label="Kupione"
                                value={String(summary.purchasedCount)}
                                detail="Oznaczone jako Kupiłem"
                            />
                            <AnalyticsKpi
                                label="Średnia cena zakupu"
                                value={formatNullablePrice(summary.averagePurchasePrice)}
                                detail="Cena końcowa kupionych"
                            />
                            <AnalyticsKpi
                                label="Mediana zakupu"
                                value={formatNullablePrice(summary.medianPurchasePrice)}
                                detail="Mediana naszych cen zakupu"
                            />
                            <AnalyticsKpi
                                label="Nie kupiłem"
                                value={String(summary.rejectedCount)}
                                detail={`${summary.missedOpportunityCount} z powodu utraconej okazji`}
                            />
                            <AnalyticsKpi
                                label="Oszustwa"
                                value={String(summary.scamCount)}
                                detail="Oferty oznaczone ręcznie jako oszustwo"
                            />
                            <AnalyticsKpi
                                label="Zakup vs mediana rynku"
                                value={formatNullableSignedPrice(
                                    summary.purchaseBelowMarketMedianAmount,
                                )}
                                detail={formatNullablePercent(
                                    summary.purchaseBelowMarketMedianPercent,
                                )}
                                positive={
                                    summary.purchaseBelowMarketMedianAmount !== null
                                    && summary.purchaseBelowMarketMedianAmount > 0
                                }
                            />
                        </div>
                    </section>
                    )}

                    <div className={source === "HISTORY"
                        ? "analytics-grid"
                        : "analytics-grid analytics-grid-two"}>
                        <article className="content-card analytics-chart-card">
                            <div className="analytics-card-heading analytics-card-heading-stacked">
                                <div>
                                    <h2 className="content-card-title">
                                        Ceny w czasie
                                    </h2>
                                    <p className="content-card-text">
                                        Włącz dokładnie te serie, które chcesz porównywać.
                                    </p>
                                </div>
                                <FilterPills
                                    title="Serie wykresu"
                                    allLabel="Wszystkie"
                                    options={visiblePriceSeriesOptions}
                                    selected={visiblePriceSeries}
                                    disabled={false}
                                    onChange={setPriceSeries}
                                />
                            </div>
                            <PriceTimelineChart
                                points={overview.timeline}
                                selectedSeries={visiblePriceSeries}
                                source={source}
                            />
                        </article>

                        {source !== "HISTORY" && (
                        <article className="content-card analytics-chart-card">
                            <div className="analytics-card-heading">
                                <div>
                                    <h2 className="content-card-title">
                                        Liczba nowych ofert
                                    </h2>
                                    <p className="content-card-text">
                                        Tempo pojawiania się ofert w wybranym grupowaniu.
                                    </p>
                                </div>
                            </div>
                            <VolumeChart points={overview.timeline} />
                        </article>
                        )}
                    </div>

                    {source !== "HISTORY" && (
                    <div className="analytics-grid analytics-grid-two">
                        <article className="content-card analytics-chart-card">
                            <div className="analytics-card-heading">
                                <div>
                                    <h2 className="content-card-title">
                                        Rozkład cen rynku
                                    </h2>
                                    <p className="content-card-text">
                                        Gdzie skupiają się ceny wybranych modeli.
                                    </p>
                                </div>
                            </div>
                            <HistogramChart buckets={overview.marketPriceHistogram} />
                        </article>

                        <article className="content-card analytics-descriptive-card">
                            <h2 className="content-card-title">
                                Statystyki opisowe rynku
                            </h2>
                            <div className="analytics-descriptive-grid analytics-descriptive-grid-compact">
                                <Metric label="Minimum" value={formatNullablePrice(summary.marketMinPrice)} />
                                <Metric label="P25" value={formatNullablePrice(summary.marketP25)} />
                                <Metric label="Mediana" value={formatNullablePrice(summary.medianMarketPrice)} />
                                <Metric label="P75" value={formatNullablePrice(summary.marketP75)} />
                                <Metric label="Maksimum" value={formatNullablePrice(summary.marketMaxPrice)} />
                                <Metric
                                    label="Odchylenie"
                                    value={formatNullablePrice(summary.marketStandardDeviation)}
                                />
                            </div>
                        </article>
                    </div>
                    )}

                    <article className="content-card analytics-table-card">
                        <div className="analytics-card-heading">
                            <div>
                                <h2 className="content-card-title">
                                    Modele
                                </h2>
                                <p className="content-card-text">
                                    Ceny, tempo rynku i nasze zakupy per model.
                                </p>
                            </div>
                        </div>
                        <ModelTable rows={overview.modelBreakdowns} />
                    </article>
                </>
            ) : null}
        </section>
    );
}

export default StatisticsPage;
