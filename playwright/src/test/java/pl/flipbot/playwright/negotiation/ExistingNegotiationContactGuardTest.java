package pl.flipbot.playwright.negotiation;

import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.quota.OfferQuotaClient;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.model.NegotiationStepDto;
import pl.flipbot.playwright.negotiation.conversation.ConversationReader;
import pl.flipbot.playwright.negotiation.next.NextOfferPreparation;
import pl.flipbot.playwright.negotiation.next.NextOfferWorkflow;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ExistingNegotiationContactGuardTest {
    @After public void clearTracker() { ConsecutiveContactUnavailableTracker.clearProcessState(39L); }

    @Test public void anAmbiguousConversationDoesNotPrepareAnOfferOrDelayTheNextConversation() {
        try (Fixture f = new Fixture()) {
            when(f.listings.getNegotiatingListings(39L)).thenReturn(List.of(f.first, f.second));
            when(f.contacts().inspect(f.second)).thenReturn(ConversationContactAssessment.available("ready"));
            assertFalse(f.processor.process());
            verify(f.conversations()).inspectSnapshot(f.first);
            verify(f.conversations()).inspectSnapshot(f.second);
            verify(f.workflow(), never()).execute(eq(f.first), any());
            verify(f.workflow()).execute(eq(f.second), any());
            verifyNoInteractions(f.preparations.constructed().get(0), f.quota, f.updater);
            verify(f.contacts(), times(1)).inspect(f.first);
        }
    }

    @Test public void theExistingThreePassThresholdStillClosesAnUnavailableConversationOnce() {
        try (Fixture f = new Fixture()) {
            assertFalse(f.processor.process());
            assertFalse(f.processor.process());
            verifyNoInteractions(f.updater);
            assertFalse(f.processor.process());
            verify(f.updater, times(1)).markNegotiationUnavailable(f.first);
            verify(f.contacts(), times(3)).inspect(f.first);
            verifyNoInteractions(f.workflow(), f.preparations.constructed().get(0), f.quota);
        }
    }

    @Test public void anAvailableConversationResumesNormalDecisionsAndResetsSuspicion() {
        try (Fixture f = new Fixture()) {
            assertFalse(f.processor.process());
            when(f.contacts().inspect(f.first)).thenReturn(ConversationContactAssessment.available("ready"));
            assertFalse(f.processor.process());
            verify(f.workflow()).execute(eq(f.first), any());
            when(f.contacts().inspect(f.first)).thenReturn(ConversationContactAssessment.suspected("loading"));
            assertFalse(f.processor.process());
            assertFalse(f.processor.process());
            verifyNoInteractions(f.updater, f.quota);
        }
    }

    @Test public void theImmediatePreOfferCheckAlsoDefersAnAmbiguousSellerCounteroffer() {
        try (Fixture f = new Fixture()) {
            when(f.conversations().inspectSnapshot(f.first)).thenReturn(
                    NegotiationConversationSnapshot.sellerCounterOffer(new java.math.BigDecimal("99")));
            assertFalse(f.processor.process());
            verify(f.contacts(), times(1)).inspect(f.first);
            verifyNoInteractions(f.workflow(), f.preparations.constructed().get(0), f.quota, f.updater);
        }
    }

    @Test public void missingOfferWithWorkingChatAlsoRequiresThreeSeparateObservations() {
        try (Fixture f = new Fixture()) {
            when(f.contacts().inspect(f.first)).thenReturn(ConversationContactAssessment.offerActionUnavailable("chat only"));
            assertFalse(f.processor.process());
            assertFalse(f.processor.process());
            verifyNoInteractions(f.updater);
            assertFalse(f.processor.process());
            verify(f.updater).markNegotiationUnavailable(f.first);
            verifyNoInteractions(f.workflow(), f.quota);
        }
    }

    @Test public void suspicionSurvivesTheProcessorsCreatedForSeparateJobs() {
        try (Fixture f = new Fixture()) {
            assertFalse(f.processor.process());
            assertFalse(new ExistingNegotiationProcessor(f.context, f.listings, f.quota, f.updater, true, 1).process());
            verifyNoInteractions(f.updater);
            assertFalse(new ExistingNegotiationProcessor(f.context, f.listings, f.quota, f.updater, true, 1).process());
            verify(f.updater).markNegotiationUnavailable(f.first);
            verifyNoInteractions(f.quota);
        }
    }

    @Test public void incompletePageDoesNotCountTowardsAContactClosure() {
        try (Fixture f = new Fixture()) {
            when(f.contacts().inspect(f.first)).thenReturn(ConversationContactAssessment.inspectionUnavailable("not loaded"));
            for (int i = 0; i < 4; i++) assertFalse(f.processor.process());
            when(f.contacts().inspect(f.first)).thenReturn(ConversationContactAssessment.suspected("composer missing"));
            assertFalse(f.processor.process());
            assertFalse(f.processor.process());
            verifyNoInteractions(f.updater, f.workflow(), f.quota);
        }
    }

    @Test public void explicitContactRestrictionClosesImmediatelyWithoutSending() {
        try (Fixture f = new Fixture()) {
            when(f.contacts().inspect(f.first)).thenReturn(ConversationContactAssessment.confirmed("cannot send messages"));
            assertFalse(f.processor.process());
            verify(f.updater).markContactUnavailable(f.first);
            verify(f.updater, never()).markNegotiationUnavailable(any());
            verifyNoInteractions(f.workflow(), f.quota);
        }
    }

    @Test public void waitingAfterASellerCounterofferStillChecksMissingContactControls() {
        try (Fixture f = new Fixture()) {
            when(f.conversations().inspectSnapshot(f.first)).thenReturn(
                    NegotiationConversationSnapshot.sellerCounterOffer(new java.math.BigDecimal("1900")));
            when(f.decisions.constructed().get(0).decide(any(), any(), any())).thenReturn(
                    NegotiationDecision.wait(new java.math.BigDecimal("1900"), "normal step timer"));
            for (int i = 0; i < 3; i++) assertFalse(f.processor.process());
            verify(f.updater).markNegotiationUnavailable(f.first);
            verify(f.contacts(), times(3)).inspect(f.first);
            verifyNoInteractions(f.workflow(), f.quota);
        }
    }

    @Test public void anAffordableSellerCounterofferStillReachesManualActionWithoutComposer() {
        try (Fixture f = new Fixture()) {
            when(f.conversations().inspectSnapshot(f.first)).thenReturn(
                    NegotiationConversationSnapshot.sellerCounterOffer(new java.math.BigDecimal("1000")));
            NegotiationDecision decision = NegotiationDecision.actionRequiredForCounterOffer(new java.math.BigDecimal("1000"));
            when(f.decisions.constructed().get(0).decide(any(), any(), any())).thenReturn(decision);
            when(f.updater.markActionRequired(f.first, decision)).thenReturn(f.first);
            assertFalse(f.processor.process());
            verify(f.updater).markActionRequired(f.first, decision);
            verifyNoInteractions(f.contacts(), f.workflow(), f.quota);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final BotContext context = mock(BotContext.class);
        final ListingClient listings = mock(ListingClient.class);
        final ListingStatusUpdater updater = mock(ListingStatusUpdater.class);
        final OfferQuotaClient quota = mock(OfferQuotaClient.class);
        final ListingResponseDto first = listing("10332564817");
        final ListingResponseDto second = listing("10332622810");
        final MockedConstruction<ConversationReader> readers;
        final MockedConstruction<ConversationAvailabilityDetector> availability;
        final MockedConstruction<ConversationContactAvailabilityDetector> contacts;
        final MockedConstruction<ConversationActivityDetector> activity;
        final MockedConstruction<ExistingNegotiationSupport> support;
        final MockedConstruction<NegotiationDecisionService> decisions;
        final MockedConstruction<NextOfferPreparation> preparations;
        final MockedConstruction<NextOfferWorkflow> workflows;
        final ExistingNegotiationProcessor processor;

        Fixture() {
            BotDetailsDto bot = new BotDetailsDto();
            bot.setId(39L);
            bot.setConfiguration(new BotConfigurationDto());
            when(context.getBot()).thenReturn(bot);
            when(listings.getNegotiatingListings(39L)).thenReturn(List.of(first));
            when(listings.getNegotiationRecoveryCandidates(39L)).thenReturn(List.of());
            when(updater.markNegotiationUnavailable(any())).thenAnswer(call -> call.getArgument(0));
            readers = mockConstruction(ConversationReader.class, (mock, construction) ->
                    when(mock.inspectSnapshot(any())).thenReturn(NegotiationConversationSnapshot.rejected("rejected")));
            availability = mockConstruction(ConversationAvailabilityDetector.class);
            contacts = mockConstruction(ConversationContactAvailabilityDetector.class, (mock, construction) ->
                    when(mock.inspect(any())).thenReturn(ConversationContactAssessment.suspected("loading")));
            activity = mockConstruction(ConversationActivityDetector.class, (mock, construction) ->
                    when(mock.inspect()).thenReturn(mock(ConversationActivitySnapshot.class)));
            support = mockConstruction(ExistingNegotiationSupport.class, (mock, construction) ->
                    when(mock.matchesConfiguredTarget(any(), any())).thenReturn(true));
            decisions = mockConstruction(NegotiationDecisionService.class, (mock, construction) ->
                    when(mock.decide(any(), any(), any())).thenReturn(NegotiationDecision.sendNextStep(
                            mock(NegotiationStepDto.class), null, "Configured wait elapsed")));
            preparations = mockConstruction(NextOfferPreparation.class);
            workflows = mockConstruction(NextOfferWorkflow.class);
            processor = new ExistingNegotiationProcessor(context, listings, quota, updater, true, 1);
        }

        ConversationReader conversations() { return readers.constructed().get(0); }
        ConversationContactAvailabilityDetector contacts() { return contacts.constructed().get(0); }
        NextOfferWorkflow workflow() { return workflows.constructed().get(0); }
        public void close() {
            workflows.close(); preparations.close(); decisions.close(); support.close();
            activity.close(); contacts.close(); availability.close(); readers.close();
        }
        static ListingResponseDto listing(String id) {
            ListingResponseDto listing = mock(ListingResponseDto.class);
            when(listing.listingId()).thenReturn(id);
            return listing;
        }
    }
}
