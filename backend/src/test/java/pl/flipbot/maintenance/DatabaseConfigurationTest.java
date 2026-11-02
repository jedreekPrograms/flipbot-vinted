package pl.flipbot.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseConfigurationTest {
    @Test
    void defaultsKeepTheWorkingBackendDatabase() throws IOException {
        MockEnvironment environment = environment();
        assertEquals("jdbc:postgresql://localhost:5433/flipbot_pr74", environment.getProperty("spring.datasource.url"));
        assertEquals("postgres", environment.getProperty("spring.datasource.username"));
        assertEquals("postgres", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void sharedDockerAndBackendVariablesCanSelectAnExistingDatabase() throws IOException {
        MockEnvironment environment = environment().withProperty("FLIPBOT_DB_NAME", "existing_db")
                .withProperty("FLIPBOT_DB_HOST", "database.example")
                .withProperty("FLIPBOT_DB_PORT", "5544")
                .withProperty("FLIPBOT_DB_USER", "existing_user")
                .withProperty("FLIPBOT_DB_PASSWORD", "existing_password");
        assertEquals("jdbc:postgresql://database.example:5544/existing_db", environment.getProperty("spring.datasource.url"));
        assertEquals("existing_user", environment.getProperty("spring.datasource.username"));
        assertEquals("existing_password", environment.getProperty("spring.datasource.password"));
    }

    private MockEnvironment environment() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
