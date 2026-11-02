package pl.flipbot.maintenance;

import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated schemas exercise both installation paths against actual PostgreSQL. */
@SpringBootTest
class MigrationUpgradeTest {
    @Autowired DataSource dataSource;

    @Test void emptyDatabaseGetsCompleteSchemaIncludingDurableNativeClaims() throws Exception {
        inSchema((jdbc, flyway, source) -> {
            DatabaseMigrations.migrate(flyway);
            assertEquals("103", flyway.info().current().getVersion().getVersion());
            assertTrue(jdbc.queryForObject("select to_regclass(current_schema() || '.marketplace_negotiation_claim') is not null", Boolean.class));
            assertTrue(jdbc.queryForObject("select to_regclass(current_schema() || '.daily_offer_quota_reservation') is not null", Boolean.class));
            assertEquals(0L, jdbc.queryForObject("select count(*) from listing", Long.class));
            DatabaseMigrations.migrate(flyway);
            assertEquals(4, flyway.info().applied().length);
            assertReadIndexes(jdbc);
        });
    }

    @Test void workingLegacyDatabaseIsUpgradedOnceWithoutLosingListingsQuotaOrSnapshots() throws Exception {
        inSchema((jdbc, flyway, source) -> {
            new ResourceDatabasePopulator(new ClassPathResource("db/schema/V100__initial_schema.sql")).execute(source);
            jdbc.execute("create table flyway_schema_history(version varchar(40))");
            jdbc.update("insert into flyway_schema_history(version) values ('legacy-history-preserved')");
            jdbc.update("insert into bot(id, market_stats_observer, name, status) values (1, false, 'fixture', 'STOPPED')");
            jdbc.update("insert into bot_configuration(id, bot_id, negotiation_strategy_version, marketplace) values (1, 1, 1, 'VINTED')");
            insertListing(jdbc, 12L, "NEGOTIATING");
            insertListing(jdbc, 13L, "PURCHASED");
            jdbc.update("update listing set negotiation_strategy_snapshot = '{\"version\":7}', negotiation_strategy_version = 7 where id = 12");
            UUID reservation = UUID.randomUUID();
            jdbc.update("insert into daily_offer_quota_reservation(request_id, bot_id, usage_date, active, created_at) values (?, 1, current_date, true, current_timestamp)", reservation);
            jdbc.execute("alter table listing drop column last_terminal_watch_at");
            jdbc.execute("alter table market_listing_observation drop column published_at");

            // The old erroneous recovery and marker were one transaction.
            Connection connection = source.getConnection();
            connection.setAutoCommit(false);
            insertListing(jdbc, 11L, "NEGOTIATING");
            jdbc.update("insert into flipbot_data_repair(repair_key) values ('legacy-negotiation-recovery-2026-09-19-v1')");
            connection.commit();
            connection.setAutoCommit(true);

            DatabaseMigrations.migrate(flyway);
            assertReadIndexes(jdbc);
            assertEquals("100", flyway.info().applied()[0].getVersion().getVersion());
            assertEquals("EXPIRED", jdbc.queryForObject("select status from listing where id = 11", String.class));
            assertEquals("NEGOTIATING", jdbc.queryForObject("select status from listing where id = 12", String.class));
            assertEquals("{\"version\":7}", jdbc.queryForObject("select negotiation_strategy_snapshot from listing where id = 12", String.class));
            assertEquals("PURCHASED", jdbc.queryForObject("select history_outcome from listing where id = 13", String.class));
            assertEquals(1L, jdbc.queryForObject("select count(*) from daily_offer_quota_reservation where request_id = ? and active", Long.class, reservation));
            assertEquals(3L, jdbc.queryForObject("select count(*) from listing", Long.class));
            assertEquals("legacy-history-preserved", jdbc.queryForObject("select version from flyway_schema_history", String.class));
            // After migration, genuinely reopened rows must not be corrected again.
            jdbc.update("update listing set status = 'NEGOTIATING' where id = 11");
            DatabaseMigrations.migrate(flyway);
            assertEquals("NEGOTIATING", jdbc.queryForObject("select status from listing where id = 11", String.class));
        });
    }

    @Test void partialSchemaIsRejectedBeforeBaseline() throws Exception {
        inSchema((jdbc, flyway, source) -> {
            jdbc.execute("create table bot(id bigint primary key)");
            assertThrows(IllegalStateException.class, () -> DatabaseMigrations.migrate(flyway));
            assertNull(jdbc.queryForObject("select to_regclass(current_schema() || '.flipbot_schema_history')", String.class));
        });
    }

    private void assertReadIndexes(JdbcTemplate jdbc) {
        for (String name : new String[]{"idx_real_action_audit_bot_created", "idx_real_action_audit_listing_action_outcome",
                "idx_listing_visible_history_date", "idx_market_observation_published"}) {
            assertTrue(jdbc.queryForObject("select exists(select 1 from pg_indexes where schemaname = current_schema() and indexname = ?)",
                    Boolean.class, name), name);
        }
    }

    private void insertListing(JdbcTemplate jdbc, long id, String status) {
        jdbc.update("""
                insert into listing(id, bot_id, listing_id, title, url, original_price, current_price,
                    current_step, awaiting_seller_response, history_hidden, offer_assessment, status)
                values (?, 1, ?, 'preserved fixture', 'https://example.test/item', 200, 100, 1, true, false, 'UNASSESSED', ?)
                """, id, "fixture-" + id, status);
    }

    private void inSchema(Scenario scenario) throws Exception {
        String schema = "migration_fixture_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection()) {
            String originalSchema = connection.getSchema();
            JdbcTemplate admin = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            admin.execute("create schema " + schema);
            try {
                connection.setSchema(schema);
                SingleConnectionDataSource source = new SingleConnectionDataSource(connection, true);
                Flyway flyway = Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                        .table("flipbot_schema_history").locations("classpath:db/schema")
                        .baselineVersion("100").cleanDisabled(true).load();
                scenario.run(new JdbcTemplate(source), flyway, source);
            } finally {
                connection.setAutoCommit(true);
                connection.setSchema(originalSchema);
                admin.execute("drop schema " + schema + " cascade");
            }
        }
    }

    @FunctionalInterface private interface Scenario {
        void run(JdbcTemplate jdbc, Flyway flyway, SingleConnectionDataSource source) throws Exception;
    }
}
