package pl.flipbot.playwright.negotiation;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.negotiation.conversation.ConversationReader;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;
import pl.flipbot.playwright.target.VintedRateLimitException;

/** Bounded read-only late-response recovery; never submits a buyer offer. */
@Slf4j
@lombok.RequiredArgsConstructor
final class TerminalConversationRecovery {
    private final BotContext context;
    private final ListingClient listingClient;
    private final ListingStatusUpdater listingStatusUpdater;
    private final ConversationReader conversationProcessor;
    private final ExistingNegotiationSupport support;
    private final NegotiationContactGuard contactGuard;
    private final TerminalNegotiationRecoveryPolicy terminalRecoveryPolicy = new TerminalNegotiationRecoveryPolicy();

    void inspect(
            List<ListingResponseDto> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        BotConfigurationDto currentProductConfiguration =
                context.getBot().getConfiguration();
        if (currentProductConfiguration == null) {
            throw new IllegalStateException("Bot configuration is missing");
        }

        log.info(
                "[NEGOTIATION RECOVERY] Inspecting {} recent terminal conversation candidate(s) read-only after active negotiations.",
                candidates.size()
        );

        for (ListingResponseDto listing : candidates) {
            // Also reject a stale response from a backend that has not restarted yet.
            if ("UNAVAILABLE".equals(listing.status()) || "CONTACT_UNAVAILABLE".equals(listing.status())) {
                continue;
            }
            BotConfigurationDto configuration =
                    NegotiationStrategyConfigurationResolver.resolve(
                            currentProductConfiguration,
                            listing.negotiationStrategySnapshot()
                    );

            context.getBot().setConfiguration(configuration);
            boolean transitioned = false;
            boolean inspectionAborted = false;

            try {
                NegotiationConversationSnapshot snapshot =
                        conversationProcessor.inspectRecoverySnapshot(listing);

                TerminalNegotiationRecoveryDecision recovery =
                        terminalRecoveryPolicy.decide(
                                listing,
                                snapshot,
                                configuration
                        );

                if (recovery.action() != TerminalNegotiationRecoveryDecision.Action.MARK_ACTION_REQUIRED) {
                    if (new ConversationAvailabilityDetector(context).isUnavailable(listing)) {
                        listingStatusUpdater.markNegotiationUnavailable(listing);
                        contactGuard.clear(listing);
                        transitioned = true;
                        log.info("[NEGOTIATION RECOVERY] Listing {} changed from {} to UNAVAILABLE because Vinted reports it sold or removed. It leaves automatic recovery as well as active negotiations.",
                                listing.listingId(), listing.status());
                        continue;
                    }
                    if (contactGuard.shouldSkip(listing)) {
                        continue;
                    }
                }

                switch (recovery.action()) {
                    case KEEP_TERMINAL -> log.info(
                            "[NEGOTIATION RECOVERY] Listing {} stays {}. Live result={}. Reason: {}",
                            listing.listingId(),
                            listing.status(),
                            snapshot.result(),
                            recovery.reason()
                    );

                    case REOPEN_NEGOTIATING -> {
                        ListingResponseDto reopened =
                                listingClient.reopenNegotiationForRecovery(
                                        context.getBot().getId(),
                                        listing.id(),
                                        recovery.awaitingSellerResponse(),
                                        recovery.reason()
                                );
                        transitioned = true;

                        log.warn(
                                "[NEGOTIATION RECOVERY] Listing {} recovered from {} to {} at step {}. No offer was sent by the recovery layer.",
                                reopened.listingId(),
                                listing.status(),
                                reopened.status(),
                                reopened.currentStep()
                        );
                    }

                    case MARK_ACTION_REQUIRED -> {
                        ListingResponseDto updated =
                                listingStatusUpdater.markActionRequired(
                                        listing,
                                        recovery.negotiationDecision()
                                );
                        transitioned = true;
                        contactGuard.clear(listing);

                        log.warn(
                                "[NEGOTIATION RECOVERY] Fresh seller decision recovered from terminal history. Listing {} changed from {} to ACTION_REQUIRED at price {}. No buyer offer was sent. Reason: {}",
                                updated.listingId(),
                                listing.status(),
                                updated.currentPrice(),
                                recovery.reason()
                        );
                    }
                }
            } catch (VintedRateLimitException | VintedAuthenticationRequiredException exception) {
                inspectionAborted = true;
                throw exception;
            } catch (Exception exception) {
                log.error(
                        "[NEGOTIATION RECOVERY] Failed to inspect terminal backend listing {}, marketplace listing {}, conversation {}: {}",
                        listing.id(),
                        listing.listingId(),
                        listing.conversationId(),
                        support.friendlyError(exception)
                );
                log.trace(
                        "[NEGOTIATION RECOVERY] Full exception for backend listing {}.",
                        listing.id(),
                        exception
                );
            } finally {
                if (!transitioned && !inspectionAborted) {
                    try {
                        listingClient.markNegotiationRecoveryChecked(
                                context.getBot().getId(),
                                listing.id()
                        );
                    } catch (Exception exception) {
                        log.warn(
                                "[NEGOTIATION RECOVERY] Could not persist recovery throttle for backend listing {}: {}",
                                listing.id(),
                                support.friendlyError(exception)
                        );
                    }
                }

                context.getBot().setConfiguration(
                        currentProductConfiguration
                );
            }
        }
    }

}
