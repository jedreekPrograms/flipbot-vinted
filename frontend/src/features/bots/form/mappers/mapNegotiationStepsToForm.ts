import type { BotNegotiationStep } from "../../../../types/bots";
import type { NegotiationStepForm } from "../botForm";

export function mapNegotiationStepsToForm(steps: BotNegotiationStep[]): NegotiationStepForm[] {
    return steps
        .slice()
        .sort((left, right) => left.stepNumber - right.stepNumber)
        .map((step, index) => ({
            id: index + 1,
            offerPrice: String(step.offerPrice),
            maxAcceptedCounterOffer: step.maxAcceptedCounterOffer === null
                ? ""
                : String(step.maxAcceptedCounterOffer),
            message: step.message,
            rejectionAction: step.rejectionAction ?? "NEXT_STEP_NOW",
            rejectionWaitHours: step.rejectionWaitHours === null
                ? ""
                : String(step.rejectionWaitHours),
            counterOfferDefaultAction:
                step.counterOfferDefaultAction ?? "WAIT_BEFORE_NEXT_STEP",
            counterOfferDefaultWaitHours:
                step.counterOfferDefaultWaitHours === null
                    ? ""
                    : String(step.counterOfferDefaultWaitHours),
            counterOfferRules: (step.counterOfferRules ?? []).map(
                (rule, ruleIndex) => ({
                    id: (index + 1) * 100 + ruleIndex + 1,
                    minimumDiscountPercent: String(rule.minimumDiscountPercent),
                    action: rule.action,
                    waitHours: rule.waitHours === null
                        ? ""
                        : String(rule.waitHours),
                }),
            ),
        }));
}
