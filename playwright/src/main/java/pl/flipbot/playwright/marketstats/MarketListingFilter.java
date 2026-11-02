package pl.flipbot.playwright.marketstats;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.scanner.model.Listing;
import pl.flipbot.playwright.target.ListingTargetAssessment;
import pl.flipbot.playwright.target.ListingTargetMatcher;

final class MarketListingFilter {
    private static final Set<String> ACCESSORY_WORDS = Set.of(
            "etui", "case", "cover", "pokrowiec", "obudowa", "szklo",
            "folia", "protector", "ladowarka", "charger", "kabel", "cable",
            "uchwyt", "holder", "digitizer", "czesci", "parts", "dummy", "atrapa"
    );

    private MarketListingFilter() {}

    static boolean matchesTarget(
            Listing listing,
            BotConfigurationDto configuration,
            boolean accessoryFiltering,
            ListingTargetMatcher matcher
    ) {
        if (accessoryFiltering && looksLikeAccessory(listing)) {
            return false;
        }

        ListingResponseDto candidate = new ListingResponseDto(
                null,
                listing.getId(),
                listing.getTitle(),
                listing.getUrl(),
                listing.getPrice(),
                listing.getPrice(),
                0,
                false,
                null,
                null,
                "DISCOVERED",
                null
        );

        ListingTargetAssessment catalogAssessment = matcher.assessCatalogListing(candidate, configuration);

        if (catalogAssessment == ListingTargetAssessment.MATCH) {
            return true;
        }

        if (catalogAssessment == ListingTargetAssessment.MISMATCH) {
            return false;
        }

        return matcher.assessListingUrl(candidate, configuration)
                == ListingTargetAssessment.MATCH;
    }

    private static boolean looksLikeAccessory(Listing listing) {
        String normalized = normalizeForAccessoryCheck(
                String.valueOf(listing.getTitle()) + " " + String.valueOf(listing.getUrl())
        );

        if (normalized.isBlank()) {
            return false;
        }

        String padded = " " + normalized + " ";

        for (String accessoryWord : ACCESSORY_WORDS) {
            if (padded.contains(" " + accessoryWord + " ")) {
                return true;
            }
        }

        return false;
    }

    private static String normalizeForAccessoryCheck(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD);

        return normalized
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
