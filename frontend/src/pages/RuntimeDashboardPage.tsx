import type { RuntimeStatus } from "../api/dashboardApi";
import AppSelect, { type AppSelectOption } from "../components/AppSelect";
import { RuntimeRow, TableHeader } from "../features/runtime/RuntimeBotTable";
import { formatDuration } from "../features/runtime/runtimeFormat";
import useRuntimeDashboard from "../features/runtime/useRuntimeDashboard";

const runtimeStatuses: Array<RuntimeStatus | "ALL"> = [
    "ALL",
    "WORKING",
    "QUEUED",
    "COOLDOWN",
    "ERROR",
    "IDLE",
];

const runtimeStatusOptions: AppSelectOption[] = runtimeStatuses.map(status => ({
    value: status,
    label: status === "ALL"
        ? "Wszystkie statusy"
        : status,
}));

function RuntimeDashboardPage() {
    const {
        data, statusFilter, setStatusFilter, search, setSearch, isLoading, errorMessage,
        nowMs, previewUpdatingBotIds, loadRuntime, handleSessionPreview, filteredBots,
    } = useRuntimeDashboard();

    return (
        <section className="page">
            <header className="page-header">
                <div>
                    <p className="page-eyebrow">
                        Scheduler
                    </p>

                    <h1 className="page-title">
                        Runtime
                    </h1>

                    <p className="page-description">
                        Bieżący stan kolejki, workerów i ostatnich wykonań botów.
                        Dane odświeżają się automatycznie co 5 sekund.
                    </p>
                </div>

                <button
                    className="secondary-button"
                    type="button"
                    disabled={isLoading}
                    onClick={() => {
                        void loadRuntime(true);
                    }}
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

            {data !== null && (
                <>
                    <div className="stats-grid">
                        <RuntimeStat
                            label="RUNNING"
                            value={data.runningBots}
                            description={`z ${data.totalBots} wszystkich botów`}
                        />
                        <RuntimeStat
                            label="WORKING"
                            value={data.workingCount}
                            description="aktualnie zajęte botami"
                        />
                        <RuntimeStat
                            label="QUEUED"
                            value={data.queuedCount}
                            description="czekają na następny job"
                        />
                        <RuntimeStat
                            label="COOLDOWN"
                            value={data.cooldownCount}
                            description="czasowo wstrzymane"
                        />
                        <RuntimeStat
                            label="ERROR"
                            value={data.errorCount}
                            description="ostatni job zakończył się błędem"
                        />
                        <RuntimeStat
                            label="Średni job"
                            value={formatDuration(data.averageLastRunDurationMs)}
                            description="średni czas ostatnich wykonań"
                        />
                    </div>

                    <article className="content-card runtime-card">
                        <div className="runtime-toolbar">
                            <div className="runtime-toolbar-copy">
                                <h2 className="content-card-title">
                                    Boty runtime
                                </h2>
                                <p className="content-card-text">
                                    Pokazano {filteredBots.length} z {data.bots.length} botów.
                                </p>
                            </div>

                            <div className="runtime-filter-controls">
                                <input
                                    className="form-input"
                                    type="search"
                                    value={search}
                                    placeholder="Nazwa lub ID bota"
                                    aria-label="Szukaj bota po nazwie lub ID"
                                    onChange={event => setSearch(event.target.value)}
                                />

                                <AppSelect
                                    value={statusFilter}
                                    ariaLabel="Filtr statusu runtime"
                                    options={runtimeStatusOptions}
                                    onChange={value => {
                                        setStatusFilter(
                                            value as RuntimeStatus | "ALL",
                                        );
                                    }}
                                />
                            </div>
                        </div>

                        <div className="runtime-table-wrapper">
                            <table className="runtime-table">
                                <thead>
                                    <tr>
                                        <TableHeader>Bot</TableHeader>
                                        <TableHeader>Bot status</TableHeader>
                                        <TableHeader>Runtime</TableHeader>
                                        <TableHeader>Sesja</TableHeader>
                                        <TableHeader>Slot</TableHeader>
                                        <TableHeader>Ostatni job</TableHeader>
                                        <TableHeader>Następny job</TableHeader>
                                        <TableHeader>Błędy z rzędu</TableHeader>
                                        <TableHeader>Ostatni błąd</TableHeader>
                                    </tr>
                                </thead>

                                <tbody>
                                    {filteredBots.map(bot => (
                                        <RuntimeRow
                                            key={bot.botId}
                                            bot={bot}
                                            nowMs={nowMs}
                                            previewUpdating={previewUpdatingBotIds.has(bot.botId)}
                                            onSessionPreview={handleSessionPreview}
                                        />
                                    ))}
                                </tbody>
                            </table>
                        </div>

                        {filteredBots.length === 0 && (
                            <div className="dictionary-list-state">
                                Brak botów pasujących do wybranego filtra.
                            </div>
                        )}
                    </article>
                </>
            )}

            {isLoading && data === null && (
                <article className="content-card">
                    <div className="dictionary-list-state">
                        Pobieranie danych runtime...
                    </div>
                </article>
            )}
        </section>
    );
}

function RuntimeStat({
    label,
    value,
    description,
}: {
    label: string;
    value: number | string;
    description: string;
}) {
    return (
        <article className="stat-card">
            <div className="stat-label">
                {label}
            </div>
            <div className="stat-value">
                {value}
            </div>
            <div className="stat-description">
                {description}
            </div>
        </article>
    );
}

export default RuntimeDashboardPage;
