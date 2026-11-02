package pl.flipbot.playwright.negotiation.first;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;
import pl.flipbot.playwright.model.NegotiationStepDto;
import pl.flipbot.playwright.negotiation.NegotiationPreparationResult;
import pl.flipbot.playwright.negotiation.NegotiationSelectors;
import pl.flipbot.playwright.negotiation.NegotiationStartResult;
import pl.flipbot.playwright.negotiation.shared.OfferMessageSender;
import pl.flipbot.playwright.negotiation.shared.VintedPriceParser;
import pl.flipbot.playwright.util.ErrorMessages;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Slf4j
public class FirstOfferAction {
    private static final double ELEMENT_TIMEOUT_MS =
            15_000;

    private final BotContext context;

    private final HumanVerificationHandler humanVerificationHandler =
            new HumanVerificationHandler();

    private final FirstOfferTerms terms;
    private final FirstOfferItemPage firstOfferItemPage;
    private final FirstOfferForm firstOfferForm = new FirstOfferForm();
    private final FirstOfferConfirmation firstOfferConfirmation;
    private final OfferMessageSender messageSender = new OfferMessageSender();
    private PreparedOffer preparedOffer;

    public FirstOfferAction(BotContext context) {
        this.context = context;
        this.terms = new FirstOfferTerms(context);
        this.firstOfferItemPage = new FirstOfferItemPage(context);
        this.firstOfferConfirmation = new FirstOfferConfirmation(context);
    }

    /**
     * Przygotowuje formularz pierwszej oferty, ale NIE klika submit.
     *
     * Ta metoda musi być wykonana PRZED reserveSlot().
     */
    public NegotiationPreparationResult prepareFirstOffer(ListingResponseDto listing) {
        Objects.requireNonNull(listing, "Listing cannot be null");

        clearPreparedState();

        terms.validateListing(listing);

        NegotiationStepDto firstStep =
                terms.getFirstNegotiationStep();

        BigDecimal offerPrice =
                firstStep.getOfferPrice();

        Integer stepNumber =
                firstStep.getStepNumber();

        terms.validateOfferPrice(offerPrice);

        if (stepNumber == null) {
            throw new IllegalStateException("First negotiation step has no step number");
        }

        if (terms.isBelowEstimatedVintedMinimum(listing, offerPrice)) {
            return NegotiationPreparationResult
                    .OFFER_TOO_LOW;
        }

        Page page =
                context.getPage();

        try {
            log.info(
                    "[REAL OFFER PREPARE] Preparing first offer for "
                            + "backend listing {}, marketplace listing {}, "
                            + "step {}, price {}. No quota has been reserved.",
                    listing.id(),
                    listing.listingId(),
                    stepNumber,
                    offerPrice
            );

            firstOfferItemPage.navigateToListingIfNeeded(page, listing);

            boolean listingAvailable =
                    firstOfferItemPage.waitForListingPage(page, listing);

            if (!listingAvailable) {
                log.info(
                        "[REAL OFFER PREPARE] Marketplace listing {} "
                                + "is no longer available. "
                                + "No quota was reserved and no offer was sent.",
                        listing.listingId()
                );

                return NegotiationPreparationResult
                        .LISTING_UNAVAILABLE;
            }

            Locator offerButton =
                    firstOfferItemPage.waitForOfferButtonOrNull(page, listing);

            if (offerButton == null) {
                if (firstOfferItemPage.isListingUnavailable(page)) {
                    log.info(
                            "[REAL OFFER PREPARE] Marketplace listing {} "
                                    + "became unavailable while checking "
                                    + "whether negotiation is possible. "
                                    + "No quota was reserved and no offer "
                                    + "was sent.",
                            listing.listingId()
                    );

                    return NegotiationPreparationResult
                            .LISTING_UNAVAILABLE;
                }

                log.warn(
                        "[REAL OFFER PREPARE] Marketplace listing {} is "
                                + "available, but this account currently has "
                                + "no visible action for starting a price "
                                + "negotiation. This can happen because of "
                                + "seller/account restrictions, blocking, or "
                                + "listing-specific Vinted rules. The "
                                + "candidate will be skipped and persisted "
                                + "as CANNOT_NEGOTIATE by the caller. "
                                + "No quota was reserved and no offer was sent.",
                        listing.listingId()
                );

                return NegotiationPreparationResult
                        .CANNOT_NEGOTIATE;
            }

            firstOfferForm.openOfferModal(page, listing, offerButton);

            boolean offerPrepared =
                    fillOfferPrice(page, listing, offerPrice);

            if (!offerPrepared) {
                log.info(
                        "[REAL OFFER PREPARE] Marketplace listing {} "
                                + "was rejected before submission because "
                                + "the proposed price is below the minimum "
                                + "accepted by Vinted. No quota was reserved.",
                        listing.listingId()
                );

                return NegotiationPreparationResult
                        .OFFER_TOO_LOW;
            }

            preparedOffer =
                    new PreparedOffer(
                            listing.id(),
                            listing.listingId(),
                            offerPrice,
                            stepNumber,
                            firstStep.getMessage()
                    );

            assertPreparedOfferReady(listing);

            log.warn(
                    "[REAL OFFER PREPARE] Marketplace listing {} is fully "
                            + "prepared for submission. Offer form is open, "
                            + "price={}, submit button is visible and enabled. "
                            + "No quota has been reserved and submit has NOT "
                            + "been clicked.",
                    listing.listingId(),
                    offerPrice
            );

            return NegotiationPreparationResult
                    .PREPARED;
        } catch (RuntimeException exception) {
            clearPreparedState();

            throw exception;
        }
    }

