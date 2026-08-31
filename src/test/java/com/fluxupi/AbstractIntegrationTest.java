package com.fluxupi;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real database.
 *
 * <p>These run against PostgreSQL in Docker, not H2. The reason is specific:
 * the guarantees this project claims — {@code SELECT ... FOR UPDATE} row locks,
 * a {@code UNIQUE} index resolving an idempotency race, and the deferred
 * constraint trigger that enforces ledger balance at commit — either behave
 * differently or do not exist at all on an in-memory database. Testing them
 * anywhere but Postgres would be testing a different system.
 *
 * <p>One container is shared by every integration test class. Testcontainers'
 * per-class {@code @Container} lifecycle would start and stop Postgres for each
 * one, which dominates the suite's runtime for no benefit.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("fluxupi_test")
                    .withUsername("fluxupi")
                    .withPassword("fluxupi_test")
                    .withReuse(false);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
