package pl.flipbot.maintenance;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Adopts the known working schema; unrelated/partial databases are never baselined. */
@Configuration(proxyBeanMethods = false)
public class DatabaseMigrations {
    private static final List<String> LEGACY_CORE = List.of(
            "bot", "bot_configuration", "listing", "negotiation_step", "dictionary_model");

    @Bean
    FlywayMigrationStrategy migrationStrategy() { return DatabaseMigrations::migrate; }

    public static void migrate(Flyway flyway) {
        if (flyway.info().current() == null) {
            JdbcTemplate jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
            String schema = flyway.getConfiguration().getDefaultSchema();
            if (schema == null) schema = jdbc.queryForObject("select current_schema()", String.class);
            int existing = 0;
            for (String table : LEGACY_CORE) {
                if (Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null",
                        Boolean.class, schema + "." + table))) existing++;
            }
            if (existing > 0) {
                if (existing != LEGACY_CORE.size()) {
                    throw new IllegalStateException("Incomplete FlipBot schema; refusing automatic baseline");
                }
                flyway.baseline();
            }
        }
        flyway.migrate();
    }
}
