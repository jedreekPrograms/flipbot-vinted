import ProductConfigurationFields from "../features/bots/form/ProductConfigurationFields";
import { getErrorMessage } from "../shared/errorMessage";
import {
    type FormEvent,
    useState,
} from "react";
import { useNavigate } from "react-router-dom";

import { createBot } from "../api/botsApi";
import BasicInfoSection from "../features/bots/form/BasicInfoSection";
import NegotiationBudgetSection from "../features/bots/form/NegotiationBudgetSection";
import VintedAccountSection from "../features/bots/form/VintedAccountSection";
import { useBotDictionaries } from "../features/bots/form/hooks/useBotDictionaries";
import { useBotProductForm } from "../features/bots/form/hooks/useBotProductForm";
import { buildCreateBotRequest } from "../features/bots/form/mappers/buildCreateBotRequest";
import { validateCreateBotForm } from "../features/bots/form/validation/validateCreateBotForm";

function CreateBotPage() {
    const navigate = useNavigate();

    const controller = useBotProductForm();
    const {
        form,
        setBotName,
        setEmail,
        setPassword,
        setDailyNegotiationBudget,
    } = controller;

    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const [isSubmitting, setIsSubmitting] = useState(false);

    const dictionaries = useBotDictionaries(form.selectedBrandId);
    const {
        categories,
        brands,
        models,
        isLoadingDictionaries,
        dictionaryErrorMessage,
    } = dictionaries;

    const selectedCategory = categories.find(
        (category) => String(category.id) === form.selectedCategoryId,
    ) ?? null;
    const selectedBrand = brands.find(
        (brand) => String(brand.id) === form.selectedBrandId,
    ) ?? null;
    const selectedModel = models.find(
        (model) => String(model.id) === form.selectedModelId,
    ) ?? null;

    function clearMessages() {
        setErrorMessage(null);
    }

    async function handleSubmit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (isSubmitting) {
            return;
        }

        clearMessages();
        const validationResult = validateCreateBotForm({
            form,
            selectedCategory,
            selectedBrand,
            selectedModel,
        });

        if (!validationResult.valid) {
            setErrorMessage(validationResult.errorMessage);
            return;
        }

        setIsSubmitting(true);
        try {
            await createBot(buildCreateBotRequest(validationResult.data));
            navigate("/bots");
        } catch (error) {
            setErrorMessage(
                getErrorMessage(error, "Nie udało się utworzyć bota."),
            );
        } finally {
            setIsSubmitting(false);
        }
    }

    return (
        <section className="page">
            <header className="page-header">
                <div>
                    <p className="page-eyebrow">Konfiguracja</p>
                    <h1 className="page-title">Utwórz bota</h1>
                    <p className="page-description">
                        Jeden bot korzysta z jednego konta Vinted i posiada
                        własne filtry, drabinkę cenową oraz politykę reakcji
                        na zachowanie sprzedającego.
                    </p>
                </div>
            </header>

            <form className="bot-form" onSubmit={handleSubmit}>
                <fieldset className="bot-form-fieldset" disabled={isSubmitting}>
                    <BasicInfoSection
                        botName={form.botName}
                        onBotNameChange={(value) => {
                            setBotName(value);
                            clearMessages();
                        }}
                    />

                    <VintedAccountSection
                        email={form.email}
                        password={form.password}
                        onEmailChange={(value) => {
                            setEmail(value);
                            clearMessages();
                        }}
                        onPasswordChange={(value) => {
                            setPassword(value);
                            clearMessages();
                        }}
                    />

                    <ProductConfigurationFields controller={controller} dictionaries={dictionaries}
                        onError={setErrorMessage}>
                    <NegotiationBudgetSection
                        dailyNegotiationBudget={form.dailyNegotiationBudget}
                        onBudgetChange={(value) => {
                            setDailyNegotiationBudget(value);
                            clearMessages();
                        }}
                    />
                    </ProductConfigurationFields>
                </fieldset>

                {dictionaryErrorMessage !== null && (
                    <div className="form-message form-message-error" role="alert">
                        {dictionaryErrorMessage}
                    </div>
                )}

                {errorMessage !== null && (
                    <div className="form-message form-message-error" role="alert">
                        {errorMessage}
                    </div>
                )}

                <div className="bot-form-actions">
                    <button
                        className="primary-button"
                        type="submit"
                        disabled={isSubmitting || isLoadingDictionaries}
                    >
                        {isSubmitting ? "Tworzenie bota..." : "Utwórz bota"}
                    </button>
                </div>
            </form>
        </section>
    );
}

export default CreateBotPage;
