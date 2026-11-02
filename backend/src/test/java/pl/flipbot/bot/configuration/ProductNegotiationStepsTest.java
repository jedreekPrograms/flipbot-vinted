package pl.flipbot.bot.configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.flipbot.negotiation.NegotiationReactionAction;
import pl.flipbot.negotiation.NegotiationStep;
import pl.flipbot.negotiation.dto.CreateNegotiationStepRequest;

import static org.junit.jupiter.api.Assertions.*;

class ProductNegotiationStepsTest {
    @Test
    void replacementPreservesJpaCollectionAndAssignsOnlyTheCorrectProductOwner() {
        BotConfiguration main = BotConfiguration.builder().negotiationSteps(new ArrayList<>()).build();
        BotAdditionalTarget additional = BotAdditionalTarget.builder().negotiationSteps(new ArrayList<>()).build();
        var mainCollection = main.getNegotiationSteps();
        var additionalCollection = additional.getNegotiationSteps();
        var request = request("  Cześć,\n  kupię  ");

        ProductNegotiationSteps.forMainProduct(main).replace(List.of(request));
        ProductNegotiationSteps.forAdditionalProduct(additional).replace(List.of(request));

        assertSame(mainCollection, main.getNegotiationSteps());
        assertSame(additionalCollection, additional.getNegotiationSteps());
        NegotiationStep mainStep = mainCollection.getFirst();
        NegotiationStep additionalStep = additionalCollection.getFirst();
        assertSame(main, mainStep.getConfiguration());
        assertNull(mainStep.getAdditionalTarget());
        assertSame(additional, additionalStep.getAdditionalTarget());
        assertNull(additionalStep.getConfiguration());
        assertEquals(request.getMessage(), mainStep.getMessage());
        assertEquals("Cześć, kupię", additionalStep.getMessage());
        assertEquals(2, mainStep.getCounterOfferRules().size());
        assertEquals(2, additionalStep.getCounterOfferRules().size());
    }

    @Test
    void messageComparisonRetainsDifferentMainAndAdditionalProductRules() {
        BotConfiguration main = BotConfiguration.builder().negotiationSteps(new ArrayList<>()).build();
        BotAdditionalTarget additional = BotAdditionalTarget.builder().negotiationSteps(new ArrayList<>()).build();
        var mainEditor = ProductNegotiationSteps.forMainProduct(main);
        var additionalEditor = ProductNegotiationSteps.forAdditionalProduct(additional);
        mainEditor.replace(List.of(request("Cześć, kupię")));
        additionalEditor.replace(List.of(request("Cześć, kupię")));
        var request = request("  Cześć,\n kupię ");
        request.setOfferPrice(new BigDecimal("100.0"));
        request.setMaxAcceptedCounterOffer(new BigDecimal("110.000"));

        assertTrue(mainEditor.definitionChanged(List.of(request)));
        assertFalse(additionalEditor.definitionChanged(List.of(request)));
        request.setMessage("Cześć, kupię");
        assertFalse(mainEditor.definitionChanged(List.of(request)));
        request.setMessage("Cześć, KUPIĘ");
        assertTrue(additionalEditor.definitionChanged(List.of(request)));
    }

    @Test
    void policyOnlyEditKeepsStepIdentityPricesMessageOwnerAndManagedRuleCollection() {
        BotConfiguration main = BotConfiguration.builder().negotiationSteps(new ArrayList<>()).build();
        var editor = ProductNegotiationSteps.forMainProduct(main);
        editor.replace(List.of(request("Wiadomość")));
        NegotiationStep step = main.getNegotiationSteps().getFirst();
        step.setId(41L);
        var rules = step.getCounterOfferRules();
        var request = request("Wiadomość");
        request.setRejectionAction(NegotiationReactionAction.WAIT_BEFORE_NEXT_STEP);
        request.setRejectionWaitHours(12);
        request.setCounterOfferRules(List.of());
        assertTrue(editor.policiesChanged(List.of(request)));

        editor.applyPolicies(List.of(request));

        assertSame(step, main.getNegotiationSteps().getFirst());
        assertEquals(41L, step.getId());
        assertEquals(new BigDecimal("100.00"), step.getOfferPrice());
        assertEquals(new BigDecimal("110.00"), step.getMaxAcceptedCounterOffer());
        assertEquals("Wiadomość", step.getMessage());
        assertSame(main, step.getConfiguration());
        assertSame(rules, step.getCounterOfferRules());
        assertTrue(rules.isEmpty());
        assertEquals(12, step.getRejectionWaitHours());
        assertFalse(editor.policiesChanged(List.of(request)));
    }

    @Test
    void structurallyDifferentPolicyUpdateFailsBeforeModifyingAnyStep() {
        BotAdditionalTarget additional = BotAdditionalTarget.builder().negotiationSteps(new ArrayList<>()).build();
        var editor = ProductNegotiationSteps.forAdditionalProduct(additional);
        editor.replace(List.of(request("Pierwsza"), request("Druga")));
        var first = additional.getNegotiationSteps().getFirst();
        assertThrows(IllegalStateException.class, () -> editor.applyPolicies(List.of(request("Nowa"))));
        assertSame(first, additional.getNegotiationSteps().getFirst());
        assertEquals("Pierwsza", first.getMessage());
        assertEquals(2, additional.getNegotiationSteps().size());
    }

    private CreateNegotiationStepRequest request(String message) {
        var request = new CreateNegotiationStepRequest();
        request.setOfferPrice(new BigDecimal("100.00"));
        request.setMaxAcceptedCounterOffer(new BigDecimal("110.00"));
        request.setMessage(message);
        return request;
    }
}
