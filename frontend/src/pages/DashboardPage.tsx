import { Link } from "react-router-dom";
import useDashboardStats from "../features/dashboard/useDashboardStats";
import { DashboardFilterGroup, StatCard } from "../features/dashboard/DashboardCards";
import {
    assessmentOptions, dashboardSummary, formatPercentage, formatSelection,
    getPeriodLabel, outcomeOptions, periodOptions,
} from "../features/dashboard/dashboardSummary";

function DashboardPage() {
    const {
        stats, period, outcomes, assessments, isLoading, errorMessage, loadStats,
        setPeriod, setOutcomes, setAssessments,
    } = useDashboardStats();
    const { activityStats, periodStats, purchaseRate } = dashboardSummary(stats, period);

    return (
        <section className="page">
            <header className="page-header">
                <div>
                    <p className="page-eyebrow">Panel główny</p>
                    <h1 className="page-title">Dashboard</h1>
                    <p className="page-description">
                        Podsumowanie działania botów, negocjacji i wyników ofert.
                    </p>
                </div>

                <button
                    className="secondary-button"
                    type="button"
                    disabled={isLoading}
                    onClick={() => void loadStats()}
                >
                    {isLoading ? "Odświeżanie..." : "Odśwież"}
                </button>
            </header>

            {errorMessage !== null && (
                <div
                    className="form-message form-message-error"
                    role="alert"
                >
                    {errorMessage}
                </div>
            )}

            {stats !== null && (
                <Link
                    className={
                        stats.actionRequiredCount > 0
                            ? "dashboard-buy-hero dashboard-buy-hero-active"
                            : "dashboard-buy-hero"
                    }
                    to="/action-required"
                >
                    <div>
                        <span className="dashboard-buy-hero-eyebrow">
                            Wymaga Twojej decyzji
                        </span>
                        <strong>{stats.actionRequiredCount}</strong>
                        <h2>Oferty do kupienia teraz</h2>
                        <p>
                            To są wyłącznie oferty, które dotarły do etapu decyzji zakupowej.
                            Kliknij, żeby przejść do listy.
                        </p>
                    </div>
                    <span className="dashboard-buy-hero-action">
                        Otwórz oferty →
                    </span>
                </Link>
            )}

            <div className="dashboard-section-header">
                <div>
                    <h2>Aktualny stan</h2>
                    <p>Dane operacyjne niezależne od filtrów historii.</p>
                </div>
            </div>

            {stats !== null && (
                <div className="stats-grid">
                    {activityStats.map(stat => (
                        <StatCard
                            key={stat.label}
                            stat={stat}
                        />
                    ))}
                </div>
            )}

            <div className="dashboard-period-section">
                <div className="dashboard-section-header">
                    <div>
                        <h2>Wyniki</h2>
                        <p>
                            Wybierz okres oraz typy ofert, które mają wejść do podsumowania.
                        </p>
                    </div>
                </div>

                <div className="dashboard-period-switch">
                    {periodOptions.map(option => (
                        <button
                            key={option.value}
                            className={
                                period === option.value
                                    ? "dashboard-period-button dashboard-period-button-active"
                                    : "dashboard-period-button"
                            }
                            type="button"
                            disabled={isLoading}
                            onClick={() => setPeriod(option.value)}
                        >
                            {option.label}
                        </button>
                    ))}
                </div>

                <DashboardFilterGroup
                    title="Wynik oferty"
                    allLabel="Wszystkie"
                    options={outcomeOptions}
                    selected={outcomes}
                    disabled={isLoading}
                    onChange={setOutcomes}
                />

                <DashboardFilterGroup
                    title="Ocena oferty"
                    allLabel="Wszystkie oceny"
                    options={assessmentOptions}
                    selected={assessments}
                    disabled={isLoading}
                    onChange={setAssessments}
                />
            </div>

            {isLoading && stats === null ? (
                <article className="content-card">
                    <div className="dictionary-list-state">
                        Pobieranie statystyk...
                    </div>
                </article>
            ) : stats !== null ? (
                <>
                    <div className="stats-grid">
                        {periodStats.map(stat => (
                            <StatCard
                                key={stat.label}
                                stat={stat}
                            />
                        ))}
                    </div>

                    <div className="dashboard-summary-grid">
                        <article className="content-card">
                            <h2 className="content-card-title">
                                Decyzje zakupowe
                            </h2>
                            <p className="content-card-text">
                                Wyniki ofert po aktualnie wybranych filtrach.
                            </p>

                            <div className="dashboard-decision-stats">
                                <div>
                                    <span>Kupiłem</span>
                                    <strong>{stats.purchasedCount}</strong>
                                </div>
                                <div>
                                    <span>Nie kupiłem</span>
                                    <strong>{stats.skippedByUserCount}</strong>
                                </div>
                                <div>
                                    <span>Wszystkie decyzje</span>
                                    <strong>
                                        {stats.purchasedCount + stats.skippedByUserCount}
                                    </strong>
                                </div>
                            </div>
                        </article>

                        <article className="content-card">
                            <h2 className="content-card-title">
                                Skuteczność zakupu
                            </h2>
                            <p className="content-card-text">
                                Jaki procent decyzji z „Ofert do kupienia” zakończył się zakupem.
                            </p>

                            <div className="dashboard-effectiveness">
                                <strong>{formatPercentage(purchaseRate)}</strong>
                                <span>ofert zakończonych zakupem</span>
                            </div>
                        </article>
                    </div>

                    <article className="content-card">
                        <h2 className="content-card-title">
                            Aktywne filtry
                        </h2>
                        <p className="content-card-text">
                            Okres: <strong>{getPeriodLabel(period)}</strong>.
                            {" "}Wynik: <strong>{formatSelection(
                                outcomes,
                                outcomeOptions,
                                "wszystkie",
                            )}</strong>.
                            {" "}Ocena: <strong>{formatSelection(
                                assessments,
                                assessmentOptions,
                                "wszystkie",
                            )}</strong>.
                        </p>
                        <p className="content-card-text">
                            Kwoty zakupowe nadal są liczone wyłącznie z ofert
                            sklasyfikowanych jako Kupione. Pozostałe filtry wpływają
                            na liczbę ofert, średnią i medianę wybranego zbioru.
                        </p>
                    </article>
                </>
            ) : null}
        </section>
    );
}

export default DashboardPage;
