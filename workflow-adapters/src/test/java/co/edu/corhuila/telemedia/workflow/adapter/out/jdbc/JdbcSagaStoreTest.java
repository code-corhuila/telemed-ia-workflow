package co.edu.corhuila.telemedia.workflow.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.telemedia.workflow.application.port.out.DuplicateSagaException;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The store against a real PostgreSQL: the roles of infra-postgres, the changelog of {@code db/}
 * applied as {@code workflow_app}, and the store connected as {@code workflow_app} too, so it runs
 * with the grants it will have in the platform (ADR-028).
 */
@Testcontainers
class JdbcSagaStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-09T15:00:00Z");
    private static final Instant T1 = T0.plusSeconds(2);
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = "a".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("telemed_ia");

    private static JdbcSagaStore store;

    @BeforeAll
    static void createTheSchemaAsInThePlatform() throws Exception {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement sql = admin.createStatement()) {
            // Minimal replica of the workflow block of infra-postgres 01-instance.sh.
            sql.execute("CREATE ROLE workflow_app LOGIN PASSWORD '" + APP_PASSWORD + "'");
            sql.execute("CREATE ROLE workflow_reader NOLOGIN");
            sql.execute("CREATE ROLE workflow_writer NOLOGIN");
            sql.execute("GRANT workflow_reader, workflow_writer TO workflow_app WITH ADMIN OPTION");
            sql.execute("GRANT CREATE ON DATABASE telemed_ia TO workflow_app");
            sql.execute("CREATE SCHEMA workflow AUTHORIZATION workflow_app");
            sql.execute("ALTER ROLE workflow_app SET search_path = workflow");
        }
        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "workflow_app", APP_PASSWORD)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(app));
            database.setDatabaseChangeLogTableName("databasechangelog_workflow");
            database.setDatabaseChangeLogLockTableName("databasechangeloglock_workflow");
            Scope.child(Map.of(Scope.Attr.resourceAccessor.name(),
                    new DirectoryResourceAccessor(Path.of("..", "db").toAbsolutePath().normalize())), () ->
                    new CommandScope(UpdateCommandStep.COMMAND_NAME)
                            .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
                            .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "changelog/changelog-master.yaml")
                            .execute());
        }
        DriverManagerDataSource appDataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "workflow_app", APP_PASSWORD);
        store = new JdbcSagaStore(appDataSource, new ObjectMapper());
    }

    private static SagaInstance started(String key) {
        return SagaInstance.start(UUID.randomUUID(), "professional-onboarding", key, HASH, T0);
    }

    @Test
    void storesASagaAndFindsItByIdAndByKey() {
        SagaInstance saga = started("key-store");
        saga.stepCompleted("create-account", T1);
        saga.put("accountId", "7a1e9c3f-0000-4000-8000-000000000003");

        store.insert(saga);

        assertThat(store.findById(saga.id())).get()
                .extracting(SagaInstance::snapshot).isEqualTo(saga.snapshot());
        assertThat(store.findByIdempotencyKey("key-store")).get()
                .extracting(SagaInstance::snapshot).isEqualTo(saga.snapshot());
    }

    @Test
    void keepsEveryStepFailureAndCompensationAfterAnUpdate() {
        SagaInstance saga = started("key-update");
        store.insert(saga);
        saga.stepCompleted("create-account", T1);
        saga.stepFailed("create-profile", "ParticipantException: 409", T1);
        saga.compensationFailed("create-account", "ParticipantException: unreachable", T1);

        store.update(saga);

        SagaInstance stored = store.findById(saga.id()).orElseThrow();
        assertThat(stored.snapshot()).isEqualTo(saga.snapshot());
        assertThat(stored.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(stored.updatedAt()).isEqualTo(T1);
    }

    @Test
    void aCompensatedSagaComesBackCompensated() {
        SagaInstance saga = started("key-compensated");
        store.insert(saga);
        saga.stepCompleted("create-account", T1);
        saga.stepFailed("create-profile", "409", T1);
        saga.stepCompensated("create-account", T1);
        saga.compensated(T1);

        store.update(saga);

        SagaInstance stored = store.findByIdempotencyKey("key-compensated").orElseThrow();
        assertThat(stored.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(stored.compensatedSteps()).containsExactly("create-account");
    }

    @Test
    void aSecondSagaWithTheSameKeyIsADuplicate() {
        store.insert(started("key-twin"));

        assertThatThrownBy(() -> store.insert(started("key-twin"))).isInstanceOf(DuplicateSagaException.class);
    }

    @Test
    void updatingASagaThatWasNeverStoredFails() {
        assertThatThrownBy(() -> store.update(started("key-never"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownSagaIsNotFound() {
        assertThat(store.findById(UUID.randomUUID())).isEmpty();
        assertThat(store.findByIdempotencyKey("key-unknown")).isEmpty();
    }

    @Test
    void instantsAreKeptToTheMicrosecondPostgresStores() {
        Instant precise = T0.plusNanos(123_456_789);
        SagaInstance saga = SagaInstance.start(UUID.randomUUID(), "professional-onboarding", "key-precise", HASH,
                precise);

        store.insert(saga);

        assertThat(store.findById(saga.id()).orElseThrow().createdAt())
                .isEqualTo(precise.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void findsOnlyTheRunningSagasNotUpdatedSinceAnInstantOldestFirst() {
        Instant longAgo = T0.minus(30, ChronoUnit.DAYS);
        SagaInstance oldest = SagaInstance.start(UUID.randomUUID(), "professional-onboarding", "key-stale-1", HASH,
                longAgo);
        SagaInstance older = SagaInstance.start(UUID.randomUUID(), "professional-onboarding", "key-stale-2", HASH,
                longAgo.plusSeconds(60));
        SagaInstance recent = SagaInstance.start(UUID.randomUUID(), "professional-onboarding", "key-stale-3", HASH,
                longAgo.plusSeconds(600));
        SagaInstance finished = SagaInstance.start(UUID.randomUUID(), "professional-onboarding", "key-stale-4", HASH,
                longAgo);
        finished.complete(longAgo);
        for (SagaInstance saga : new SagaInstance[] {older, recent, finished, oldest}) {
            store.insert(saga);
        }
        Instant since = longAgo.plusSeconds(300);

        assertThat(store.findRunningNotUpdatedSince(since, 10))
                .extracting(SagaInstance::id).containsExactly(oldest.id(), older.id());
        assertThat(store.findRunningNotUpdatedSince(since, 1))
                .extracting(SagaInstance::id).containsExactly(oldest.id());
    }
}
