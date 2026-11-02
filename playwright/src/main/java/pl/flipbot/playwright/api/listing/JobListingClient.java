package pl.flipbot.playwright.api.listing;

import java.util.List;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;

/** Read snapshot owned by one scheduled job, never reused by another bot/run. */
public final class JobListingClient extends ListingClient {
    private final Long botId;
    private final ListingClient source;
    private List<ListingResponseDto> negotiating;
    private List<ListingResponseDto> recovery;

    public JobListingClient(Long botId) {
        this(botId, new ListingClient());
    }

    JobListingClient(Long botId, ListingClient source) {
        this.botId = botId;
        this.source = source;
    }

    @Override
    public List<ListingResponseDto> getNegotiatingListings(Long requestedBotId) {
        requireOwner(requestedBotId);
        if (negotiating == null) {
            negotiating = List.copyOf(source.getNegotiatingListings(botId));
        }
        return negotiating;
    }

    @Override
    public List<ListingResponseDto> getNegotiationRecoveryCandidates(Long requestedBotId) {
        requireOwner(requestedBotId);
        if (recovery == null) {
            recovery = List.copyOf(source.getNegotiationRecoveryCandidates(botId));
        }
        return recovery;
    }

    private void requireOwner(Long requestedBotId) {
        if (!botId.equals(requestedBotId)) {
            throw new IllegalArgumentException("Listing snapshot belongs to another bot");
        }
    }
}
