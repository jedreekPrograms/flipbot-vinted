package pl.flipbot.bot.configuration;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import pl.flipbot.negotiation.NegotiationStep;
import pl.flipbot.negotiation.dto.CreateNegotiationStepRequest;

import static pl.flipbot.negotiation.policy.NegotiationStepPolicies.resolvePolicy;
import static pl.flipbot.negotiation.policy.NegotiationStepPolicies.samePolicy;
import static pl.flipbot.negotiation.policy.NegotiationStepPolicies.toRuleEntities;

/** Edits product steps in place; transactions and strategy versions belong to the caller. */
public final class ProductNegotiationSteps {
    private final List<NegotiationStep> steps;
    private final Consumer<NegotiationStep> assignOwner;
    private final UnaryOperator<String> messageText;
    private final String structureChangedMessage;

    private ProductNegotiationSteps(List<NegotiationStep> steps, Consumer<NegotiationStep> assignOwner,
                                    UnaryOperator<String> messageText, String structureChangedMessage) {
        this.steps = steps;
        this.assignOwner = assignOwner;
        this.messageText = messageText;
        this.structureChangedMessage = structureChangedMessage;
    }

    public static ProductNegotiationSteps forMainProduct(BotConfiguration configuration) {
        return new ProductNegotiationSteps(configuration.getNegotiationSteps(),
                step -> step.setConfiguration(configuration), UnaryOperator.identity(),
                "Cannot apply response policies because negotiation step structure changed.");
    }

    public static ProductNegotiationSteps forAdditionalProduct(BotAdditionalTarget target) {
        return new ProductNegotiationSteps(target.getNegotiationSteps(), step -> step.setAdditionalTarget(target),
                message -> message == null ? "" : message.trim().replaceAll("\\s+", " "),
                "Nie można zapisać polityk reakcji, ponieważ zmieniła się struktura kroków.");
    }

    public boolean definitionChanged(List<CreateNegotiationStepRequest> requested) {
        List<NegotiationStep> existing = orderedSteps();
        if (requested == null || existing.size() != requested.size()) return true;
        for (int index = 0; index < existing.size(); index++) {
            NegotiationStep current = existing.get(index);
            CreateNegotiationStepRequest next = requested.get(index);
            if (!Objects.equals(current.getStepNumber(), index + 1)
                    || !sameDecimal(current.getOfferPrice(), next.getOfferPrice())
                    || !sameDecimal(current.getMaxAcceptedCounterOffer(), next.getMaxAcceptedCounterOffer())
                    || !Objects.equals(messageText.apply(current.getMessage()), messageText.apply(next.getMessage()))) {
                return true;
            }
        }
        return false;
    }

    public boolean policiesChanged(List<CreateNegotiationStepRequest> requested) {
        List<NegotiationStep> existing = orderedSteps();
        if (requested == null || existing.size() != requested.size()) return true;
        for (int index = 0; index < existing.size(); index++) {
            if (!samePolicy(existing.get(index), resolvePolicy(requested.get(index), index + 1))) return true;
        }
        return false;
    }

    public void replace(List<CreateNegotiationStepRequest> requested) {
        steps.clear();
        for (int index = 0; index < requested.size(); index++) {
            CreateNegotiationStepRequest request = requested.get(index);
            NegotiationStep step = NegotiationStep.builder()
                    .stepNumber(index + 1)
                    .offerPrice(request.getOfferPrice())
                    .maxAcceptedCounterOffer(request.getMaxAcceptedCounterOffer())
                    .message(messageText.apply(request.getMessage()))
                    .build();
            applyPolicy(step, request, index + 1);
            assignOwner.accept(step);
            steps.add(step);
        }
    }

    public void applyPolicies(List<CreateNegotiationStepRequest> requested) {
        List<NegotiationStep> existing = orderedSteps();
        if (existing.size() != requested.size()) throw new IllegalStateException(structureChangedMessage);
        for (int index = 0; index < existing.size(); index++) {
            applyPolicy(existing.get(index), requested.get(index), index + 1);
        }
    }

    private void applyPolicy(NegotiationStep step, CreateNegotiationStepRequest request, int stepNumber) {
        var policy = resolvePolicy(request, stepNumber);
        step.setRejectionAction(policy.rejectionAction());
        step.setRejectionWaitHours(policy.rejectionWaitHours());
        step.setCounterOfferDefaultAction(policy.counterDefaultAction());
        step.setCounterOfferDefaultWaitHours(policy.counterDefaultWaitHours());
        step.getCounterOfferRules().clear();
        step.getCounterOfferRules().addAll(toRuleEntities(policy.rules()));
    }

    private List<NegotiationStep> orderedSteps() {
        return steps.stream().sorted(Comparator.comparing(
                step -> step.getStepNumber() == null ? Integer.MAX_VALUE : step.getStepNumber())).toList();
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? left == right : left.compareTo(right) == 0;
    }
}
