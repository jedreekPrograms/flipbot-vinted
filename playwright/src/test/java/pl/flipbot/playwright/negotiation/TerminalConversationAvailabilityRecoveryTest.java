package pl.flipbot.playwright.negotiation;

import java.util.List;
import org.junit.Test;
import org.junit.After;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.negotiation.conversation.ConversationReader;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TerminalConversationAvailabilityRecoveryTest {
    @After public void clearTracker() { ConsecutiveContactUnavailableTracker.clearProcessState(39L); }

    @Test public void aSoldBannerClosesRecoveryPermanentlyInsteadOfSchedulingAnotherCheck() {
        try (Fixture f = new Fixture(true, TerminalNegotiationRecoveryDecision.reopen(true, "old pending offer"))) {
            when(f.updater.markNegotiationUnavailable(f.listing)).thenAnswer(call -> {
                when(f.listing.status()).thenReturn("UNAVAILABLE");
                return f.listing;
            });
            f.recovery.inspect(List.of(f.listing));
            f.recovery.inspect(List.of(f.listing));
            verify(f.api, never()).reopenNegotiationForRecovery(anyLong(), anyLong(), anyBoolean(), anyString());
            verify(f.api, never()).markNegotiationRecoveryChecked(anyLong(), anyLong());
            verify(f.updater).markNegotiationUnavailable(f.listing);
            verify(f.reader).inspectRecoverySnapshot(f.listing);
        }
    }

    @Test public void staleUnavailableCandidatesAreSkippedEvenIfTheyContainHistoricalAcceptance() {
        try (Fixture f = new Fixture(false, TerminalNegotiationRecoveryDecision.actionRequired(
                NegotiationDecision.actionRequiredAfterAcceptance()))) {
            for (String status : List.of("UNAVAILABLE", "CONTACT_UNAVAILABLE")) {
                when(f.listing.status()).thenReturn(status);
                f.recovery.inspect(List.of(f.listing));
            }
            verifyNoInteractions(f.reader, f.api, f.updater);
        }
    }

    @Test public void anAvailableItemCanStillReopenForTheNormalNegotiationWorkflow() {
        try (Fixture f = new Fixture(false, TerminalNegotiationRecoveryDecision.reopen(true, "old pending offer"))) {
            f.recovery.inspect(List.of(f.listing));
            verify(f.api).reopenNegotiationForRecovery(39L, 1L, true, "old pending offer");
            verify(f.api, never()).markNegotiationRecoveryChecked(anyLong(), anyLong());
            verifyNoInteractions(f.updater);
        }
    }

    @Test public void messagingWithoutAnOfferActionDoesNotReopenTheUnavailableRow() {
        try (Fixture f = new Fixture(false, TerminalNegotiationRecoveryDecision.reopen(true, "old pending offer"),
                ConversationContactAssessment.offerActionUnavailable("chat works but no offer action"))) {
            f.recovery.inspect(List.of(f.listing));
            verify(f.api, never()).reopenNegotiationForRecovery(anyLong(), anyLong(), anyBoolean(), anyString());
            verify(f.api).markNegotiationRecoveryChecked(39L, 1L);
            verifyNoInteractions(f.updater);
        }
    }

    @Test public void ambiguousControlsDeferRecoveryUntilAUsableOfferActionExists() {
        try (Fixture f = new Fixture(false, TerminalNegotiationRecoveryDecision.reopen(true, "old pending offer"),
                ConversationContactAssessment.suspected("controls still loading"))) {
            f.recovery.inspect(List.of(f.listing));
            verify(f.api, never()).reopenNegotiationForRecovery(anyLong(), anyLong(), anyBoolean(), anyString());
            verify(f.api).markNegotiationRecoveryChecked(39L, 1L);
            verifyNoInteractions(f.updater);
        }
    }

    @Test public void anAcceptedSellerDecisionStillReachesManualActionEvenWhenTheItemIsUnavailable() {
        NegotiationDecision decision = NegotiationDecision.actionRequiredAfterAcceptance();
        try (Fixture f = new Fixture(true, TerminalNegotiationRecoveryDecision.actionRequired(decision))) {
            f.recovery.inspect(List.of(f.listing));
            verify(f.updater).markActionRequired(f.listing, decision);
            verify(f.api, never()).reopenNegotiationForRecovery(anyLong(), anyLong(), anyBoolean(), anyString());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ListingClient api = mock(ListingClient.class);
        final ListingStatusUpdater updater = mock(ListingStatusUpdater.class);
        final ConversationReader reader = mock(ConversationReader.class);
        final ListingResponseDto listing = mock(ListingResponseDto.class);
        final MockedConstruction<TerminalNegotiationRecoveryPolicy> policies;
        final MockedConstruction<ConversationAvailabilityDetector> availability;
        final MockedConstruction<ConversationContactAvailabilityDetector> contacts;
        final TerminalConversationRecovery recovery;

        Fixture(boolean unavailable, TerminalNegotiationRecoveryDecision decision) {
            this(unavailable, decision, ConversationContactAssessment.available("offer action ready"));
        }

        Fixture(boolean unavailable, TerminalNegotiationRecoveryDecision decision, ConversationContactAssessment contact) {
            BotContext context = mock(BotContext.class);
            BotDetailsDto bot = new BotDetailsDto();
            bot.setId(39L);
            bot.setConfiguration(new BotConfigurationDto());
            when(context.getBot()).thenReturn(bot);
            when(listing.id()).thenReturn(1L);
            when(listing.listingId()).thenReturn("10317853695");
            when(listing.status()).thenReturn("REJECTED");
            when(api.reopenNegotiationForRecovery(anyLong(), anyLong(), anyBoolean(), anyString())).thenReturn(listing);
            when(updater.markActionRequired(any(), any())).thenReturn(listing);
            when(reader.inspectRecoverySnapshot(listing)).thenReturn(NegotiationConversationSnapshot.pending("pending"));
            policies = mockConstruction(TerminalNegotiationRecoveryPolicy.class, (mock, construction) ->
                    when(mock.decide(any(), any(), any())).thenReturn(decision));
            availability = mockConstruction(ConversationAvailabilityDetector.class, (mock, construction) ->
                    when(mock.isUnavailable(listing)).thenReturn(unavailable));
            contacts = mockConstruction(ConversationContactAvailabilityDetector.class, (mock, construction) ->
                    when(mock.inspect(listing)).thenReturn(contact));
            recovery = new TerminalConversationRecovery(context, api, updater, reader,
                    mock(ExistingNegotiationSupport.class), new NegotiationContactGuard(context, updater,
                    new ConversationContactAvailabilityDetector(context), new ConsecutiveContactUnavailableTracker()));
        }

        public void close() { contacts.close(); availability.close(); policies.close(); }
    }
}