    /**
     * Ostatni check wykonywany jeszcze PRZED reserveSlot().
     */
    public void assertPreparedOfferReady(ListingResponseDto listing) {
        requireMatchingPreparedOffer(listing);
        firstOfferForm.assertReady(context.getPage(), listing, preparedOffer.offerPrice(), firstOfferItemPage);
    }

    /**
     * Wywołuj DOPIERO po udanym reserveSlot().
     *
     * Od momentu rozpoczęcia tej metody błąd traktujemy konserwatywnie:
     * quota nie powinna być automatycznie zwalniana, bo klik submit może
     * zostać wykonany lub jego wynik może być niejednoznaczny.
     */
    public NegotiationStartResult submitPreparedFirstNegotiation(ListingResponseDto listing) {
        requireMatchingPreparedOffer(listing);

        Page page =
                context.getPage();

        PreparedOffer offer =
                preparedOffer;

        Locator submitButton =
                page.getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON)
                        .first();

        log.warn(
                "[REAL OFFER] Quota is reserved. Clicking the offer submit "
                        + "button for marketplace listing {}. "
                        + "This action sends a REAL offer of {}.",
                listing.listingId(),
                offer.offerPrice()
        );

        /*
         * Nie robimy tutaj dodatkowych operacji, które mogłyby rzucić
         * wyjątek przed kliknięciem. Wszystkie bezpieczne checki odbyły się
         * w assertPreparedOfferReady() przed reserveSlot().
         *
         * Jeśli samo click() rzuci wyjątek, wynik dostarczenia może być
         * niejednoznaczny, dlatego caller zachowuje quota.
         */
        submitButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

        String conversationUrl =
                firstOfferConfirmation.waitForConversationUrl(page, listing);

        String conversationId =
                firstOfferConfirmation.extractConversationId(conversationUrl);

        firstOfferConfirmation.validateConversationReferrer(conversationUrl, listing);

        ListingResponseDto updatedListing =
                firstOfferConfirmation.markNegotiationStarted(
                        listing,
                        offer.offerPrice(),
                        offer.stepNumber(),
                        conversationId,
                        conversationUrl
                );

        log.info(
                "[REAL OFFER] Backend listing {} was updated. "
                        + "Status={}, conversationId={}, step={}, price={}.",
                updatedListing.id(),
                updatedListing.status(),
                updatedListing.conversationId(),
                updatedListing.currentStep(),
                updatedListing.currentPrice()
        );

        firstOfferConfirmation.verifySubmittedOfferSafely(page, listing, offer.offerPrice());

        messageSender.sendAfterOffer(page, listing, offer.message());

        log.warn(
                "[REAL OFFER] Negotiation STARTED for marketplace listing {}. "
                        + "Conversation ID: {}.",
                listing.listingId(),
                conversationId
        );

        clearPreparedState();

