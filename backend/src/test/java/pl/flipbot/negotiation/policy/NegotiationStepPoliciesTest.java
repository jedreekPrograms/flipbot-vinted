package pl.flipbot.negotiation.policy;

import org.junit.jupiter.api.Test;
import pl.flipbot.negotiation.NegotiationReactionAction;
import pl.flipbot.negotiation.NegotiationStep;
import pl.flipbot.negotiation.dto.CreateNegotiationStepRequest;
import pl.flipbot.negotiation.dto.SellerCounterOfferRuleRequest;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static pl.flipbot.negotiation.policy.NegotiationStepPolicies.*;

class NegotiationStepPoliciesTest {
    @Test void existingDefaultLadderAndCounterofferRulesArePreserved() {
        Integer[] waits = {null, 6, 12, 24, 24};
        for (int i = 0; i < waits.length; i++) {
            var policy = resolvePolicy(new CreateNegotiationStepRequest(), i + 1);
            assertEquals(waits[i], policy.rejectionWaitHours());
            assertEquals(i == 0 ? NegotiationReactionAction.NEXT_STEP_NOW
                    : NegotiationReactionAction.WAIT_BEFORE_NEXT_STEP, policy.rejectionAction());
            assertEquals(6, policy.counterDefaultWaitHours());
            assertEquals(List.of(new BigDecimal("10"), new BigDecimal("15")),
                    policy.rules().stream().map(CounterRuleValue::minimumDiscountPercent).toList());
            validateResolvedPolicy(policy, i + 1);
        }
    }

    @Test void immediateReactionDiscardsAnIrrelevantWait() {
        var request = new CreateNegotiationStepRequest();
        request.setRejectionAction(NegotiationReactionAction.NEXT_STEP_NOW);
        request.setRejectionWaitHours(500);
        request.setCounterOfferDefaultAction(NegotiationReactionAction.NEXT_STEP_NOW);
        request.setCounterOfferDefaultWaitHours(200);
        var policy = resolvePolicy(request, 3);
        assertNull(policy.rejectionWaitHours());
        assertNull(policy.counterDefaultWaitHours());
    }

    @Test void duplicateThresholdsCannotBypassValidationWithDifferentDecimalScales() {
        var request = new CreateNegotiationStepRequest();
        request.setCounterOfferRules(List.of(rule("10"), rule("10.0")));
        assertThrows(IllegalArgumentException.class,
                () -> validateResolvedPolicy(resolvePolicy(request, 1), 1));
    }

    @Test void persistedPolicyComparisonIgnoresRuleOrderAndNumericScale() {
        var requested = resolvePolicy(new CreateNegotiationStepRequest(), 2);
        var existing = new NegotiationStep();
        existing.setRejectionAction(requested.rejectionAction());
        existing.setRejectionWaitHours(requested.rejectionWaitHours());
        existing.setCounterOfferDefaultAction(requested.counterDefaultAction());
        existing.setCounterOfferDefaultWaitHours(requested.counterDefaultWaitHours());
        existing.setCounterOfferRules(toRuleEntities(List.of(requested.rules().get(1), requested.rules().get(0))));
        existing.getCounterOfferRules().get(0).setMinimumDiscountPercent(new BigDecimal("10.00"));
        assertTrue(samePolicy(existing, requested));
        existing.setRejectionWaitHours(7);
        assertFalse(samePolicy(existing, requested));
    }

    @Test void waitBoundaryIsEnforcedForEveryProduct() {
        var request = new CreateNegotiationStepRequest();
        request.setRejectionAction(NegotiationReactionAction.WAIT_BEFORE_NEXT_STEP);
        request.setRejectionWaitHours(720);
        validateResolvedPolicy(resolvePolicy(request, 1), 1);
        request.setRejectionWaitHours(721);
        assertThrows(IllegalArgumentException.class,
                () -> validateResolvedPolicy(resolvePolicy(request, 1), 1));
    }

    private SellerCounterOfferRuleRequest rule(String threshold) {
        var request = new SellerCounterOfferRuleRequest();
        request.setMinimumDiscountPercent(new BigDecimal(threshold));
        request.setAction(NegotiationReactionAction.NEXT_STEP_NOW);
        return request;
    }
}
