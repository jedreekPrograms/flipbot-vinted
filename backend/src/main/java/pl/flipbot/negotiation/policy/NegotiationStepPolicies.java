package pl.flipbot.negotiation.policy;

import pl.flipbot.exception.InvalidRequestException;
import pl.flipbot.negotiation.NegotiationReactionAction;
import pl.flipbot.negotiation.NegotiationStep;
import pl.flipbot.negotiation.SellerCounterOfferRule;
import pl.flipbot.negotiation.dto.CreateNegotiationStepRequest;
import pl.flipbot.negotiation.dto.SellerCounterOfferRuleRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The same negotiation defaults, validation and comparison for every product. */
public final class NegotiationStepPolicies {
    private static final int MAX_RESPONSE_WAIT_HOURS = 24 * 30;
    private static final BigDecimal MAX_DISCOUNT_PERCENT = new BigDecimal("100");
    private NegotiationStepPolicies() { }

    public static ResolvedStepPolicy resolvePolicy(CreateNegotiationStepRequest request, int stepNumber) {
        NegotiationReactionAction rejection = request.getRejectionAction();
        Integer rejectionWait = request.getRejectionWaitHours();
        if (rejection == null) {
            if (stepNumber == 1) {
                rejection = NegotiationReactionAction.NEXT_STEP_NOW;
                rejectionWait = null;
            } else {
                rejection = NegotiationReactionAction.WAIT_BEFORE_NEXT_STEP;
                rejectionWait = defaultRejectionWaitHours(stepNumber);
            }
        }
        if (rejection == NegotiationReactionAction.NEXT_STEP_NOW) rejectionWait = null;
        NegotiationReactionAction counterDefault = request.getCounterOfferDefaultAction();
        Integer counterWait = request.getCounterOfferDefaultWaitHours();
        if (counterDefault == null) {
            counterDefault = NegotiationReactionAction.WAIT_BEFORE_NEXT_STEP;
            counterWait = 6;
        }
        if (counterDefault == NegotiationReactionAction.NEXT_STEP_NOW) counterWait = null;
        List<CounterRuleValue> rules = request.getCounterOfferRules() == null
                ? defaultCounterOfferRules()
                : request.getCounterOfferRules().stream().filter(Objects::nonNull).map(NegotiationStepPolicies::toRuleValue).toList();
        return new ResolvedStepPolicy(rejection, rejectionWait, counterDefault, counterWait, rules);
    }

    private static CounterRuleValue toRuleValue(SellerCounterOfferRuleRequest request) {
        NegotiationReactionAction action = request.getAction();
        Integer wait = request.getWaitHours();
        if (action == NegotiationReactionAction.NEXT_STEP_NOW) wait = null;
        return new CounterRuleValue(request.getMinimumDiscountPercent(), action, wait);
    }

    private static int defaultRejectionWaitHours(int stepNumber) {
        if (stepNumber == 2) return 6;
        if (stepNumber == 3) return 12;
        return 24;
    }

    private static List<CounterRuleValue> defaultCounterOfferRules() {
        return List.of(
                new CounterRuleValue(new BigDecimal("10"), NegotiationReactionAction.WAIT_BEFORE_NEXT_STEP, 2),
                new CounterRuleValue(new BigDecimal("15"), NegotiationReactionAction.NEXT_STEP_NOW, null));
    }

    public static List<SellerCounterOfferRule> toRuleEntities(List<CounterRuleValue> rules) {
        return rules.stream()
                .sorted(Comparator.comparing(CounterRuleValue::minimumDiscountPercent))
                .map(rule -> SellerCounterOfferRule.builder()
                        .minimumDiscountPercent(rule.minimumDiscountPercent())
                        .action(rule.action()).waitHours(rule.waitHours()).build())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public static boolean samePolicy(NegotiationStep existing, ResolvedStepPolicy requested) {
        if (existing.getRejectionAction() != requested.rejectionAction()
                || !Objects.equals(existing.getRejectionWaitHours(), requested.rejectionWaitHours())
                || existing.getCounterOfferDefaultAction() != requested.counterDefaultAction()
                || !Objects.equals(existing.getCounterOfferDefaultWaitHours(), requested.counterDefaultWaitHours())) return false;
        List<CounterRuleValue> left = existing.getCounterOfferRules().stream()
                .map(rule -> new CounterRuleValue(rule.getMinimumDiscountPercent(), rule.getAction(), rule.getWaitHours()))
                .sorted(Comparator.comparing(CounterRuleValue::minimumDiscountPercent)).toList();
        List<CounterRuleValue> right = requested.rules().stream()
                .sorted(Comparator.comparing(CounterRuleValue::minimumDiscountPercent)).toList();
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            CounterRuleValue a = left.get(i);
            CounterRuleValue b = right.get(i);
            if (!sameDecimal(a.minimumDiscountPercent(), b.minimumDiscountPercent())
                    || a.action() != b.action()
                    || !Objects.equals(a.waitHours(), b.waitHours())) return false;
        }
        return true;
    }

    public static void validateResolvedPolicy(
            ResolvedStepPolicy policy,
            int stepNumber
    ) {
        validateReaction(
                policy.rejectionAction(),
                policy.rejectionWaitHours(),
                "Krok " + stepNumber + " po odrzuceniu"
        );
        validateReaction(
                policy.counterDefaultAction(),
                policy.counterDefaultWaitHours(),
                "Krok " + stepNumber + " domyślna kontroferta"
        );

        Set<String> thresholds = new HashSet<>();
        for (CounterRuleValue rule : policy.rules()) {
            if (rule.minimumDiscountPercent() == null
                    || rule.minimumDiscountPercent().signum() <= 0
                    || rule.minimumDiscountPercent().compareTo(MAX_DISCOUNT_PERCENT) > 0) {
                throw new InvalidRequestException(
                        "Próg procentowy kroku " + stepNumber
                                + " musi być większy od 0 i nie większy niż 100%."
                );
            }
            String normalized = rule.minimumDiscountPercent()
                    .stripTrailingZeros()
                    .toPlainString();
            if (!thresholds.add(normalized)) {
                throw new InvalidRequestException(
                        "Krok " + stepNumber + " zawiera powtórzony próg "
                                + normalized + "%."
                );
            }
            validateReaction(
                    rule.action(),
                    rule.waitHours(),
                    "Krok " + stepNumber + " próg " + normalized + "%"
            );
        }
    }

    private static void validateReaction(
            NegotiationReactionAction action,
            Integer waitHours,
            String label
    ) {
        if (action == null) {
            throw new InvalidRequestException(label + " nie ma ustawionej akcji.");
        }
        if (action == NegotiationReactionAction.WAIT_BEFORE_NEXT_STEP
                && (waitHours == null
                || waitHours < 1
                || waitHours > MAX_RESPONSE_WAIT_HOURS)) {
            throw new InvalidRequestException(
                    label + " wymaga czasu 1-720 godzin."
            );
        }
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? left == right : left.compareTo(right) == 0;
    }

    public record ResolvedStepPolicy(
            NegotiationReactionAction rejectionAction,
            Integer rejectionWaitHours,
            NegotiationReactionAction counterDefaultAction,
            Integer counterDefaultWaitHours,
            List<CounterRuleValue> rules
    ) {}

    public record CounterRuleValue(BigDecimal minimumDiscountPercent, NegotiationReactionAction action, Integer waitHours) {}
}
