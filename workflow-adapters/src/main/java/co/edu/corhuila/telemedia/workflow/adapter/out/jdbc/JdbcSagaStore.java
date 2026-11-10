package co.edu.corhuila.telemedia.workflow.adapter.out.jdbc;

import co.edu.corhuila.telemedia.workflow.application.port.out.DuplicateSagaException;
import co.edu.corhuila.telemedia.workflow.application.port.out.SagaStore;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Keeps the sagas in {@code workflow.saga_instance} (ADR-028), connected as {@code workflow_app}.
 * The schema belongs to the {@code db/} changelog: this store never creates or changes tables.
 * Instants are kept to the microsecond, the precision of {@code timestamptz}.
 */
public final class JdbcSagaStore implements SagaStore {

    private static final TypeReference<List<String>> STEPS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> DATA = new TypeReference<>() { };

    private static final String COLUMNS = "id, type, idempotency_key, request_hash, status, completed_steps,"
            + " compensated_steps, data, failed_step, failure_detail, failed_compensation,"
            + " compensation_failure_detail, created_at, updated_at";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcSagaStore(DataSource dataSource, ObjectMapper json) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
    }

    @Override
    public void insert(SagaInstance saga) {
        SagaInstance.Snapshot s = saga.snapshot();
        try {
            jdbc.sql("INSERT INTO workflow.saga_instance (" + COLUMNS + ") VALUES (:id, :type, :key, :hash, :status,"
                            + " CAST(:completed AS jsonb), CAST(:compensated AS jsonb), CAST(:data AS jsonb),"
                            + " :failedStep, :failureDetail, :failedCompensation, :compensationFailureDetail,"
                            + " :createdAt, :updatedAt)")
                    .param("id", s.id())
                    .param("type", s.type())
                    .param("key", s.idempotencyKey())
                    .param("hash", s.requestHash())
                    .params(stateParams(s))
                    .param("createdAt", timestamp(s.createdAt()))
                    .update();
        } catch (DuplicateKeyException twin) {
            throw new DuplicateSagaException(s.idempotencyKey());
        }
    }

    @Override
    public void update(SagaInstance saga) {
        SagaInstance.Snapshot s = saga.snapshot();
        int rows = jdbc.sql("UPDATE workflow.saga_instance SET status = :status,"
                        + " completed_steps = CAST(:completed AS jsonb), compensated_steps = CAST(:compensated AS jsonb),"
                        + " data = CAST(:data AS jsonb), failed_step = :failedStep, failure_detail = :failureDetail,"
                        + " failed_compensation = :failedCompensation,"
                        + " compensation_failure_detail = :compensationFailureDetail, updated_at = :updatedAt"
                        + " WHERE id = :id")
                .param("id", s.id())
                .params(stateParams(s))
                .update();
        if (rows == 0) {
            throw new IllegalStateException("saga " + s.id() + " was never inserted");
        }
    }

    @Override
    public Optional<SagaInstance> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workflow.saga_instance WHERE id = :id")
                .param("id", id)
                .query(this::row)
                .optional();
    }

    @Override
    public Optional<SagaInstance> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workflow.saga_instance WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(this::row)
                .optional();
    }

    @Override
    public List<SagaInstance> findRunningNotUpdatedSince(Instant since, int limit) {
        return List.of();
    }

    private Map<String, Object> stateParams(SagaInstance.Snapshot s) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", s.status().name());
        params.put("completed", write(s.completedSteps()));
        params.put("compensated", write(s.compensatedSteps()));
        params.put("data", write(s.data()));
        params.put("failedStep", s.failedStep());
        params.put("failureDetail", s.failureDetail());
        params.put("failedCompensation", s.failedCompensation());
        params.put("compensationFailureDetail", s.compensationFailureDetail());
        params.put("updatedAt", timestamp(s.updatedAt()));
        return params;
    }

    private SagaInstance row(ResultSet rs, int rowNumber) throws SQLException {
        return SagaInstance.restore(new SagaInstance.Snapshot(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                SagaStatus.valueOf(rs.getString("status")),
                read(rs.getString("completed_steps"), STEPS),
                read(rs.getString("compensated_steps"), STEPS),
                read(rs.getString("data"), DATA),
                rs.getString("failed_step"),
                rs.getString("failure_detail"),
                rs.getString("failed_compensation"),
                rs.getString("compensation_failure_detail"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("saga state is not serializable", impossible);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException corrupted) {
            throw new IllegalStateException("stored saga state is not valid JSON", corrupted);
        }
    }
}
