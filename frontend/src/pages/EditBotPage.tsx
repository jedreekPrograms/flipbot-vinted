import ProductConfigurationFields from "../features/bots/form/ProductConfigurationFields";
import { resolveTargetMode, categoryPathsEqual, normalizedText } from "../features/bots/form/targetIdentity";
import { getErrorMessage } from "../shared/errorMessage";
import { mapNegotiationStepsToForm } from "../features/bots/form/mappers/mapNegotiationStepsToForm";
import {
    type FormEvent,
    useEffect,
    useState,
} from "react";
import {
    Link,
    useNavigate,
    useParams,
} from "react-router-dom";

import {
    getBot,
    getBotEditCapabilities,
    updateBot,
} from "../api/botsApi";
import BasicInfoSection from "../features/bots/form/BasicInfoSection";
import NegotiationBudgetSection from "../features/bots/form/NegotiationBudgetSection";
import VintedAccountSection from "../features/bots/form/VintedAccountSection";
import type {
    CreateBotFormValues,
} from "../features/bots/form/botForm";
import type {
    BotDetails,
    BotEditCapabilities,
} from "../types/bots";
import { useBotDictionaries } from "../features/bots/form/hooks/useBotDictionaries";
import { useBotProductForm } from "../features/bots/form/hooks/useBotProductForm";
import { buildCreateBotRequest } from "../features/bots/form/mappers/buildCreateBotRequest";
import { validateCreateBotForm } from "../features/bots/form/validation/validateCreateBotForm";

