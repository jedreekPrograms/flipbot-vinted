import type { ReactNode } from "react";
import BotFiltersSection from "./BotFiltersSection";
import OfferStrategySection from "./OfferStrategySection";
import NegotiationStepsSection from "./NegotiationStepsSection";
import type { useBotProductForm } from "./hooks/useBotProductForm";
import type { useBotDictionaries } from "./hooks/useBotDictionaries";

type FormController = ReturnType<typeof useBotProductForm>;
type Dictionaries = ReturnType<typeof useBotDictionaries>;

interface Props {
    controller: FormController;
    dictionaries: Dictionaries;
    onError: (message: string | null) => void;
    targetFieldsDisabled?: boolean;
    targetModeEditable?: boolean;
    minimumNegotiationCap?: number | null;
    children?: ReactNode;
}

export default function ProductConfigurationFields({ controller: c, dictionaries: d, onError,
    targetFieldsDisabled = false, targetModeEditable = true, minimumNegotiationCap, children }: Props) {
    const { form } = c;
    function change<T>(update: (value: T) => void) {
        return (value: T) => { update(value); onError(null); };
    }
    function checked(action: () => boolean, message: string) {
        onError(action() ? null : message);
    }
    return <>
        <BotFiltersSection
            categories={d.categories} brands={d.brands} models={d.models}
            selectedCategoryId={form.selectedCategoryId} selectedBrandId={form.selectedBrandId}
            targetMode={form.targetMode} selectedModelId={form.selectedModelId} searchQuery={form.searchQuery}
            minPrice={form.minPrice} maxPrice={form.maxPrice}
            isLoadingDictionaries={d.isLoadingDictionaries} areModelsLoading={d.areModelsLoading}
            targetFieldsDisabled={targetFieldsDisabled}
            onCategoryChange={change(c.setCategory)}
            onBrandChange={value => { c.setBrand(value); onError(null); d.clearDictionaryError(); }}
            onTargetModeChange={targetModeEditable ? change(c.setTargetMode) : () => undefined}
            onSearchQueryChange={targetModeEditable ? change(c.setSearchQuery) : () => undefined}
            onModelChange={change(c.setModel)} onMinPriceChange={change(c.setMinPrice)} onMaxPriceChange={change(c.setMaxPrice)}
        />
        {children}
        <OfferStrategySection autoRaiseOfferToVintedMinimum={form.autoRaiseOfferToVintedMinimum}
            maxAutomaticOffer={form.maxAutomaticOffer} firstConfiguredOffer={form.negotiationSteps[0]?.offerPrice ?? ""}
            minimumNegotiationCap={minimumNegotiationCap}
            onAutoRaiseChange={change(c.setAutoRaiseOfferToVintedMinimum)} onMaxAutomaticOfferChange={change(c.setMaxAutomaticOffer)} />
        <NegotiationStepsSection negotiationSteps={form.negotiationSteps} dailyNegotiationBudget={form.dailyNegotiationBudget}
            onAddStep={() => checked(c.addNegotiationStep, "Nie możesz dodać więcej niż 25 kroków negocjacji.")}
            onRemoveStep={id => checked(() => c.removeNegotiationStep(id), "Bot musi mieć przynajmniej jeden krok negocjacji.")}
            onUpdateStep={(id, field, value) => { c.updateNegotiationStep(id, field, value); onError(null); }}
            onUpdateStepPolicy={(id, field, value) => { c.updateNegotiationStepPolicy(id, field, value); onError(null); }}
            onAddCounterOfferRule={id => checked(() => c.addCounterOfferRule(id), "Jeden krok może mieć maksymalnie 25 progów procentowych.")}
            onRemoveCounterOfferRule={(step, rule) => { c.removeCounterOfferRule(step, rule); onError(null); }}
            onUpdateCounterOfferRule={(step, rule, field, value) => { c.updateCounterOfferRule(step, rule, field, value); onError(null); }} />
    </>;
}
