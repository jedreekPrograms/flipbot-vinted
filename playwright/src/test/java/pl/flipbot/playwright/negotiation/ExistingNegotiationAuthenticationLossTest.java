package pl.flipbot.playwright.negotiation;

import pl.flipbot.playwright.negotiation.conversation.ConversationReader;
import org.junit.Test;
import org.mockito.MockedConstruction;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.quota.OfferQuotaClient;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ExistingNegotiationAuthenticationLossTest {
    @Test
    public void authLossStopsRemainingConversationsAndTerminalRecovery() {
        Fixture f = new Fixture();
        when(f.listings.getNegotiatingListings(3L)).thenReturn(List.of(f.first, f.second));
        try (MockedConstruction<ConversationReader> conversations =
                     mockConstruction(ConversationReader.class, (mock, construction) ->
                             when(mock.inspectSnapshot(any())).thenThrow(
                                     new VintedAuthenticationRequiredException("redirected to login")))) {
            assertThrows(VintedAuthenticationRequiredException.class, f::process);
            verify(conversations.constructed().get(0)).inspectSnapshot(f.first);
            verify(conversations.constructed().get(0), never()).inspectSnapshot(f.second);
            verify(f.listings, never()).getNegotiationRecoveryCandidates(anyLong());
            verifyNoInteractions(f.updater);
            assertSame(f.config, f.bot.getConfiguration());
        }
    }

    @Test
    public void interruptedTerminalInspectionDoesNotMarkItChecked() {
        Fixture f = new Fixture();
        when(f.listings.getNegotiatingListings(3L)).thenReturn(List.of());
        when(f.listings.getNegotiationRecoveryCandidates(3L)).thenReturn(List.of(f.first, f.second));
        try (MockedConstruction<ConversationReader> conversations =
                     mockConstruction(ConversationReader.class, (mock, construction) ->
                             when(mock.inspectRecoverySnapshot(any())).thenThrow(
                                     new VintedAuthenticationRequiredException("redirected to login")))) {
            assertThrows(VintedAuthenticationRequiredException.class, f::process);
            verify(conversations.constructed().get(0), never()).inspectRecoverySnapshot(f.second);
            verify(f.listings, never()).markNegotiationRecoveryChecked(anyLong(), anyLong());
            verifyNoInteractions(f.updater);
        }
    }

    private static final class Fixture {
        final BotContext context = mock(BotContext.class);
        final ListingClient listings = mock(ListingClient.class);
        final ListingStatusUpdater updater = mock(ListingStatusUpdater.class);
        final BotDetailsDto bot = new BotDetailsDto();
        final BotConfigurationDto config = new BotConfigurationDto();
        final ListingResponseDto first = mock(ListingResponseDto.class);
        final ListingResponseDto second = mock(ListingResponseDto.class);
        Fixture() {
            bot.setId(3L);
            bot.setConfiguration(config);
            config.setTargetMode("VINTED_MODEL");
            when(context.getBot()).thenReturn(bot);
            when(first.id()).thenReturn(1L);
            when(second.id()).thenReturn(2L);
        }
        boolean process() {
            return new ExistingNegotiationProcessor(context, listings, mock(OfferQuotaClient.class),
                    updater, false, 1).process();
        }
    }
}
