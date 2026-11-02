import type useStatisticsReport from "./useStatisticsReport";
import type { AnalyticsSource, AnalyticsGranularity } from "../../api/analyticsApi";
import AppSelect from "../../components/AppSelect";
import { periodOptions, sourceOptions, granularityOptions, outcomeOptions, assessmentOptions } from "./analyticsOptions";
import FilterPills from "./FilterPills";
import ModelPicker from "./ModelPicker";

export default function StatisticsFilters({ report }: { report: ReturnType<typeof useStatisticsReport> }) {
    const { models, period, source, granularity, from, to, modelIds, outcomes, assessments, isLoading, choosePreset, setSource, setGranularity, setFrom, setTo, setModelIds, setOutcomes, setAssessments } = report;
    return (
            <article className="content-card analytics-filter-card">
                <div className="analytics-filter-row analytics-filter-row-main">
                    <div className="analytics-filter-field">
                        <label>Źródło danych</label>
                        <AppSelect
                            value={source}
                            options={sourceOptions}
                            disabled={isLoading}
                            ariaLabel="Źródło danych"
                            onChange={value => setSource(value as AnalyticsSource)}
                        />
                    </div>
                    <div className="analytics-filter-field">
                        <label>Grupuj wykres po</label>
                        <AppSelect
                            value={granularity}
                            options={granularityOptions}
                            disabled={isLoading}
                            ariaLabel="Grupowanie wykresu"
                            onChange={value =>
                                setGranularity(value as AnalyticsGranularity)}
                        />
                    </div>
                    <div className="analytics-date-field">
                        <label>Od</label>
                        <input
                            className="form-input"
                            type="date"
                            value={from}
                            disabled={isLoading}
                            onChange={event => setFrom(event.target.value)}
                        />
                    </div>
                    <div className="analytics-date-field">
                        <label>Do</label>
                        <input
                            className="form-input"
                            type="date"
                            value={to}
                            disabled={isLoading}
                            onChange={event => setTo(event.target.value)}
                        />
                    </div>
                </div>

                {(from.length > 0 || to.length > 0) && (
                    <div className="analytics-custom-range-note">
                        Zakres ręczny ma pierwszeństwo przed szybkim okresem.
                    </div>
                )}

                <FilterPills
                    title="Szybki okres"
                    allMode={false}
                    options={periodOptions}
                    selected={from.length > 0 || to.length > 0 ? [] : [period]}
                    disabled={isLoading}
                    onSingleChange={choosePreset}
                />

                <ModelPicker
                    models={models}
                    selectedIds={modelIds}
                    disabled={isLoading}
                    onChange={setModelIds}
                />

                {source !== "OBSERVER" && (
                <FilterPills
                    title="Wynik naszych ofert"
                    allLabel="Wszystkie wyniki"
                    options={outcomeOptions}
                    selected={outcomes}
                    disabled={isLoading}
                    onChange={setOutcomes}
                />
                )}

                {source !== "OBSERVER" && (
                <FilterPills
                    title="Ocena naszych ofert"
                    allLabel="Wszystkie oceny"
                    options={assessmentOptions}
                    selected={assessments}
                    disabled={isLoading}
                    onChange={setAssessments}
                />
                )}
            </article>
    );
}