        return NegotiationStartResult
                .STARTED;
    }

    /**
     * Reconciles only a strongly-confirmed post-submit state. This method never
     * clicks submit and never guesses from a missing button/redirect alone.
     */
    public boolean reconcilePreparedFirstNegotiationAfterAmbiguousSubmit(ListingResponseDto listing) {
        if (preparedOffer == null || listing == null) {
            return false;
        }

        try {
            requireMatchingPreparedOffer(listing);

            Page page = context.getPage();
            String conversationUrl = page.url();

            if (conversationUrl == null
                    || !MarketplaceUrls.isVintedUrl(conversationUrl)
                    || !conversationUrl.contains("/inbox/")) {
                log.warn(
                        "[REAL OFFER RECONCILE] Cannot confirm marketplace listing {} after ambiguous submit because the page is not a trusted Vinted conversation. URL={}",
                        listing.listingId(),
                        conversationUrl
                );
                return false;
            }

            URI conversationUri = URI.create(conversationUrl);
            String rawQuery = conversationUri.getRawQuery();
            String decodedQuery = rawQuery == null
                    ? ""
                    : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);

            if (!decodedQuery.contains(listing.listingId())) {
                log.warn(
                        "[REAL OFFER RECONCILE] Conversation URL does not strongly bind to marketplace listing {}. "
                                + "Referrer/query='{}'. Keeping the action ambiguous.",
                        listing.listingId(),
                        decodedQuery
                );
                return false;
            }

            String conversationId = firstOfferConfirmation.extractConversationId(conversationUrl);

            Locator ownOfferPrices =
                    page.getByTestId(NegotiationSelectors.OWN_OFFER_PRICE);
            int count = ownOfferPrices.count();

            if (count <= 0) {
                log.warn(
                        "[REAL OFFER RECONCILE] Conversation {} is open for listing {}, but no own-offer price is visible. Keeping the action ambiguous.",
                        conversationId,
                        listing.listingId()
                );
                return false;
            }

            Locator latestOwnOffer = ownOfferPrices.nth(count - 1);
            if (!latestOwnOffer.isVisible()) {
                return false;
            }

            BigDecimal displayedPrice = VintedPriceParser.parse(latestOwnOffer.innerText());
            if (displayedPrice.compareTo(preparedOffer.offerPrice()) != 0) {
                log.error(
                        "[REAL OFFER RECONCILE] Refusing reconciliation for listing {} because latest own offer price {} differs from prepared price {}.",
                        listing.listingId(),
                        displayedPrice,
                        preparedOffer.offerPrice()
                );
                return false;
            }

            ListingResponseDto updatedListing = firstOfferConfirmation.markNegotiationStarted(
                    listing,
                    preparedOffer.offerPrice(),
                    preparedOffer.stepNumber(),
                    conversationId,
                    conversationUrl
            );

            messageSender.sendAfterOffer(page, listing, preparedOffer.message());

            log.warn(
                    "[REAL OFFER RECONCILE] Recovered confirmed first offer for listing {}. Backend status={}, conversation={}, price={}. No second submit was attempted.",
                    listing.listingId(),
                    updatedListing.status(),
                    conversationId,
                    displayedPrice
            );

            clearPreparedState();
            return true;
        } catch (RuntimeException exception) {
            log.warn(
                    "[REAL OFFER RECONCILE] Strong reconciliation did not complete for listing {}. Keeping the persistent action guard fail-closed. Reason: {}",
                    listing == null ? null : listing.listingId(),
                    ErrorMessages.conversationFailure(exception)
            );
            log.trace("[REAL OFFER RECONCILE] Full reconciliation failure.", exception);
            return false;
        }
    }

    public void cancelPreparedOfferSafely() {
        if (preparedOffer == null) {
            return;
        }

        try {
            firstOfferForm.closeOfferModal(context.getPage());
        } catch (Exception exception) {
            log.warn(
                    "[REAL OFFER PREPARE] Could not close prepared offer form "
                            + "for marketplace listing {}: {}",
                    preparedOffer.marketplaceListingId(),
                    ErrorMessages.conversationFailure(exception)
            );

            log.trace("[REAL OFFER PREPARE] Full prepared-form close error.", exception);
        } finally {
            clearPreparedState();
        }
    }

    private void requireMatchingPreparedOffer(ListingResponseDto listing) {
        if (preparedOffer == null) {
            throw new IllegalStateException("No prepared first offer exists");
        }

        if (
                !Objects.equals(preparedOffer.backendListingId(), listing.id())
                        || !Objects.equals(preparedOffer.marketplaceListingId(), listing.listingId())
        ) {
            throw new IllegalStateException(
                    "Prepared offer belongs to another listing. Prepared "
                            + "backend listing="
                            + preparedOffer.backendListingId()
                            + ", requested backend listing="
                            + listing.id()
            );
        }
    }

    private void clearPreparedState() {
        preparedOffer =
                null;
    }


    private boolean fillOfferPrice(Page page, ListingResponseDto listing, BigDecimal price) {
        boolean ready = firstOfferForm.fillOfferPrice(page, listing, price);
        if (!ready) clearPreparedState();
        return ready;
    }

    private record PreparedOffer(
            Long backendListingId,
            String marketplaceListingId,
            BigDecimal offerPrice,
            Integer stepNumber,
            String message
    ) {
    }
}
