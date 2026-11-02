package pl.flipbot.playwright.negotiation.first;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.negotiation.NegotiationSelectors;
import java.math.BigDecimal;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FirstOfferReadinessTest {
    @Test public void readyFormIsCheckedWithoutSubmittingAnOffer() {
        Fixture f = new Fixture();
        f.check();
        verify(f.submit, never()).click(any(Locator.ClickOptions.class));
    }

    @Test public void changedListingPageIsRejected() {
        Fixture f = new Fixture();
        when(f.itemPage.isCurrentListingPage(f.page, "123")).thenReturn(false);
        assertThrows(IllegalStateException.class, f::check);
        verifyNoInteractions(f.input, f.submit);
    }

    @Test public void changedPriceIsRejectedBeforeQuotaCanBeReserved() {
        Fixture f = new Fixture();
        when(f.input.inputValue()).thenReturn("101.00");
        assertThrows(IllegalStateException.class, f::check);
        verify(f.submit, never()).click(any(Locator.ClickOptions.class));
    }

    @Test public void disabledSubmitIsNotConsideredReady() {
        Fixture f = new Fixture();
        when(f.submit.isEnabled()).thenReturn(false);
        assertThrows(IllegalStateException.class, f::check);
        verify(f.submit, never()).click(any(Locator.ClickOptions.class));
    }

    private static final class Fixture {
        final Page page = mock(Page.class);
        final Locator input = mock(Locator.class);
        final Locator submit = mock(Locator.class);
        final ListingResponseDto listing = mock(ListingResponseDto.class);
        final FirstOfferItemPage itemPage = mock(FirstOfferItemPage.class);
        Fixture() {
            when(listing.listingId()).thenReturn("123");
            when(itemPage.isCurrentListingPage(page, "123")).thenReturn(true);
            when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(input);
            when(input.first()).thenReturn(input);
            when(input.isVisible()).thenReturn(true);
            when(input.inputValue()).thenReturn("100.00");
            when(page.getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON)).thenReturn(submit);
            when(submit.first()).thenReturn(submit);
            when(submit.isEnabled()).thenReturn(true);
        }
        void check() {
            new FirstOfferForm().assertReady(page, listing, new BigDecimal("100.00"), itemPage);
        }
    }
}
