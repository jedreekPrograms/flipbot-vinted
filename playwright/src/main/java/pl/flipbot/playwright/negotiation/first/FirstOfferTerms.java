package pl.flipbot.playwright.negotiation.first;

import lombok.extern.slf4j.Slf4j;

import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.NegotiationStepDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Read-only first-step terms and eligibility checks; no browser actions. */
@Slf4j
public final class FirstOfferTerms {
    private static final BigDecimal VINTED_MIN_OFFER_RATIO =
            new BigDecimal("0.60");

    private final BotContext context;
    public FirstOfferTerms(BotContext context) { this.context = context; }

    public NegotiationStepDto getFirstNegotiationStep() {
        if (context.getBot().getConfiguration() == null) {
            throw new IllegalStateException("Bot configuration is missing");
        }

        List<NegotiationStepDto> negotiationSteps =
                context.getBot()
                        .getConfiguration()
                        .getNegotiationSteps();

        if (negotiationSteps == null || negotiationSteps.isEmpty()) {
            throw new IllegalStateException("Bot has no negotiation steps");
        }

        NegotiationStepDto firstStep =
                negotiationSteps.stream()
                        .filter(Objects::nonNull)
                        .min(
                                Comparator.comparing(
                                        NegotiationStepDto::getStepNumber,
                                        Comparator.nullsLast(Comparator.naturalOrder())
                                )
                        )
                        .orElseThrow(
                                () -> new IllegalStateException("Bot has no valid negotiation steps")
                        );

        if (firstStep.getOfferPrice() == null) {
            throw new IllegalStateException("First negotiation step has no offer price");
        }

        return firstStep;
    }

    public void validateOfferPrice(BigDecimal offerPrice) {
        if (offerPrice.signum() <= 0) {
            throw new IllegalArgumentException("Offer price must be greater than zero");
        }
    }

    public void validateListing(ListingResponseDto listing) {
        if (listing.id() == null) {
            throw new IllegalArgumentException("Backend listing id cannot be null");
        }

        if (listing.listingId() == null || listing.listingId().isBlank()) {
            throw new IllegalArgumentException("Marketplace listing id cannot be blank");
        }

        if (listing.url() == null || listing.url().isBlank()) {
            throw new IllegalArgumentException("Listing URL cannot be blank");
        }

        if (!"DISCOVERED".equals(listing.status())) {
            throw new IllegalArgumentException(
                    "Cannot start negotiation for listing "
                            + listing.id()
                            + " because its status is "
                            + listing.status()
            );
        }
    }

    public boolean isBelowEstimatedVintedMinimum(ListingResponseDto listing, BigDecimal offerPrice) {
        BigDecimal listingPrice =
                listing.originalPrice();

        if (listingPrice == null || listingPrice.signum() <= 0) {
            return false;
        }

        BigDecimal estimatedMinimum =
                listingPrice
                        .multiply(VINTED_MIN_OFFER_RATIO)
                        .setScale(2, RoundingMode.HALF_UP);

        if (offerPrice.compareTo(estimatedMinimum) >= 0) {
            return false;
        }

        log.info(
                "[REAL OFFER PREPARE] Skipping marketplace listing {} "
                        + "before opening it. Listing price={}, "
                        + "configured offer={}, estimated Vinted minimum={}.",
                listing.listingId(),
                listingPrice,
                offerPrice,
                estimatedMinimum
        );

        return true;
    }
}
