import StepResponsePolicy from "./StepResponsePolicy";
import type {
    CounterOfferRuleField,
    NegotiationStepField,
    NegotiationStepForm,
    NegotiationStepPolicyField,
} from "./botForm";

export interface NegotiationStepsSectionProps {
    negotiationSteps: NegotiationStepForm[];
    dailyNegotiationBudget: string;

    definitionDisabled?: boolean;
    policyDisabled?: boolean;

    onAddStep: () => void;
    onRemoveStep: (stepId: number) => void;
    onUpdateStep: (
        stepId: number,
        field: NegotiationStepField,
        value: string,
    ) => void;
    onUpdateStepPolicy: (
        stepId: number,
        field: NegotiationStepPolicyField,
        value: string,
    ) => void;
    onAddCounterOfferRule: (stepId: number) => void;
    onRemoveCounterOfferRule: (
        stepId: number,
        ruleId: number,
    ) => void;
    onUpdateCounterOfferRule: (
        stepId: number,
        ruleId: number,
        field: CounterOfferRuleField,
        value: string,
    ) => void;
}

function NegotiationStepsSection({
    negotiationSteps,
    dailyNegotiationBudget,
    definitionDisabled = false,
    policyDisabled = false,
    onAddStep,
    onRemoveStep,
    onUpdateStep,
    onUpdateStepPolicy,
    onAddCounterOfferRule,
    onRemoveCounterOfferRule,
    onUpdateCounterOfferRule,
}: NegotiationStepsSectionProps) {
    return (
        <article className="content-card">
            <div className="bot-form-section-header">
                <div>
                    <span className="bot-form-step">6</span>
                    <h2 className="content-card-title">Kroki negocjacji</h2>
                </div>

                <p className="content-card-text">
                    Cena i wiadomość definiują krok. Pod każdym krokiem możesz
                    osobno ustawić, kiedy bot ma przejść dalej po odrzuceniu
                    albo po kontrofecie sprzedającego.
                </p>
            </div>

            {definitionDisabled && (
                <div className="information-box">
                    Aktywne rozmowy blokują zmianę cen kroków, progów akceptacji,
                    wiadomości i liczby kroków, ponieważ zmieniłoby to znaczenie
                    aktualnego kroku. <strong>Reguły reakcji i czasy oczekiwania
                    pozostają edytowalne</strong> i zaczną obowiązywać przy
                    kolejnych reakcjach sprzedających.
                </div>
            )}

            <div className="negotiation-steps-list">
                {negotiationSteps.map((step, index) => {
                    const stepNumber = index + 1;
                    const isLastStep = index === negotiationSteps.length - 1;

                    return (
                        <article
                            key={step.id}
                            className="negotiation-step-card"
                        >
                            <div className="negotiation-step-header">
                                <div>
                                    <span className="negotiation-step-number">
                                        Krok {stepNumber}
                                    </span>
                                    <p className="negotiation-step-description">
                                        Oferta numer {stepNumber} w tej negocjacji.
                                    </p>
                                </div>

                                <button
                                    className="danger-text-button"
                                    type="button"
                                    disabled={
                                        definitionDisabled
                                        || negotiationSteps.length === 1
                                    }
                                    onClick={() => onRemoveStep(step.id)}
                                >
                                    Usuń krok
                                </button>
                            </div>

                            <div className="bot-form-grid bot-form-grid-two">
                                <div className="form-field">
                                    <label
                                        className="form-label"
                                        htmlFor={`offer-price-${step.id}`}
                                    >
                                        Cena oferty
                                    </label>
                                    <input
                                        id={`offer-price-${step.id}`}
                                        className="form-input"
                                        type="number"
                                        min="0.01"
                                        step="0.01"
                                        required
                                        disabled={definitionDisabled}
                                        value={step.offerPrice}
                                        placeholder="np. 900"
                                        onChange={(event) => onUpdateStep(
                                            step.id,
                                            "offerPrice",
                                            event.target.value,
                                        )}
                                    />
                                    <span className="form-help">
                                        Bazowa kwota oferty. Przy adaptacyjnej
                                        drabince może zostać proporcjonalnie
                                        przeskalowana.
                                    </span>
                                </div>

                                <div className="form-field">
                                    <label
                                        className="form-label"
                                        htmlFor={`counter-offer-${step.id}`}
                                    >
                                        Akceptuj kontrofertę do
                                    </label>
                                    <input
                                        id={`counter-offer-${step.id}`}
                                        className="form-input"
                                        type="number"
                                        min="0.01"
                                        step="0.01"
                                        required
                                        disabled={definitionDisabled}
                                        value={step.maxAcceptedCounterOffer}
                                        placeholder="np. 950"
                                        onChange={(event) => onUpdateStep(
                                            step.id,
                                            "maxAcceptedCounterOffer",
                                            event.target.value,
                                        )}
                                    />
                                    <span className="form-help">
                                        Jeśli sprzedający poda cenę nie wyższą
                                        niż ten próg, oferta trafia od razu do
                                        „Oferty do kupienia”. W trybie adaptacyjnym
                                        formalna kontroferta nie wyższa od globalnej
                                        maksymalnej ceny negocjacji również kończy
                                        automat i trafia od razu do ręcznej decyzji.
                                    </span>
                                </div>
                            </div>

                            <div className="form-field negotiation-message-field">
                                <label
                                    className="form-label"
                                    htmlFor={`message-${step.id}`}
                                >
                                    Wiadomość
                                </label>
                                <textarea
                                    id={`message-${step.id}`}
                                    className="form-textarea"
                                    value={step.message}
                                    maxLength={1000}
                                    rows={3}
                                    required
                                    disabled={definitionDisabled}
                                    placeholder="np. Dzień dobry, czy zaakceptuje Pan/Pani taką cenę?"
                                    onChange={(event) => onUpdateStep(
                                        step.id,
                                        "message",
                                        event.target.value,
                                    )}
                                />
                                <div className="textarea-counter">
                                    {step.message.length}/1000
                                </div>
                            </div>

                            <StepResponsePolicy step={step} stepNumber={stepNumber} isLastStep={isLastStep}
                                policyDisabled={policyDisabled} onUpdateStepPolicy={onUpdateStepPolicy}
                                onAddCounterOfferRule={onAddCounterOfferRule} onRemoveCounterOfferRule={onRemoveCounterOfferRule}
                                onUpdateCounterOfferRule={onUpdateCounterOfferRule} />
                        </article>
                    );
                })}
            </div>

            <button
                className="secondary-button add-negotiation-step-button"
                type="button"
                disabled={
                    definitionDisabled
                    || negotiationSteps.length >= 25
                }
                onClick={onAddStep}
            >
                + Dodaj kolejny krok
            </button>

            <div className="negotiation-summary">
                <div>
                    <span>Liczba kroków</span>
                    <strong>{negotiationSteps.length}</strong>
                </div>
                <div>
                    <span>Dzienny budżet</span>
                    <strong>{dailyNegotiationBudget || "—"}</strong>
                </div>
                <div>
                    <span>Maks. nowych pełnych negocjacji</span>
                    <strong>
                        {calculateMaximumFullNegotiations(
                            dailyNegotiationBudget,
                            negotiationSteps.length,
                        )}
                    </strong>
                </div>
            </div>
        </article>
    );
}

function calculateMaximumFullNegotiations(
    budgetValue: string,
    numberOfSteps: number,
): string {
    const budget = Number(budgetValue);
    if (!Number.isInteger(budget)
        || budget <= 0
        || numberOfSteps <= 0) {
        return "—";
    }
    return String(Math.floor(budget / numberOfSteps));
}

export default NegotiationStepsSection;
