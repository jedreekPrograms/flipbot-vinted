import { Link } from "react-router-dom";
import AppDialog from "../components/AppDialog";
import MarketStatsObserverCard from "../components/MarketStatsObserverCard";
import { BotDailyLimitCell, BotNegotiationStepsCell, BotStatus } from "../features/bots/BotOverviewCells";
import useBotsOverview from "../features/bots/useBotsOverview";

function BotsPage() {
    const {
        bots, activityByBotId, runtimeByBotId, nowMs, isInitialLoading, isRefreshing,
        actionBotId, bulkAction, pendingBulkAction, setPendingBulkAction, errorMessage,
        successMessage, runningBots, stoppedBots, pendingTargets, anyActionBusy,
        loadBots, handleStartBot, handleStopBot, runBulkAction,
    } = useBotsOverview();

    return (
        <section className="page">
            <header className="page-header bots-page-header">
                <div>
                    <p className="page-eyebrow">Zarządzanie</p>
                    <h1 className="page-title">Boty</h1>
                    <p className="page-description">
                        Zarządzaj botami, ich aktualnym stanem, negocjacjami oraz dziennym limitem realnych akcji ofertowych.
                    </p>
                </div>

                <Link className="primary-button" to="/bots/create">
                    + Utwórz bota
                </Link>
            </header>

            {errorMessage !== null && (
                <div className="form-message form-message-error" role="alert">
                    {errorMessage}
                </div>
            )}
            {successMessage !== null && (
                <div className="form-message form-message-success" role="status">
                    {successMessage}
                </div>
            )}

            <MarketStatsObserverCard />

            <article className="content-card">
                <div className="bots-list-header">
                    <div>
                        <h2 className="content-card-title">Wszystkie boty</h2>
                        <p className="content-card-text">
                            Statystyki dzienne są liczone od 00:00 w strefie Europe/Warsaw i odświeżają się automatycznie.
                        </p>
                    </div>

                    <div className="bots-list-header-actions">
                        <span className="dictionary-count">{bots.length}</span>
                        <button
                            className="bot-start-button"
                            type="button"
                            disabled={anyActionBusy || stoppedBots.length === 0 || isInitialLoading}
                            onClick={() => setPendingBulkAction("START")}
                        >
                            {bulkAction === "START"
                                ? "Uruchamianie..."
                                : `Uruchom wszystkie (${stoppedBots.length})`}
                        </button>
                        <button
                            className="bot-stop-button"
                            type="button"
                            disabled={anyActionBusy || runningBots.length === 0 || isInitialLoading}
                            onClick={() => setPendingBulkAction("STOP")}
                        >
                            {bulkAction === "STOP"
                                ? "Zatrzymywanie..."
                                : `Zatrzymaj wszystkie (${runningBots.length})`}
                        </button>
                        <button
                            className="secondary-button"
                            type="button"
                            disabled={isInitialLoading || isRefreshing || anyActionBusy}
                            onClick={() => void loadBots("background")}
                        >
                            {isRefreshing ? "Odświeżanie..." : "Odśwież"}
                        </button>
                    </div>
                </div>

                {isInitialLoading && bots.length === 0 ? (
                    <div className="dictionary-list-state">Pobieranie botów...</div>
                ) : bots.length === 0 ? (
                    <div className="bots-empty-state">
                        <h3>Nie masz jeszcze żadnego bota</h3>
                        <p>
                            Utwórz pierwszego bota i skonfiguruj konto Vinted, filtry oraz strategię negocjacji.
                        </p>
                        <Link className="primary-button" to="/bots/create">
                            Utwórz pierwszego bota
                        </Link>
                    </div>
                ) : (
                    <div className="bots-table-wrapper" aria-busy={isRefreshing || anyActionBusy}>
                        <table className="bots-table">
                            <thead>
                                <tr>
                                    <th>Bot</th>
                                    <th>Konto</th>
                                    <th>Status</th>
                                    <th>Limit dzienny</th>
                                    <th>Aktywne negocjacje</th>
                                    <th>Nowe dziś</th>
                                    <th>Kroki dziś</th>
                                    <th>ID</th>
                                    <th className="bots-actions-column">Akcje</th>
                                </tr>
                            </thead>
                            <tbody>
                                {bots.map((bot) => {
                                    const isRunning = bot.status.toUpperCase() === "RUNNING";
                                    const isActionInProgress = actionBotId === bot.id;
                                    const activity = activityByBotId[bot.id];
                                    const runtime = runtimeByBotId[bot.id];

                                    return (
                                        <tr key={bot.id}>
                                            <td data-label="Bot">
                                                <div className="bot-name-cell">
                                                    <strong>{bot.name}</strong>
                                                    <span>Vinted</span>
                                                </div>
                                            </td>
                                            <td data-label="Konto" className="bots-email-cell">
                                                {bot.email}
                                            </td>
                                            <td data-label="Status">
                                                <BotStatus
                                                    status={bot.status}
                                                    runtime={runtime}
                                                    nowMs={nowMs}
                                                />
                                            </td>
                                            <td data-label="Limit dzienny">
                                                {activity
                                                    ? <BotDailyLimitCell activity={activity} />
                                                    : <span>Brak danych</span>}
                                            </td>
                                            <td data-label="Aktywne negocjacje">
                                                <strong>{activity?.activeNegotiations ?? "—"}</strong>
                                            </td>
                                            <td data-label="Nowe dziś">
                                                <strong>{activity?.newNegotiationsToday ?? "—"}</strong>
                                            </td>
                                            <td data-label="Kroki dziś">
                                                {activity
                                                    ? <BotNegotiationStepsCell activity={activity} />
                                                    : <span>Brak danych</span>}
                                            </td>
                                            <td data-label="ID">
                                                <span className="bot-id">#{bot.id}</span>
                                            </td>
                                            <td data-label="Akcje" className="bots-actions-cell">
                                                <div className="bot-row-actions">
                                                    {isRunning ? (
                                                        <button
                                                            className="bot-stop-button"
                                                            type="button"
                                                            disabled={anyActionBusy}
                                                            onClick={() => void handleStopBot(bot.id)}
                                                        >
                                                            {isActionInProgress ? "Zatrzymywanie..." : "Zatrzymaj"}
                                                        </button>
                                                    ) : (
                                                        <>
                                                            <button
                                                                className="bot-start-button"
                                                                type="button"
                                                                disabled={anyActionBusy}
                                                                onClick={() => void handleStartBot(bot.id)}
                                                            >
                                                                {isActionInProgress ? "Uruchamianie..." : "Uruchom"}
                                                            </button>
                                                            <Link className="secondary-button" to={`/bots/${bot.id}/edit`}>
                                                                Edytuj
                                                            </Link>
                                                        </>
                                                    )}
                                                </div>
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                )}
            </article>

            <AppDialog
                open={pendingBulkAction !== null}
                title={pendingBulkAction === "START" ? "Uruchomić wszystkie boty?" : "Zatrzymać wszystkie boty?"}
                description={
                    pendingBulkAction === "START"
                        ? `Uruchomione zostaną ${pendingTargets.length} aktualnie zatrzymane boty. Boty już działające pozostaną bez zmian.`
                        : `Zatrzymane zostaną ${pendingTargets.length} aktualnie działające boty. Zapisane negocjacje pozostaną w systemie.`
                }
                confirmLabel={pendingBulkAction === "START" ? "Uruchom wszystkie" : "Zatrzymaj wszystkie"}
                danger={pendingBulkAction === "STOP"}
                busy={bulkAction !== null}
                onCancel={() => setPendingBulkAction(null)}
                onConfirm={() => {
                    if (pendingBulkAction !== null) {
                        void runBulkAction(pendingBulkAction);
                    }
                }}
            />
        </section>
    );
}

export default BotsPage;
