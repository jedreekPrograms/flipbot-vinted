package pl.flipbot.playwright.catalog;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.TargetBoundListingClient;
import pl.flipbot.playwright.api.listing.dto.CreateListingRequestDto;
import pl.flipbot.playwright.api.listing.dto.DiscoverListingsRequestDto;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.scanner.model.Listing;

@Slf4j
@RequiredArgsConstructor
public class ListingDiscoveryService {

    private final BotContext context;

    private final TargetBoundListingClient listingClient;

    public List<ListingResponseDto> process(
            List<Listing> listings
    ) {

        if (listings == null
                || listings.isEmpty()) {

            log.info(
                    "No listings to process for bot {} target {}",
                    context.getBot().getId(),
                    "CLIENT_SCOPE"
            );

            return List.of();

        }

        log.info(
                "Preparing {} listings for backend verification for target {}",
                listings.size(),
                "CLIENT_SCOPE"
        );

        List<CreateListingRequestDto> requestListings =
                new ArrayList<>();

        int skippedListings = 0;

        for (Listing listing : listings) {

            if (!isValid(listing)) {

                skippedListings++;

                log.warn(
                        "Skipping incomplete listing before backend request: "
                                + "id={}, title={}, url={}, price={}",
                        listing == null
                                ? null
                                : listing.getId(),
                        listing == null
                                ? null
                                : listing.getTitle(),
                        listing == null
                                ? null
                                : listing.getUrl(),
                        listing == null
                                ? null
                                : listing.getPrice()
                );

                continue;

            }

            requestListings.add(
                    new CreateListingRequestDto(
                            listing.getId(),
                            listing.getTitle(),
                            listing.getUrl(),
                            listing.getPrice()
                    )
            );

        }

        log.info(
                "Prepared {} valid listings, skipped {} incomplete listings",
                requestListings.size(),
                skippedListings
        );

        if (requestListings.isEmpty()) {
            return List.of();
        }

        DiscoverListingsRequestDto request =
                new DiscoverListingsRequestDto(
                        requestListings
                );

        List<ListingResponseDto> claimedListings = listingClient.discoverListings(
                context.getBot().getId(),
                request
        );

        log.info(
                "Bot {} target {} received {} new backend listings for further processing",
                context.getBot().getId(),
                "CLIENT_SCOPE",
                claimedListings.size()
        );

        return claimedListings;

    }

    private boolean isValid(
            Listing listing
    ) {

        if (listing == null) {
            return false;
        }

        return isNotBlank(
                listing.getId()
        )
                && isNotBlank(
                listing.getTitle()
        )
                && isNotBlank(
                listing.getUrl()
        )
                && listing.getPrice() != null;

    }

    private boolean isNotBlank(
            String value
    ) {

        return value != null
                && !value.isBlank();

    }

}
