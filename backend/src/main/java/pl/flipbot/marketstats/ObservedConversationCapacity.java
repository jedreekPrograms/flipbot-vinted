package pl.flipbot.marketstats;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import pl.flipbot.analytics.HistoryModelResolver;
import pl.flipbot.dictionary.DictionaryModel;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.negotiation.audit.RealActionAudit;
import pl.flipbot.negotiation.audit.RealActionAuditOutcome;
import pl.flipbot.negotiation.audit.RealActionAuditRepository;
import pl.flipbot.negotiation.guard.RealActionType;

/** Observed historical throughput for the calendar recommendation, not admission policy. */
@RequiredArgsConstructor
final class ObservedConversationCapacity {
    private static final int CAPACITY_LOOKBACK_DAYS = 28;
    private static final int FALLBACK_DAILY_CONVERSATION_CAPACITY = 5;
    private static final int HARD_DAILY_OFFER_LIMIT = 25;
    private final RealActionAuditRepository realActionAuditRepository;
    private final ListingRepository listingRepository;
    private final HistoryModelResolver historyModelResolver;

    Profile load(
            LocalDate today,
            List<DictionaryModel> models
    ) {
        LocalDateTime from = today
                .minusDays(CAPACITY_LOOKBACK_DAYS - 1L)
                .atStartOfDay();

        List<RealActionAudit> audits = realActionAuditRepository
                .findAllByActionTypeAndOutcomeAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        RealActionType.FIRST_OFFER,
                        RealActionAuditOutcome.CONFIRMED,
                        from
                );

        if (audits.isEmpty()) {
            return Profile.empty();
        }

        Set<Long> listingIds = audits.stream()
                .map(RealActionAudit::getBackendListingId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        Map<Long, Listing> listingsById = listingRepository
                .findAllById(listingIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        Listing::getId,
                        listing -> listing
                ));

        Map<Long, Map<Long, Map<LocalDate, Integer>>> counts =
                new HashMap<>();

        for (RealActionAudit audit : audits) {
            if (audit.getBotId() == null
                    || audit.getCreatedAt() == null
                    || audit.getBackendListingId() == null) {
                continue;
            }

            Listing listing = listingsById.get(audit.getBackendListingId());
            if (listing == null) {
                continue;
            }

            Long modelId = historyModelResolver
                    .resolveModelId(listing, models)
                    .orElse(null);

            if (modelId == null) {
                continue;
            }

            counts.computeIfAbsent(
                            modelId,
                            ignored -> new HashMap<>()
                    )
                    .computeIfAbsent(
                            audit.getBotId(),
                            ignored -> new HashMap<>()
                    )
                    .merge(
                            audit.getCreatedAt().toLocalDate(),
                            1,
                            Integer::sum
                    );
        }

        Map<Long, Map<Long, List<Integer>>> compact = new HashMap<>();

        for (Map.Entry<Long, Map<Long, Map<LocalDate, Integer>>> modelEntry
                : counts.entrySet()) {
            Map<Long, List<Integer>> botCounts = new HashMap<>();

            for (Map.Entry<Long, Map<LocalDate, Integer>> botEntry
                    : modelEntry.getValue().entrySet()) {
                List<Integer> dailyCounts = botEntry.getValue()
                        .values()
                        .stream()
                        .filter(value -> value != null && value > 0)
                        .sorted()
                        .toList();

                if (!dailyCounts.isEmpty()) {
                    botCounts.put(botEntry.getKey(), dailyCounts);
                }
            }

            if (!botCounts.isEmpty()) {
                compact.put(modelEntry.getKey(), Map.copyOf(botCounts));
            }
        }

        return new Profile(Map.copyOf(compact));
    }
    record Profile(
            Map<Long, Map<Long, List<Integer>>> dailyCountsByModelAndBot
    ) {
        static Profile empty() {
            return new Profile(Map.of());
        }

        int dailyCapacityFor(
                Long modelId,
                List<Long> botIds
        ) {
            if (modelId == null || botIds == null || botIds.isEmpty()) {
                return FALLBACK_DAILY_CONVERSATION_CAPACITY;
            }

            Map<Long, List<Integer>> byBot =
                    dailyCountsByModelAndBot.getOrDefault(
                            modelId,
                            Map.of()
                    );

            List<Integer> counts = new ArrayList<>();
            for (Long botId : botIds) {
                counts.addAll(byBot.getOrDefault(botId, List.of()));
            }

            if (counts.isEmpty()) {
                return FALLBACK_DAILY_CONVERSATION_CAPACITY;
            }

            List<Integer> sorted = counts.stream().sorted().toList();
            int index = Math.max(
                    0,
                    (int) Math.ceil(sorted.size() * 0.75d) - 1
            );
            int demonstrated = sorted.get(
                    Math.min(index, sorted.size() - 1)
            );

            return Math.min(
                    HARD_DAILY_OFFER_LIMIT,
                    Math.max(
                            FALLBACK_DAILY_CONVERSATION_CAPACITY,
                            demonstrated
                    )
            );
        }
    }


}
