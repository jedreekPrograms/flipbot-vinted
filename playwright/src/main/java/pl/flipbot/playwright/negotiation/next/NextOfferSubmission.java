package pl.flipbot.playwright.negotiation.next;

import pl.flipbot.playwright.negotiation.NegotiationSelectors;
import pl.flipbot.playwright.negotiation.NextStepExecutionResult;
import pl.flipbot.playwright.negotiation.shared.VintedPriceParser;

import pl.flipbot.playwright.negotiation.shared.OfferMessageSender;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.listing.dto.UpdateListingRequestDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.NegotiationStepDto;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import java.math.BigDecimal;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class NextOfferSubmission {
    private static final double ELEMENT_TIMEOUT_MS = 15_000;
    private static final double OFFER_CONFIRMATION_TIMEOUT_MS = 30_000;
    private static final double OFFER_CONFIRMATION_POLL_INTERVAL_MS = 500;

    private final BotContext context;
    private final ListingClient listingClient = new ListingClient();
    private final HumanVerificationHandler humanVerificationHandler =
            new HumanVerificationHandler();

    public NextStepExecutionResult submitPrepared(
            ListingResponseDto listing,
            NegotiationStepDto nextStep
    ) {
        Objects.requireNonNull(listing, "Listing cannot be null");
        Objects.requireNonNull(nextStep, "Next negotiation step cannot be null");

        Page page = context.getPage();
        int ownOfferCountBefore =
                page.getByTestId(NegotiationSelectors.OWN_OFFER_PRICE).count();

        Locator submitButton =
                page.getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON).first();

        log.warn(
                "[NEXT STEP REAL] Clicking already-prepared submit. This sends a REAL offer. Listing: {}, step: {}, price: {}",
                listing.listingId(),
                nextStep.getStepNumber(),
                nextStep.getOfferPrice()
        );

        submitButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

        SubmittedOffer submittedOffer =
                waitForNewOwnOffer(page, listing, nextStep, ownOfferCountBefore);

        ListingResponseDto updatedListing =
                markNextStepStarted(listing, nextStep, submittedOffer.displayedPrice());

        log.info(
                "[NEXT STEP REAL] Backend listing {} updated. Status={}, step={}, currentPrice={}, awaitingSellerResponse={}",
                updatedListing.id(),
                updatedListing.status(),
                updatedListing.currentStep(),
                updatedListing.currentPrice(),
                updatedListing.awaitingSellerResponse()
        );

        new OfferMessageSender().sendAfterOffer(page, listing, nextStep.getMessage());

        log.warn(
                "[NEXT STEP REAL] Prepared step {} sent for listing {}. Displayed price={}, Vinted status={}",
                nextStep.getStepNumber(),
                listing.listingId(),
                submittedOffer.displayedPrice(),
                submittedOffer.rawStatus()
        );

        return NextStepExecutionResult.SENT;
    }

    private SubmittedOffer waitForNewOwnOffer(
            Page page,
            ListingResponseDto listing,
            NegotiationStepDto nextStep,
            int ownOfferCountBefore
    ) {
        Locator ownOfferPrices =
                page.getByTestId(NegotiationSelectors.OWN_OFFER_PRICE);

        long deadline =
                System.currentTimeMillis()
                        + (long) OFFER_CONFIRMATION_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            humanVerificationHandler.waitUntilVerified(page);

            int currentOfferCount = ownOfferPrices.count();
            if (currentOfferCount > ownOfferCountBefore) {
                Locator latestOwnOffer = ownOfferPrices.nth(currentOfferCount - 1);

                if (!latestOwnOffer.isVisible()) {
                    page.waitForTimeout(OFFER_CONFIRMATION_POLL_INTERVAL_MS);
                    continue;
                }

                BigDecimal displayedPrice = VintedPriceParser.parse(latestOwnOffer.innerText());
                String rawStatus = readLatestOwnOfferStatus(page);

                if (displayedPrice.compareTo(nextStep.getOfferPrice()) == 0) {
                    log.info("[NEXT STEP REAL] Submitted offer price confirmed: {}", displayedPrice);
                } else {
                    log.warn(
                            "[NEXT STEP REAL] Configured price {} differs from Vinted displayed price {}",
                            nextStep.getOfferPrice(),
                            displayedPrice
                    );
                }

                return new SubmittedOffer(displayedPrice, rawStatus);
            }

            page.waitForTimeout(OFFER_CONFIRMATION_POLL_INTERVAL_MS);
        }

        throw new IllegalStateException(
                "Prepared next-step submit was clicked, but a new own offer was not confirmed within 30 seconds. The offer may already have been sent. Do not retry automatically. Listing: "
                        + listing.listingId()
        );
    }

    private String readLatestOwnOfferStatus(Page page) {
        try {
            Locator statuses =
                    page.getByTestId(NegotiationSelectors.OWN_OFFER_STATUS);
            int count = statuses.count();

            if (count == 0) {
                return null;
            }

            Locator latest = statuses.nth(count - 1);
            return latest.isVisible() ? latest.innerText() : null;
        } catch (PlaywrightException exception) {
            log.debug("Could not read latest own-offer status", exception);
            return null;
        }
    }

    private ListingResponseDto markNextStepStarted(
            ListingResponseDto listing,
            NegotiationStepDto nextStep,
            BigDecimal displayedPrice
    ) {
        UpdateListingRequestDto request =
                new UpdateListingRequestDto(
                        "NEGOTIATING",
                        displayedPrice,
                        nextStep.getStepNumber(),
                        true,
                        listing.conversationId(),
                        listing.conversationUrl()
                );

        ListingResponseDto updated =
                listingClient.updateListing(context.getBot().getId(), listing.id(), request);

        if (!"NEGOTIATING".equals(updated.status())
                || !Objects.equals(nextStep.getStepNumber(), updated.currentStep())
                || !Objects.equals(listing.conversationId(), updated.conversationId())) {
            throw new IllegalStateException(
                    "Backend returned unexpected state after prepared next-step submit"
            );
        }

        return updated;
    }

    private record SubmittedOffer(BigDecimal displayedPrice, String rawStatus) {
    }
}
