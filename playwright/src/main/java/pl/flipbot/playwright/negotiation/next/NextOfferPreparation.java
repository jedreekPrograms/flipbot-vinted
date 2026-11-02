package pl.flipbot.playwright.negotiation.next;

import pl.flipbot.playwright.negotiation.ConversationIdentityCoordinator;
import pl.flipbot.playwright.negotiation.ConversationIdentityResolver;
import pl.flipbot.playwright.negotiation.NextStepPreparationResult;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.NegotiationStepDto;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import java.math.BigDecimal;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class NextOfferPreparation {
    private static final double CONVERSATION_TIMEOUT_MS =
            20_000;

    private final BotContext context;

    private final HumanVerificationHandler humanVerificationHandler =
            new HumanVerificationHandler();

    public NextStepPreparationResult prepare(ListingResponseDto listing, NegotiationStepDto nextStep) {
        Objects.requireNonNull(listing, "Listing cannot be null");

        Objects.requireNonNull(nextStep, "Next negotiation step cannot be null");

        validateListing(listing);

        validateNextStep(listing, nextStep);

        Page page =
                context.getPage();

        log.info(
                "[NEXT OFFER PREPARE] Preparing step {} "
                        + "for backend listing {}, marketplace listing {}, "
                        + "offer price {}",
                nextStep.getStepNumber(),
                listing.id(),
                listing.listingId(),
                nextStep.getOfferPrice()
        );

        listing = openConversation(page, listing, "[NEXT OFFER PREPARE]");

        NextOfferForm form = new NextOfferForm(context);
        form.openOfferModal(page, listing, "[NEXT OFFER PREPARE]");

        boolean offerPrepared =
                form.fillOfferPrice(page, listing, nextStep, "[NEXT OFFER PREPARE]");

        if (!offerPrepared) {
            log.warn(
                    "[NEXT OFFER PREPARE] Step {} for marketplace listing {} "
                            + "cannot be sent because price {} is below "
                            + "the minimum allowed by Vinted. "
                            + "No offer was sent.",
                    nextStep.getStepNumber(),
                    listing.listingId(),
                    nextStep.getOfferPrice()
            );

            return NextStepPreparationResult.OFFER_TOO_LOW;
        }

        log.warn(
                "[NEXT OFFER PREPARE] Step {} for marketplace listing {} "
                        + "was prepared successfully. Offer price: {}. "
                        + "The submit button was NOT clicked.",
                nextStep.getStepNumber(),
                listing.listingId(),
                nextStep.getOfferPrice()
        );

        return NextStepPreparationResult.PREPARED;
    }

    private ListingResponseDto openConversation(Page page, ListingResponseDto listing, String logPrefix) {
        boolean reuseCurrentConversation =
                isExpectedConversationAlreadyOpen(page, listing);

        if (reuseCurrentConversation) {
            log.info(
                    "{} Reusing already-open expected conversation {} instead of navigating to the same inbox URL again.",
                    logPrefix,
                    listing.conversationId()
            );
        } else {
            log.info(
                    "{} Opening conversation {}: {}",
                    logPrefix,
                    listing.conversationId(),
                    listing.conversationUrl()
            );

            new MarketplaceNavigator(context).goToTrustedVintedUrl(listing.conversationUrl());
        }

        humanVerificationHandler.waitUntilVerified(page);

        ListingResponseDto canonicalListing =
                validateOpenedConversation(page, listing);

        Locator conversationContent =
                page.getByTestId("conversation-content")
                        .first();

        conversationContent.waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(CONVERSATION_TIMEOUT_MS)
        );

        log.info("{} Conversation {} is ready.", logPrefix, canonicalListing.conversationId());

        return canonicalListing;
    }

    private boolean isExpectedConversationAlreadyOpen(Page page, ListingResponseDto listing) {
        if (page == null
                || page.isClosed()
                || listing == null
                || listing.conversationId() == null
                || listing.conversationId().isBlank()) {
            return false;
        }

        try {
            ConversationIdentityResolver.ConversationIdentityAssessment assessment =
                    new ConversationIdentityResolver().assess(listing.conversationId(), page.url());

            if (!assessment.matchesExpectedConversation()) {
                return false;
            }

            Locator conversationContent =
                    page.getByTestId("conversation-content").first();

            return conversationContent.isVisible();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ListingResponseDto validateOpenedConversation(Page page, ListingResponseDto listing) {
        ListingResponseDto canonicalListing =
                new ConversationIdentityCoordinator(context)
                        .verifyAndCanonicalize(listing, "Opened conversation");

        log.info("Opened expected conversation {}.", canonicalListing.conversationId());

        return canonicalListing;
    }

    private void validateListing(ListingResponseDto listing) {
        if (listing.id() == null) {
            throw new IllegalArgumentException("Backend listing ID cannot be null");
        }

        if (!"NEGOTIATING".equals(listing.status())) {
            throw new IllegalArgumentException(
                    "Next negotiation step can only be processed "
                            + "for a NEGOTIATING listing. Backend listing: "
                            + listing.id()
                            + ", status: "
                            + listing.status()
            );
        }

        if (listing.currentStep() == null || listing.currentStep() <= 0) {
            throw new IllegalArgumentException(
                    "Negotiating listing "
                            + listing.id()
                            + " has an invalid current step: "
                            + listing.currentStep()
            );
        }

        if (listing.conversationId() == null || listing.conversationId().isBlank()) {
            throw new IllegalArgumentException(
                    "Negotiating listing "
                            + listing.id()
                            + " has no conversation ID"
            );
        }

        if (listing.conversationUrl() == null || listing.conversationUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "Negotiating listing "
                            + listing.id()
                            + " has no conversation URL"
            );
        }
    }

    private void validateNextStep(ListingResponseDto listing, NegotiationStepDto nextStep) {
        if (nextStep.getStepNumber() == null) {
            throw new IllegalArgumentException("Next negotiation step has no step number");
        }

        if (nextStep.getStepNumber() <= listing.currentStep()) {
            throw new IllegalArgumentException(
                    "Next negotiation step must be greater than "
                            + "the current step. Current: "
                            + listing.currentStep()
                            + ", next: "
                            + nextStep.getStepNumber()
            );
        }

        if (nextStep.getOfferPrice() == null) {
            throw new IllegalArgumentException(
                    "Negotiation step "
                            + nextStep.getStepNumber()
                            + " has no offer price"
            );
        }

        if (nextStep.getOfferPrice().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Negotiation step "
                            + nextStep.getStepNumber()
                            + " has an invalid offer price: "
                            + nextStep.getOfferPrice()
            );
        }

        if (listing.currentPrice() != null) {
            int priceComparison =
                    nextStep.getOfferPrice().compareTo(listing.currentPrice());

            if (priceComparison < 0) {
                throw new IllegalArgumentException(
                        "Next negotiation offer cannot be lower than the current offer. Current price: "
                                + listing.currentPrice()
                                + ", next price: "
                                + nextStep.getOfferPrice()
                );
            }

            if (priceComparison == 0 && !isAllowedAdaptiveCapPlateau(listing, nextStep)) {
                throw new IllegalArgumentException(
                        "Next negotiation offer may equal the current offer only after the adaptive global cap has been reached. Current price: "
                                + listing.currentPrice()
                                + ", next price: "
                                + nextStep.getOfferPrice()
                );
            }
        }
    }

    boolean isAllowedAdaptiveCapPlateau(ListingResponseDto listing, NegotiationStepDto nextStep) {
        if (listing == null
                || nextStep == null
                || listing.currentPrice() == null
                || nextStep.getOfferPrice() == null
                || context.getBot() == null) {
            return false;
        }

        BotConfigurationDto configuration =
                context.getBot().getConfiguration();

        if (configuration == null
                || !Boolean.TRUE.equals(configuration.getAutoRaiseOfferToVintedMinimum())
                || configuration.getMaxAutomaticOffer() == null
                || configuration.getMaxAutomaticOffer().signum() <= 0) {
            return false;
        }

        BigDecimal cap = configuration.getMaxAutomaticOffer();

        return listing.currentPrice().compareTo(cap) == 0
                && nextStep.getOfferPrice().compareTo(cap) == 0
                && listing.currentStep() != null
                && nextStep.getStepNumber() != null
                && nextStep.getStepNumber() > listing.currentStep();
    }
}