function EditBotPage() {
    const navigate = useNavigate();
    const { botId: botIdParam } = useParams<{ botId: string }>();
    const botId = Number(botIdParam);
    const isBotIdValid = Number.isInteger(botId) && botId > 0;

    const controller = useBotProductForm();
    const {
        form,
        setBotName,
        setEmail,
        setPassword,
        setModel,
        setDailyNegotiationBudget,
        replaceForm,
    } = controller;

    const [bot, setBot] = useState<BotDetails | null>(null);
    const [editCapabilities, setEditCapabilities] =
        useState<BotEditCapabilities | null>(null);
    const [isLoadingBot, setIsLoadingBot] = useState(true);
    const [isBaseInitialized, setIsBaseInitialized] = useState(false);
    const [isModelResolved, setIsModelResolved] = useState(false);
    const [initializationError, setInitializationError] =
        useState<string | null>(null);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const [isSubmitting, setIsSubmitting] = useState(false);

    const dictionaries = useBotDictionaries(form.selectedBrandId);
    const {
        categories,
        brands,
        models,
        isLoadingDictionaries,
        areModelsLoading,
        modelsBrandId,
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

    const isStopped = bot !== null
        && bot.status.toUpperCase() === "STOPPED";
    const hasActiveNegotiations =
        editCapabilities?.hasActiveNegotiations ?? false;
    const hasMainProductActiveNegotiations =
        editCapabilities?.hasMainProductActiveNegotiations ?? false;
    const isFormInitialized = isBaseInitialized
        && isModelResolved
        && editCapabilities !== null
        && initializationError === null;

    useEffect(() => {
        setBot(null);
        setEditCapabilities(null);
        setIsBaseInitialized(false);
        setIsModelResolved(false);
        setInitializationError(null);
        setErrorMessage(null);

        if (!isBotIdValid) {
            setIsLoadingBot(false);
            setInitializationError(
                "Identyfikator bota w adresie jest nieprawidłowy.",
            );
            return;
        }

        let cancelled = false;

        async function loadBot() {
            setIsLoadingBot(true);
            try {
                const [loadedBot, loadedCapabilities] = await Promise.all([
                    getBot(botId),
                    getBotEditCapabilities(botId),
                ]);
                if (!cancelled) {
                    setBot(loadedBot);
                    setEditCapabilities(loadedCapabilities);
                }
            } catch (error) {
                if (!cancelled) {
                    setInitializationError(
                        getErrorMessage(
                            error,
                            "Nie udało się pobrać konfiguracji i zasad edycji bota.",
                        ),
                    );
                }
            } finally {
                if (!cancelled) {
                    setIsLoadingBot(false);
                }
            }
        }

        void loadBot();
        return () => {
            cancelled = true;
        };
    }, [botId, isBotIdValid]);

    useEffect(() => {
        if (bot === null
            || isLoadingDictionaries
            || isBaseInitialized
            || initializationError !== null) {
            return;
        }

        const matchingCategory = categories.find((category) =>
            categoryPathsEqual(
                category.categoryPath,
                bot.configuration.categoryPath,
            ),
        );
        if (matchingCategory === undefined) {
            setInitializationError(
                "Nie udało się odnaleźć zapisanej kategorii bota w aktualnym słowniku.",
            );
            setIsBaseInitialized(true);
            setIsModelResolved(true);
            return;
        }

        const matchingBrand = brands.find((brand) =>
            normalizedText(brand.name)
            === normalizedText(bot.configuration.brand),
        );
        if (matchingBrand === undefined) {
            setInitializationError(
                "Nie udało się odnaleźć zapisanej marki bota w aktualnym słowniku.",
            );
            setIsBaseInitialized(true);
            setIsModelResolved(true);
            return;
        }

        const targetMode = resolveTargetMode(bot.configuration);
        const steps = mapNegotiationStepsToForm(bot.configuration.negotiationSteps);

        const initialForm: CreateBotFormValues = {
            botName: bot.name,
            email: bot.email,
            password: "",
            selectedCategoryId: String(matchingCategory.id),
            selectedBrandId: String(matchingBrand.id),
            targetMode,
            selectedModelId: "",
            searchQuery: bot.configuration.searchQuery ?? "",
            minPrice: String(bot.configuration.minPrice),
            maxPrice: String(bot.configuration.maxPrice),
            autoRaiseOfferToVintedMinimum: Boolean(
                bot.configuration.autoRaiseOfferToVintedMinimum,
            ),
            maxAutomaticOffer: bot.configuration.maxAutomaticOffer === null
                ? ""
                : String(bot.configuration.maxAutomaticOffer),
            dailyNegotiationBudget: String(
                bot.configuration.dailyNegotiationBudget,
            ),
            negotiationSteps: steps.length > 0
                ? steps
                : [],
        };

        replaceForm(initialForm);
        setIsBaseInitialized(true);
    }, [
        bot,
        brands,
        categories,
        initializationError,
        isBaseInitialized,
        isLoadingDictionaries,
        replaceForm,
    ]);

    useEffect(() => {
        if (bot === null
            || !isBaseInitialized
            || isModelResolved
            || form.selectedBrandId.length === 0
            || modelsBrandId !== form.selectedBrandId
            || areModelsLoading) {
            return;
        }

        const targetMode = resolveTargetMode(bot.configuration);
        const configuredTargetName = targetMode === "SEARCH_QUERY"
            ? bot.configuration.searchQuery
            : bot.configuration.model;

        if (configuredTargetName === null
            || configuredTargetName.trim().length === 0) {
            setInitializationError(
                "Bot nie ma poprawnie zapisanego modelu / frazy wyszukiwania.",
            );
            setIsModelResolved(true);
            return;
        }

        const matchingModel = models.find((model) =>
            normalizedText(model.name) === normalizedText(configuredTargetName)
            && model.targetMode === targetMode,
        );
        if (matchingModel === undefined) {
            setInitializationError(
                `Nie znaleziono w słowniku modelu „${configuredTargetName}” z trybem ${
                    targetMode === "SEARCH_QUERY"
                        ? "wyszukiwanie tekstowe"
                        : "filtr Vinted"
                }. Dodaj lub popraw ten model w Słownikach przed edycją bota.`,
            );
            setIsModelResolved(true);
            return;
        }

        setModel(String(matchingModel.id));
        setIsModelResolved(true);
    }, [
        areModelsLoading,
        bot,
        form.selectedBrandId,
        isBaseInitialized,
        isModelResolved,
        models,
        modelsBrandId,
        setModel,
    ]);

    function clearMessages() {
        setErrorMessage(null);
    }

    async function handleSubmit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (!isStopped || !isFormInitialized || isSubmitting) {
            return;
        }

        clearMessages();

        const validationResult = validateCreateBotForm({
            form,
            selectedCategory,
            selectedBrand,
            selectedModel,
            requirePassword: false,
        });
        if (!validationResult.valid) {
            setErrorMessage(validationResult.errorMessage);
            return;
        }

        setIsSubmitting(true);
        try {
            await updateBot(botId, buildCreateBotRequest(validationResult.data));
            navigate("/bots");
        } catch (error) {
            setErrorMessage(
                getErrorMessage(error, "Nie udało się zapisać zmian bota."),
            );
        } finally {
            setIsSubmitting(false);
        }
    }

    if (isLoadingBot || isLoadingDictionaries) {
        return (
            <section className="page">
                <article className="content-card">
                    <div className="dictionary-list-state">
                        Pobieranie konfiguracji bota...
                    </div>
                </article>
            </section>
        );
    }

    if (initializationError !== null) {
        return (
            <section className="page">
                <div className="form-message form-message-error" role="alert">
                    {initializationError}
                </div>
                <Link className="secondary-button" to="/bots">Wróć do botów</Link>
            </section>
        );
    }

    if (bot === null || editCapabilities === null) {
        return null;
    }

    return (
        <section className="page">
            <header className="page-header">
                <div>
                    <p className="page-eyebrow">Konfiguracja</p>
                    <h1 className="page-title">Edytuj bota</h1>
                    <p className="page-description">
                        Bot musi być zatrzymany podczas zapisu. Aktywne rozmowy
                        zachowują własną wersję strategii negocjacji, więc możesz
                        zmieniać ceny, limity, wiadomości i reguły dla nowych rozmów.
                        Wspólne konto Vinted i tożsamość produktu pozostają chronione.
                    </p>
                </div>
            </header>

            {!isStopped && (
                <div className="form-message form-message-error" role="alert">
                    Zatrzymaj bota przed edycją konfiguracji.
                </div>
            )}

            {hasMainProductActiveNegotiations ? (
                <div className="information-box">
                    <strong>Aktywne negocjacje produktu głównego:</strong>{" "}
                    aktualne rozmowy pozostają na przypiętej wersji strategii.
                    Możesz zmienić tryb adaptacyjny, globalny limit, ceny i progi
                    kroków, wiadomości, liczbę kroków oraz reguły reakcji — zapis
                    utworzy kolejną wersję dla nowych negocjacji. Kategoria, marka,
                    model/fraza oraz wspólne konto Vinted pozostają zablokowane.
                </div>
            ) : hasActiveNegotiations ? (
                <div className="information-box">
                    <strong>Aktywna rozmowa dodatkowego produktu:</strong>{" "}
                    konto Vinted pozostaje zablokowane, bo jest wspólne dla całego
                    bota. Produkt główny nie ma własnej aktywnej negocjacji, więc
                    jego cel/model i strategię możesz edytować normalnie.
                </div>
            ) : null}

            <div className="information-box">
                <strong>Aktualna strategia: v{bot.configuration.negotiationStrategyVersion}.</strong>{" "}
                Zmiana cen, limitów, wiadomości, trybu adaptacyjnego lub reguł
                negocjacji tworzy następną wersję. Rozpoczęte rozmowy nigdy nie
                przełączają się automatycznie na nowszą wersję.
            </div>

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

            <form className="bot-form" onSubmit={handleSubmit}>
                <fieldset
                    className="bot-form-fieldset"
                    disabled={!isStopped || !isFormInitialized || isSubmitting}
                >
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
                        passwordOptional
                        disabled={hasActiveNegotiations}
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
                        onError={setErrorMessage}
                        targetFieldsDisabled={hasMainProductActiveNegotiations} targetModeEditable={false}
                        minimumNegotiationCap={editCapabilities.minimumNegotiationCap}>
                    <NegotiationBudgetSection
                        dailyNegotiationBudget={form.dailyNegotiationBudget}
                        onBudgetChange={(value) => {
                            setDailyNegotiationBudget(value);
                            clearMessages();
                        }}
                    />
                    </ProductConfigurationFields>
                </fieldset>

                <div className="bot-form-actions">
                    <Link className="secondary-button" to="/bots">Anuluj</Link>
                    <button
                        className="primary-button"
                        type="submit"
                        disabled={!isStopped || !isFormInitialized || isSubmitting}
                    >
                        {isSubmitting ? "Zapisywanie..." : "Zapisz zmiany"}
                    </button>
                </div>
            </form>
        </section>
    );
}

export default EditBotPage;
