import type { CreateBotFormValues, NegotiationStepForm } from "./botForm";
import type { NegotiationReactionAction } from "../../../types/bots";

export function createInitialForm(): CreateBotFormValues {
    return {
        botName: "",
        email: "",
        password: "",
        selectedCategoryId: "",
        selectedBrandId: "",
        targetMode: "VINTED_MODEL",
        selectedModelId: "",
        searchQuery: "",
        minPrice: "",
        maxPrice: "",
        autoRaiseOfferToVintedMinimum: false,
        maxAutomaticOffer: "",
        dailyNegotiationBudget: "25",
        negotiationSteps: [createNegotiationStep(1, 1, 1)],
    };
}

function defaultRejectionPolicy(stepNumber: number): {
    action: NegotiationReactionAction;
    waitHours: string;
} {
    if (stepNumber === 1) {
        return {
            action: "NEXT_STEP_NOW",
            waitHours: "",
        };
    }

    if (stepNumber === 2) {
        return {
            action: "WAIT_BEFORE_NEXT_STEP",
            waitHours: "6",
        };
    }

    if (stepNumber === 3) {
        return {
            action: "WAIT_BEFORE_NEXT_STEP",
            waitHours: "12",
        };
    }

    return {
        action: "WAIT_BEFORE_NEXT_STEP",
        waitHours: "24",
    };
}


export function createNegotiationStep(id: number, stepNumber: number, firstRuleId: number): NegotiationStepForm {
    const rejection = defaultRejectionPolicy(stepNumber);
    return {
        id, offerPrice: "", maxAcceptedCounterOffer: "", message: "",
        rejectionAction: rejection.action, rejectionWaitHours: rejection.waitHours,
        counterOfferDefaultAction: "WAIT_BEFORE_NEXT_STEP", counterOfferDefaultWaitHours: "6",
        counterOfferRules: [
            { id: firstRuleId, minimumDiscountPercent: "10", action: "WAIT_BEFORE_NEXT_STEP", waitHours: "2" },
            { id: firstRuleId + 1, minimumDiscountPercent: "15", action: "NEXT_STEP_NOW", waitHours: "" },
        ],
    };
}
