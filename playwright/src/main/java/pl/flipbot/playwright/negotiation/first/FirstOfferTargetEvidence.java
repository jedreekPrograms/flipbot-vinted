package pl.flipbot.playwright.negotiation.first;

import java.util.Set;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.model.BotConfigurationDto;


final class FirstOfferTargetEvidence {
    private static final String VINTED_MODEL = "VINTED_MODEL";

    private FirstOfferTargetEvidence() {}

    static boolean hasCurrentExactModelProof(
            ListingResponseDto listing,
            BotConfigurationDto configuration,
            Set<String> currentScanListingIds
    ) {
        return listing != null
                && listing.listingId() != null
                && usesExactVintedModelFilter(configuration)
                && currentScanListingIds != null
                && currentScanListingIds.contains(listing.listingId());
    }

    static boolean usesExactVintedModelFilter(BotConfigurationDto configuration) {
        if (configuration == null) {
            return false;
        }

        String targetMode = configuration.getTargetMode();
        return targetMode == null
                || targetMode.isBlank()
                || VINTED_MODEL.equalsIgnoreCase(targetMode.trim());
    }

    static String getConfiguredTargetLabel(BotConfigurationDto configuration) {
        if (configuration == null) {
            return "null";
        }

        if ("SEARCH_QUERY".equalsIgnoreCase(configuration.getTargetMode())) {
            return configuration.getSearchQuery();
        }

        if (usesExactVintedModelFilter(configuration)) {
            return configuration.getModel();
        }

        return "unknown";
    }
}
