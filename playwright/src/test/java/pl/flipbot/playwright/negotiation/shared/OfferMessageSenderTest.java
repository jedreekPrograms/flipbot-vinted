package pl.flipbot.playwright.negotiation.shared;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.negotiation.NegotiationSelectors;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OfferMessageSenderTest {
    @Test public void absentMessageDoesNotTouchThePage() {
        Page page = mock(Page.class);
        OfferMessageSender sender = new OfferMessageSender(mock(HumanVerificationHandler.class));
        sender.sendAfterOffer(page, mock(ListingResponseDto.class), " ");
        verifyNoInteractions(page);
    }

    @Test public void confirmedMessageIsClickedExactlyOnce() {
        Fixture f = new Fixture();
        when(f.input.inputValue()).thenReturn("hello", "");
        f.sender.sendAfterOffer(f.page, f.listing, "hello");
        verify(f.button).click(any(Locator.ClickOptions.class));
        verify(f.input).fill("hello");
        verify(f.page, never()).close();
    }

    @Test public void ambiguousSendFailureDoesNotRetryOrFailTheAlreadySentOffer() {
        Fixture f = new Fixture();
        when(f.input.inputValue()).thenReturn("hello");
        doThrow(new IllegalStateException("uncertain acknowledgement"))
                .when(f.button).click(any(Locator.ClickOptions.class));
        f.sender.sendAfterOffer(f.page, f.listing, "hello");
        verify(f.button, times(1)).click(any(Locator.ClickOptions.class));
        verify(f.page, never()).reload();
    }

    @Test public void alteredComposerContentIsNotSent() {
        Fixture f = new Fixture();
        when(f.input.inputValue()).thenReturn("different text");
        f.sender.sendAfterOffer(f.page, f.listing, "hello");
        verify(f.button, never()).click(any(Locator.ClickOptions.class));
    }

    private static final class Fixture {
        final Page page = mock(Page.class);
        final Locator input = mock(Locator.class);
        final Locator icon = mock(Locator.class);
        final Locator button = mock(Locator.class);
        final ListingResponseDto listing = mock(ListingResponseDto.class);
        final OfferMessageSender sender = new OfferMessageSender(mock(HumanVerificationHandler.class));
        Fixture() {
            when(listing.listingId()).thenReturn("123");
            when(page.getByTestId(NegotiationSelectors.MESSAGE_INPUT)).thenReturn(input);
            when(input.first()).thenReturn(input);
            when(page.getByTestId(NegotiationSelectors.MESSAGE_SEND_ICON)).thenReturn(icon);
            when(icon.last()).thenReturn(icon);
            when(icon.locator(anyString())).thenReturn(button);
            when(button.first()).thenReturn(button);
        }
    }
}
