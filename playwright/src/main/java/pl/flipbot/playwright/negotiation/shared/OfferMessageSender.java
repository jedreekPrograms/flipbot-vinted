package pl.flipbot.playwright.negotiation.shared;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.negotiation.NegotiationSelectors;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

/** Sends the optional chat message after the offer and backend transition succeed. */
@Slf4j
public final class OfferMessageSender {
    private static final double ELEMENT_TIMEOUT_MS = 20_000;
    private static final long CONFIRMATION_TIMEOUT_MS = 5_000;
    private static final double POLL_INTERVAL_MS = 250;
    private final HumanVerificationHandler verification;

    public OfferMessageSender() { this(new HumanVerificationHandler()); }

    OfferMessageSender(HumanVerificationHandler verification) { this.verification = verification; }

    public void sendAfterOffer(Page page, ListingResponseDto listing, String message) {
        if (message == null || message.isBlank()) return;
        try {
            verification.waitUntilVerified(page);
            Locator input = page.getByTestId(NegotiationSelectors.MESSAGE_INPUT).first();
            input.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(ELEMENT_TIMEOUT_MS));
            input.fill(message);
            if (!message.equals(input.inputValue())) {
                throw new IllegalStateException("Chat input contains unexpected message");
            }
            Locator button = page.getByTestId(NegotiationSelectors.MESSAGE_SEND_ICON).last()
                    .locator("xpath=ancestor::button[1]").first();
            button.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(ELEMENT_TIMEOUT_MS));
            button.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            if (waitForComposerToClear(page, input)) {
                log.info("[OFFER MESSAGE] Message sent for listing {}", listing.listingId());
            } else {
                log.warn("[OFFER MESSAGE] Send clicked but composer did not clear for listing {}. Manual verification may be required.",
                        listing.listingId());
            }
        } catch (Exception exception) {
            // A chat failure must never replay an already submitted price offer.
            log.error("[OFFER MESSAGE] Price offer was sent; optional message failed for listing {}. Backend remains NEGOTIATING.",
                    listing.listingId());
            log.trace("[OFFER MESSAGE] Message failure", exception);
        }
    }

    private boolean waitForComposerToClear(Page page, Locator input) {
        long deadline = System.currentTimeMillis() + CONFIRMATION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (input.inputValue().isBlank()) return true;
            } catch (PlaywrightException departingComposer) {
                return true;
            }
            page.waitForTimeout(POLL_INTERVAL_MS);
        }
        return false;
    }
}
