import type { NegotiationStepsSectionProps } from "./NegotiationStepsSection";
import type { NegotiationStepForm } from "./botForm";

type Props = Pick<NegotiationStepsSectionProps, "policyDisabled" | "onUpdateStepPolicy"
    | "onAddCounterOfferRule" | "onRemoveCounterOfferRule" | "onUpdateCounterOfferRule"> & {
    step: NegotiationStepForm; stepNumber: number; isLastStep: boolean;
};

export default function StepResponsePolicy({ step, stepNumber, isLastStep, policyDisabled = false,
    onUpdateStepPolicy, onAddCounterOfferRule, onRemoveCounterOfferRule, onUpdateCounterOfferRule }: Props) {
    return (
        <section className="negotiation-response-policy">
            <div className="negotiation-response-policy-heading">
                <div>
                    <p className="negotiation-response-policy-eyebrow">
                        Reakcja po kroku {stepNumber}
                    </p>
                    <h3>Co bot robi po odpowiedzi sprzedającego?</h3>
                </div>
                <span className="negotiation-policy-badge">
                    Reguły przyszłych akcji
                </span>
            </div>

            {isLastStep && (
                <div className="negotiation-policy-note">
                    To obecnie ostatni krok. Reguły są zapisane,
                    ale bez kolejnego kroku bot nie podbije już
                    automatycznie ceny. Przydadzą się, jeśli
                    później dodasz następny krok.
                </div>
            )}

            <div className="negotiation-policy-grid">
                <div className="negotiation-policy-card">
                    <div className="negotiation-policy-card-title">
                        <span className="negotiation-policy-icon">×</span>
                        <div>
                            <strong>Jeśli sprzedający odrzuci ofertę</strong>
                            <p>
                                Ta sama reguła działa także wtedy,
                                gdy sprzedający przeczyta ofertę
                                albo napisze zwykłą wiadomość,
                                ale oferta nadal ma status
                                „Oczekujące”. W takim przypadku
                                czas liczymy od pierwszej wykrytej
                                reakcji sprzedającego.
                            </p>
                        </div>
                    </div>

                    <ReactionEditor
                        idPrefix={`rejection-${step.id}`}
                        action={step.rejectionAction}
                        waitHours={step.rejectionWaitHours}
                        disabled={policyDisabled}
                        onActionChange={(value) =>
                            onUpdateStepPolicy(
                                step.id,
                                "rejectionAction",
                                value,
                            )
                        }
                        onWaitHoursChange={(value) =>
                            onUpdateStepPolicy(
                                step.id,
                                "rejectionWaitHours",
                                value,
                            )
                        }
                    />
                </div>

                <div className="negotiation-policy-card negotiation-policy-card-wide">
                    <div className="negotiation-policy-card-title">
                        <span className="negotiation-policy-icon">%</span>
                        <div>
                            <strong>Jeśli sprzedający poda własną cenę</strong>
                            <p>
                                Procent zawsze liczymy od
                                <strong> początkowej ceny ogłoszenia</strong>.
                                Np. 1700 zł przy cenie początkowej
                                2000 zł oznacza obniżkę 15%.
                            </p>
                        </div>
                    </div>

                    <div className="counter-rule-fallback">
                        <div>
                            <strong>Gdy żaden próg poniżej nie pasuje</strong>
                            <span>
                                Np. obniżka mniejsza niż 10%, jeśli
                                pierwszy próg ustawisz na 10%.
                            </span>
                        </div>
                        <ReactionEditor
                            idPrefix={`counter-default-${step.id}`}
                            action={step.counterOfferDefaultAction}
                            waitHours={step.counterOfferDefaultWaitHours}
                            disabled={policyDisabled}
                            compact
                            onActionChange={(value) =>
                                onUpdateStepPolicy(
                                    step.id,
                                    "counterOfferDefaultAction",
                                    value,
                                )
                            }
                            onWaitHoursChange={(value) =>
                                onUpdateStepPolicy(
                                    step.id,
                                    "counterOfferDefaultWaitHours",
                                    value,
                                )
                            }
                        />
                    </div>

                    <div className="counter-rules-list">
                        {step.counterOfferRules
                            .slice()
                            .sort((left, right) => {
                                const leftValue = Number(left.minimumDiscountPercent);
                                const rightValue = Number(right.minimumDiscountPercent);
                                if (!Number.isFinite(leftValue)) return 1;
                                if (!Number.isFinite(rightValue)) return -1;
                                return leftValue - rightValue;
                            })
                            .map((rule) => (
                                <div
                                    key={rule.id}
                                    className="counter-rule-row"
                                >
                                    <div className="counter-rule-threshold">
                                        <label
                                            className="form-label"
                                            htmlFor={`discount-threshold-${step.id}-${rule.id}`}
                                        >
                                            Od obniżki
                                        </label>
                                        <div className="input-with-suffix">
                                            <input
                                                id={`discount-threshold-${step.id}-${rule.id}`}
                                                className="form-input"
                                                type="number"
                                                min="0.001"
                                                max="100"
                                                step="any"
                                                disabled={policyDisabled}
                                                value={rule.minimumDiscountPercent}
                                                placeholder="10"
                                                onChange={(event) =>
                                                    onUpdateCounterOfferRule(
                                                        step.id,
                                                        rule.id,
                                                        "minimumDiscountPercent",
                                                        event.target.value,
                                                    )
                                                }
                                            />
                                            <span>%</span>
                                        </div>
                                    </div>

                                    <ReactionEditor
                                        idPrefix={`counter-rule-${step.id}-${rule.id}`}
                                        action={rule.action}
                                        waitHours={rule.waitHours}
                                        disabled={policyDisabled}
                                        compact
                                        onActionChange={(value) =>
                                            onUpdateCounterOfferRule(
                                                step.id,
                                                rule.id,
                                                "action",
                                                value,
                                            )
                                        }
                                        onWaitHoursChange={(value) =>
                                            onUpdateCounterOfferRule(
                                                step.id,
                                                rule.id,
                                                "waitHours",
                                                value,
                                            )
                                        }
                                    />

                                    <button
                                        type="button"
                                        className="danger-text-button counter-rule-remove"
                                        disabled={policyDisabled}
                                        onClick={() =>
                                            onRemoveCounterOfferRule(
                                                step.id,
                                                rule.id,
                                            )
                                        }
                                    >
                                        Usuń
                                    </button>
                                </div>
                            ))}
                    </div>

                    <button
                        type="button"
                        className="secondary-button counter-rule-add"
                        disabled={
                            policyDisabled
                            || step.counterOfferRules.length >= 25
                        }
                        onClick={() => onAddCounterOfferRule(step.id)}
                    >
                        + Dodaj kolejny próg procentowy
                    </button>

                    <p className="negotiation-policy-footnote">
                        Gdy kilka progów pasuje, wygrywa
                        <strong> najwyższy spełniony próg</strong>.
                        Przykład: przy obniżce 18% i progach
                        10% oraz 15% użyta zostanie reguła 15%.
                    </p>
                </div>
            </div>
        </section>
    );
}

