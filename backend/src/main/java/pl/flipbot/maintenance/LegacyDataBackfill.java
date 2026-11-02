package pl.flipbot.maintenance;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.flipbot.analytics.HistoryModelResolver;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.listing.ListingStatus;
import pl.flipbot.negotiation.strategy.NegotiationStrategySnapshotService;

/** One transactional upgrade after Flyway and Hibernate validation, before readiness. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegacyDataBackfill implements ApplicationRunner {
    private static final String REPAIR_KEY = "schema-v100-history-and-strategy-snapshots";
    private final JdbcTemplate jdbc;
    private final HistoryModelResolver historyModels;
    private final ListingRepository listings;
    private final NegotiationStrategySnapshotService snapshots;

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        int claimed = jdbc.update("insert into flipbot_data_repair(repair_key) values (?) on conflict do nothing", REPAIR_KEY);
        if (claimed == 0) return;

        new LegacyHistoryLabels(jdbc, historyModels).backfill();
        List<Listing> active = listings.findByStatusInOrderByIdAsc(
                List.of(ListingStatus.NEGOTIATING, ListingStatus.ACTION_REQUIRED));
        int pinned = 0;
        for (Listing listing : active) if (snapshots.pinIfMissing(listing)) pinned++;
        if (pinned > 0) listings.saveAll(active);
        log.info("[SCHEMA UPGRADE] One-time history/strategy backfill completed; pinned {} active conversations", pinned);
    }
}
