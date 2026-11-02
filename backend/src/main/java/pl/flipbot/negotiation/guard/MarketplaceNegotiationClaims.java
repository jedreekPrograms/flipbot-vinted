package pl.flipbot.negotiation.guard;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pl.flipbot.listing.Listing;

/** Durable cross-bot ownership; all calls participate in the action-guard transaction. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketplaceNegotiationClaims {
    private final JdbcTemplate jdbcTemplate;

    MarketplaceClaimResult acquire(
            Listing listing,
            UUID requestId
    ) {
        String marketplace = resolveMarketplace(listing);

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO marketplace_negotiation_claim (
                    marketplace,
                    marketplace_listing_id,
                    owner_bot_id,
                    owner_listing_id,
                    request_id,
                    claimed_at,
                    confirmed_at
                )
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, NULL)
                ON CONFLICT (marketplace, marketplace_listing_id) DO NOTHING
                """,
                marketplace,
                listing.getListingId(),
                listing.getBot().getId(),
                listing.getId(),
                requestId
        );

        if (inserted == 1) {
            log.warn(
                    "[MARKETPLACE CLAIM] ACQUIRED PRE-SUBMIT {}:{} for bot {}, backend listing {}, requestId={}",
                    marketplace,
                    listing.getListingId(),
                    listing.getBot().getId(),
                    listing.getId(),
                    requestId
            );
            return MarketplaceClaimResult.ACQUIRED;
        }

        Map<String, Object> owner = findMarketplaceOwner(
                marketplace,
                listing.getListingId()
        );

        boolean sameOwner = isSameOwner(owner, listing);
        if (sameOwner) {
            return MarketplaceClaimResult.ACQUIRED;
        }

        boolean confirmed = owner.get("confirmed_at") != null;

        log.warn(
                "[MARKETPLACE CLAIM] CONFLICT for {}:{} requested by bot {}, backend listing {}. Existing owner bot={}, backend listing={}, confirmedAt={}.",
                marketplace,
                listing.getListingId(),
                listing.getBot().getId(),
                listing.getId(),
                owner.get("owner_bot_id"),
                owner.get("owner_listing_id"),
                owner.get("confirmed_at")
        );

        return confirmed
                ? MarketplaceClaimResult.BLOCKED_CONFIRMED
                : MarketplaceClaimResult.BLOCKED_PRE_SUBMIT;
    }

    boolean ownsOrRecover(
            Listing listing,
            UUID requestId
    ) {
        String marketplace = resolveMarketplace(listing);

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO marketplace_negotiation_claim (
                    marketplace,
                    marketplace_listing_id,
                    owner_bot_id,
                    owner_listing_id,
                    request_id,
                    claimed_at,
                    confirmed_at
                )
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (marketplace, marketplace_listing_id) DO NOTHING
                """,
                marketplace,
                listing.getListingId(),
                listing.getBot().getId(),
                listing.getId(),
                requestId
        );

        if (inserted == 1) {
            log.warn(
                    "[MARKETPLACE CLAIM] Recovered missing durable ownership for existing negotiation {}:{} -> bot {}, backend listing {}.",
                    marketplace,
                    listing.getListingId(),
                    listing.getBot().getId(),
                    listing.getId()
            );
            return true;
        }

        Map<String, Object> owner = findMarketplaceOwner(
                marketplace,
                listing.getListingId()
        );

        if (!isSameOwner(owner, listing)) {
            return false;
        }

        if (owner.get("confirmed_at") == null) {
            int confirmed = jdbcTemplate.update(
                    """
                    UPDATE marketplace_negotiation_claim
                    SET confirmed_at = CURRENT_TIMESTAMP
                    WHERE marketplace = ?
                      AND marketplace_listing_id = ?
                      AND owner_bot_id = ?
                      AND owner_listing_id = ?
                      AND confirmed_at IS NULL
                    """,
                    marketplace,
                    listing.getListingId(),
                    listing.getBot().getId(),
                    listing.getId()
            );

            if (confirmed != 1) {
                throw new IllegalStateException(
                        "Could not confirm marketplace ownership for existing negotiation "
                                + marketplace
                                + ":"
                                + listing.getListingId()
                );
            }
        }

        return true;
    }

    private Map<String, Object> findMarketplaceOwner(
            String marketplace,
            String marketplaceListingId
    ) {
        return jdbcTemplate.queryForMap(
                """
                SELECT owner_bot_id,
                       owner_listing_id,
                       request_id,
                       claimed_at,
                       confirmed_at
                FROM marketplace_negotiation_claim
                WHERE marketplace = ?
                  AND marketplace_listing_id = ?
                """,
                marketplace,
                marketplaceListingId
        );
    }

    private boolean isSameOwner(
            Map<String, Object> owner,
            Listing listing
    ) {
        return Objects.equals(
                asLong(owner.get("owner_bot_id")),
                listing.getBot().getId()
        ) && Objects.equals(
                asLong(owner.get("owner_listing_id")),
                listing.getId()
        );
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null
                ? null
                : Long.valueOf(value.toString());
    }

    void confirmIfFirstOffer(
            Listing listing,
            RealActionGuard guard
    ) {
        if (guard.getActionType() == RealActionType.FIRST_OFFER) {
            confirm(
                    listing,
                    guard.getRequestId()
            );
        }
    }

    void confirm(
            Listing listing,
            UUID requestId
    ) {
        String marketplace = resolveMarketplace(listing);

        int updated = jdbcTemplate.update(
                """
                UPDATE marketplace_negotiation_claim
                SET confirmed_at = COALESCE(confirmed_at, CURRENT_TIMESTAMP)
                WHERE marketplace = ?
                  AND marketplace_listing_id = ?
                  AND owner_bot_id = ?
                  AND owner_listing_id = ?
                  AND request_id = ?
                """,
                marketplace,
                listing.getListingId(),
                listing.getBot().getId(),
                listing.getId(),
                requestId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Could not confirm marketplace negotiation claim for "
                            + marketplace
                            + ":"
                            + listing.getListingId()
                            + ". Failing closed."
            );
        }
    }

    void releaseBeforeSubmit(
            Listing listing,
            UUID requestId
    ) {
        String marketplace = resolveMarketplace(listing);

        int deleted = jdbcTemplate.update(
                """
                DELETE FROM marketplace_negotiation_claim
                WHERE marketplace = ?
                  AND marketplace_listing_id = ?
                  AND owner_bot_id = ?
                  AND owner_listing_id = ?
                  AND request_id = ?
                  AND confirmed_at IS NULL
                """,
                marketplace,
                listing.getListingId(),
                listing.getBot().getId(),
                listing.getId(),
                requestId
        );

        if (deleted != 1) {
            throw new IllegalStateException(
                    "Could not release pre-submit marketplace negotiation claim for "
                            + marketplace
                            + ":"
                            + listing.getListingId()
                            + ". Ownership changed or the claim is already confirmed. Failing closed."
            );
        }

        log.info(
                "[MARKETPLACE CLAIM] RELEASED PRE-SUBMIT {}:{} for bot {}, backend listing {}, requestId={}",
                marketplace,
                listing.getListingId(),
                listing.getBot().getId(),
                listing.getId(),
                requestId
        );
    }

    private String resolveMarketplace(Listing listing) {
        if (listing.getBot() == null
                || listing.getBot().getConfiguration() == null
                || listing.getBot().getConfiguration().getMarketplace() == null) {
            throw new IllegalStateException(
                    "Cannot protect marketplace negotiation for backend listing "
                            + listing.getId()
                            + " because marketplace configuration is missing"
            );
        }

        return listing.getBot().getConfiguration().getMarketplace().name();
    }

    enum MarketplaceClaimResult {
        ACQUIRED,
        BLOCKED_PRE_SUBMIT,
        BLOCKED_CONFIRMED
    }
}