interface ReactionEditorProps {
    idPrefix: string;
    action: "NEXT_STEP_NOW" | "WAIT_BEFORE_NEXT_STEP";
    waitHours: string;
    disabled: boolean;
    compact?: boolean;
    onActionChange: (value: string) => void;
    onWaitHoursChange: (value: string) => void;
}

function ReactionEditor({
    idPrefix,
    action,
    waitHours,
    disabled,
    compact = false,
    onActionChange,
    onWaitHoursChange,
}: ReactionEditorProps) {
    return (
        <div className={compact
            ? "reaction-editor reaction-editor-compact"
            : "reaction-editor"
        }>
            <div className="form-field">
                <label className="form-label" htmlFor={`${idPrefix}-action`}>
                    Co robimy?
                </label>
                <select
                    id={`${idPrefix}-action`}
                    className="form-select"
                    disabled={disabled}
                    value={action}
                    onChange={(event) => onActionChange(event.target.value)}
                >
                    <option value="NEXT_STEP_NOW">
                        Wyślij następny krok od razu
                    </option>
                    <option value="WAIT_BEFORE_NEXT_STEP">
                        Poczekaj, potem wyślij następny krok
                    </option>
                </select>
            </div>

            {action === "WAIT_BEFORE_NEXT_STEP" && (
                <div className="form-field reaction-wait-field">
                    <label className="form-label" htmlFor={`${idPrefix}-hours`}>
                        Ile godzin czekamy?
                    </label>
                    <div className="input-with-suffix">
                        <input
                            id={`${idPrefix}-hours`}
                            className="form-input"
                            type="number"
                            min="1"
                            max="720"
                            step="1"
                            required
                            disabled={disabled}
                            value={waitHours}
                            placeholder="np. 6"
                            onChange={(event) => onWaitHoursChange(event.target.value)}
                        />
                        <span>h</span>
                    </div>
                </div>
            )}
        </div>
    );
}
