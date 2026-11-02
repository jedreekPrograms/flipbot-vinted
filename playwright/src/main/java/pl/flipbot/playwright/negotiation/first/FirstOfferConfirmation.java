package pl.flipbot.playwright.negotiation.first;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.listing.dto.UpdateListingRequestDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import pl.flipbot.playwright.negotiation.NegotiationSelectors;
import pl.flipbot.playwright.negotiation.shared.VintedPriceParser;

/** Browser operations for confirming a submitted first offer and updating its backend state. */
@Slf4j
public final class FirstOfferConfirmation {
    private static final double CONVERSATION_TIMEOUT_MS =
            30_000;

    private static final double CHAT_ELEMENT_TIMEOUT_MS =
            20_000;

    private final BotContext context;
    private final ListingClient listingClient = new ListingClient();
    private final HumanVerificationHandler humanVerificationHandler = new HumanVerificationHandler();
    public FirstOfferConfirmation(BotContext context) { this.context = context; }

    public String waitForConversationUrl(Page page, ListingResponseDto listing) {
        try {
            page.waitForURL(
                    "**/inbox/**",
                    new Page.WaitForURLOptions()
                            .setTimeout(CONVERSATION_TIMEOUT_MS)
            );
        } catch (TimeoutError exception) {
            throw new IllegalStateException(
                    "Offer submit was attempted, but Vinted did not navigate "
                            + "to an inbox conversation within "
                            + Math.round(CONVERSATION_TIMEOUT_MS / 1_000)
                            + " seconds. Marketplace listing: "
                            + listing.listingId()
                            + ", current URL: "
                            + page.url(),
                    exception
            );
        }

        humanVerificationHandler.waitUntilVerified(page);

        String conversationUrl =
                page.url();

        if (conversationUrl == null || conversationUrl.isBlank() || !conversationUrl.contains("/inbox/")) {
            throw new IllegalStateException(
                    "Invalid conversation URL after sending offer: "
                            + conversationUrl
            );
        }

        log.info(
                "[REAL OFFER] Vinted opened conversation for marketplace "
                        + "listing {}. URL: {}",
                listing.listingId(),
                conversationUrl
        );

        return conversationUrl;
    }

    public String extractConversationId(String conversationUrl) {
        URI uri =
                URI.create(conversationUrl);

        String path =
                uri.getPath();

        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Conversation URL has no path: " + conversationUrl);
        }

        String[] pathParts =
                path.split("/");

        for (int i = 0; i < pathParts.length - 1; i++) {
            if ("inbox".equals(pathParts[i])) {
                String conversationId =
                        pathParts[i + 1];

                if (conversationId != null && !conversationId.isBlank()) {
                    return conversationId;
                }
            }
        }

        throw new IllegalArgumentException("Cannot extract conversation ID from URL: " + conversationUrl);
    }

    public void validateConversationReferrer(String conversationUrl, ListingResponseDto listing) {
        URI uri =
                URI.create(conversationUrl);

        String rawQuery =
                uri.getRawQuery();

        if (rawQuery == null || rawQuery.isBlank()) {
            log.warn(
                    "[REAL OFFER] Conversation URL has no query parameters. "
                            + "Cannot verify referrer for listing {}.",
                    listing.listingId()
            );

            return;
        }

        String decodedQuery =
                URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);

        if (!decodedQuery.contains(listing.listingId())) {
            log.warn(
                    "[REAL OFFER] Conversation URL referrer does not contain "
                            + "marketplace listing ID {}. Decoded query: {}",
                    listing.listingId(),
                    decodedQuery
            );

            return;
        }

        log.info(
                "[REAL OFFER] Conversation referrer matches marketplace "
                        + "listing {}.",
                listing.listingId()
        );
    }

    public ListingResponseDto markNegotiationStarted(
            ListingResponseDto listing,
            BigDecimal offerPrice,
            Integer stepNumber,
            String conversationId,
            String conversationUrl
    ) {
        UpdateListingRequestDto request =
                new UpdateListingRequestDto(
                        "NEGOTIATING",
                        offerPrice,
                        stepNumber,
                        true,
                        conversationId,
                        conversationUrl
                );

        ListingResponseDto updatedListing =
                listingClient.updateListing(context.getBot().getId(), listing.id(), request);

        if (!"NEGOTIATING".equals(updatedListing.status())) {
            throw new IllegalStateException(
                    "Backend returned unexpected listing status after "
                            + "starting negotiation. Expected NEGOTIATING, "
                            + "actual: "
                            + updatedListing.status()
            );
        }

        if (!conversationId.equals(updatedListing.conversationId())) {
            throw new IllegalStateException(
                    "Backend returned unexpected conversation ID. Expected: "
                            + conversationId
                            + ", actual: "
                            + updatedListing.conversationId()
            );
        }

        return updatedListing;
    }

    public void verifySubmittedOfferSafely(
            Page page,
            ListingResponseDto listing,
            BigDecimal expectedPrice
    ) {
        try {
            Locator ownOfferPrice =
                    page.getByTestId(NegotiationSelectors.OWN_OFFER_PRICE)
                            .last();

            ownOfferPrice.waitFor(
                    new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(CHAT_ELEMENT_TIMEOUT_MS)
            );

            String rawPrice =
                    ownOfferPrice.innerText();

            BigDecimal actualPrice =
                    VintedPriceParser.parse(rawPrice);

            if (expectedPrice.compareTo(actualPrice) != 0) {
                log.error(
                        "[REAL OFFER] Submitted offer price does not match. "
                                + "Backend listing={}, expected={}, "
                                + "displayed={}, raw='{}'.",
                        listing.id(),
                        expectedPrice,
                        actualPrice,
                        rawPrice
                );
            } else {
                log.info(
                        "[REAL OFFER] Submitted offer price confirmed in "
                                + "conversation: {}.",
                        actualPrice
                );
            }

            logOwnOfferStatus(page);
        } catch (Exception exception) {
            log.warn(
                    "[REAL OFFER] Could not fully verify submitted offer "
                            + "inside conversation for marketplace listing {}. "
                            + "Backend remains NEGOTIATING.",
                    listing.listingId()
            );

            log.trace("[REAL OFFER] Full submitted-offer verification error.", exception);
        }
    }

    private void logOwnOfferStatus(Page page) {
        Locator ownOfferStatus =
                page.getByTestId(NegotiationSelectors.OWN_OFFER_STATUS)
                        .last();

        try {
            ownOfferStatus.waitFor(
                    new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(CHAT_ELEMENT_TIMEOUT_MS)
            );

            log.info(
                    "[REAL OFFER] Current own-offer status displayed by "
                            + "Vinted: {}",
                    ownOfferStatus.innerText()
            );
        } catch (TimeoutError exception) {
            log.warn("[REAL OFFER] Own-offer status did not become visible " + "inside conversation.");
        }
    }
}
