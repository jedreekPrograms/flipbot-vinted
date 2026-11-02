package pl.flipbot.playwright.negotiation;

import org.junit.Test;
import com.microsoft.playwright.Page;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class ConversationContactAvailabilityDetectorTest {

    @Test
    public void accentedPolishContactRestrictionsAreRecognizedBeforeReadingControls() {
        Page page = mock(Page.class, RETURNS_DEEP_STUBS);
        BotContext context = mock(BotContext.class);
        ListingResponseDto listing = mock(ListingResponseDto.class);
        when(context.getPage()).thenReturn(page);
        when(listing.conversationId()).thenReturn("123");
        when(page.url()).thenReturn("https://www.vinted.pl/inbox/123");
        var messages = page.getByTestId("conversation-message--status-message");
        when(messages.count()).thenReturn(1);
        when(messages.nth(0).isVisible()).thenReturn(true);
        for (String phrase : java.util.List.of("Nie możesz wysyłać wiadomości", "Ten użytkownik zablokował Cię")) {
            when(messages.nth(0).innerText()).thenReturn(phrase);
            assertEquals(ConversationContactAssessment.State.CONFIRMED_UNAVAILABLE,
                    new ConversationContactAvailabilityDetector(context).inspect(listing).state());
        }
    }

    @Test
    public void enabledOfferButtonWithoutComposerDoesNotProveContactIsAvailable() {
        assertEquals(
                ConversationContactAssessment.State.SUSPECTED_UNAVAILABLE,
                ConversationContactAvailabilityDetector.classify(
                        true,
                        true,
                        false,
                        false,
                        false
                )
        );
    }

    @Test
    public void usableOfferAndMessageComposerMakeConversationAvailable() {
        assertEquals(ConversationContactAssessment.State.AVAILABLE,
                ConversationContactAvailabilityDetector.classify(true, true, true, true, false));
    }

    @Test
    public void enabledOfferButtonWithDisabledComposerStillNeedsConfirmation() {
        assertEquals(ConversationContactAssessment.State.SUSPECTED_UNAVAILABLE,
                ConversationContactAvailabilityDetector.classify(true, true, true, false, false));
    }

    @Test
    public void disabledOfferButtonWithoutComposerIsSuspectedUnavailable() {
        assertEquals(
                ConversationContactAssessment.State.SUSPECTED_UNAVAILABLE,
                ConversationContactAvailabilityDetector.classify(
                        true,
                        false,
                        false,
                        false,
                        false
                )
        );
    }

    @Test
    public void enabledMessageComposerWithoutOfferActionSignalsNegotiationUnavailable() {
        assertEquals(
                ConversationContactAssessment.State.OFFER_ACTION_UNAVAILABLE,
                ConversationContactAvailabilityDetector.classify(
                        false,
                        false,
                        true,
                        true,
                        false
                )
        );
    }

    @Test
    public void missingOfferAndComposerIsOnlySuspectedAtFirst() {
        assertEquals(
                ConversationContactAssessment.State.SUSPECTED_UNAVAILABLE,
                ConversationContactAvailabilityDetector.classify(
                        false,
                        false,
                        false,
                        false,
                        false
                )
        );
    }

    @Test
    public void disabledComposerWithoutOfferIsSuspectedUnavailable() {
        assertEquals(
                ConversationContactAssessment.State.SUSPECTED_UNAVAILABLE,
                ConversationContactAvailabilityDetector.classify(
                        false,
                        false,
                        true,
                        false,
                        false
                )
        );
    }

    @Test
    public void explicitUnavailableSignalWinsEvenIfControlsLookPresent() {
        assertEquals(
                ConversationContactAssessment.State.CONFIRMED_UNAVAILABLE,
                ConversationContactAvailabilityDetector.classify(
                        true,
                        true,
                        true,
                        true,
                        true
                )
        );
    }
}
