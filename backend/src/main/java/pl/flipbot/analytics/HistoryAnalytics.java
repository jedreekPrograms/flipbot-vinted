package pl.flipbot.analytics;

import java.util.List;
import java.util.Set;
import pl.flipbot.listing.HistoryOutcome;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingHistoryMetadata;
import pl.flipbot.listing.MissedOpportunityReason;
import pl.flipbot.listing.OfferAssessment;

final class HistoryAnalytics {
    private HistoryAnalytics() {}

    static boolean matchesOutcomeFilter(Listing listing, Set<HistoryOutcome> outcomes) {
        HistoryOutcome effective = ListingHistoryMetadata.effectiveOutcome(listing);

        if (outcomes.contains(effective)) {
            return true;
        }

        return outcomes.contains(HistoryOutcome.REJECTED)
                && effective == HistoryOutcome.MISSED_OPPORTUNITY;
    }

    static boolean isPurchased(Listing listing) {
        return ListingHistoryMetadata.effectiveOutcome(listing)
                == HistoryOutcome.PURCHASED;
    }

    static boolean isNotPurchased(Listing listing) {
        HistoryOutcome outcome = ListingHistoryMetadata.effectiveOutcome(listing);

        return outcome == HistoryOutcome.REJECTED
                || outcome == HistoryOutcome.MISSED_OPPORTUNITY;
    }

    static boolean isMissedOpportunity(Listing listing) {
        if (ListingHistoryMetadata.effectiveOutcome(listing) == HistoryOutcome.MISSED_OPPORTUNITY) {
            return true;
        }

        if (!isNotPurchased(listing)) {
            return false;
        }

        MissedOpportunityReason reason = listing.getMissedOpportunityReason();
        return reason == MissedOpportunityReason.SOLD_BEFORE_PURCHASE
                || reason == MissedOpportunityReason.TOO_SLOW;
    }

    static long countAssessment(List<Listing> history, OfferAssessment assessment) {
        return history.stream()
                .filter(listing -> ListingHistoryMetadata.effectiveAssessment(listing) == assessment)
                .count();
    }
}
