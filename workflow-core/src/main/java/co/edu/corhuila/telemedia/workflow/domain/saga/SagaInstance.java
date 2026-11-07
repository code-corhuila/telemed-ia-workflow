package co.edu.corhuila.telemedia.workflow.domain.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One run of a saga and everything needed to resume it after a restart: the steps done, the
 * steps undone, the failure and the values the steps produced (Anexo E, rule 2).
 *
 * <p>The failure details are internal: they are kept here and in the logs, never in a response.
 * The request itself is not kept, only its hash, so no secret it carries is ever stored.
 */
public final class SagaInstance {

    private final UUID id;
    private final String type;
    private final String idempotencyKey;
    private final String requestHash;
    private final Instant createdAt;
    private final List<String> completedSteps = new ArrayList<>();
    private final List<String> compensatedSteps = new ArrayList<>();
    private final Map<String, String> data = new LinkedHashMap<>();
    private SagaStatus status = SagaStatus.RUNNING;
    private Instant updatedAt;
    private String failedStep;
    private String failureDetail;
    private String failedCompensation;
    private String compensationFailureDetail;

    private SagaInstance(UUID id, String type, String idempotencyKey, String requestHash, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
    }

    public static SagaInstance start(UUID id, String type, String idempotencyKey, String requestHash, Instant now) {
        return new SagaInstance(id, type, idempotencyKey, requestHash, now);
    }

    public void stepCompleted(String step, Instant now) {
        requireRunningForward();
        if (completedSteps.contains(step)) {
            throw new IllegalStateException("Step " + step + " is already completed");
        }
        completedSteps.add(Objects.requireNonNull(step, "step"));
        updatedAt = now;
    }

    public void complete(Instant now) {
        requireRunningForward();
        status = SagaStatus.COMPLETED;
        updatedAt = now;
    }

    /** The step could not be done: from now on the saga only undoes what it did. */
    public void stepFailed(String step, String detail, Instant now) {
        requireRunningForward();
        failedStep = Objects.requireNonNull(step, "step");
        failureDetail = detail;
        updatedAt = now;
    }

    /** Only the most recent step not yet undone can be undone: compensations run in reverse order. */
    public void stepCompensated(String step, Instant now) {
        requireCompensating();
        List<String> pending = pendingCompensations();
        if (pending.isEmpty() || !pending.get(0).equals(step)) {
            throw new IllegalStateException("Step " + step + " is not the next one to undo");
        }
        compensatedSteps.add(step);
        updatedAt = now;
    }

    public void compensated(Instant now) {
        requireCompensating();
        if (!pendingCompensations().isEmpty()) {
            throw new IllegalStateException("Steps still to undo: " + pendingCompensations());
        }
        status = SagaStatus.COMPENSATED;
        updatedAt = now;
    }

    public void compensationFailed(String step, String detail, Instant now) {
        requireCompensating();
        failedCompensation = Objects.requireNonNull(step, "step");
        compensationFailureDetail = detail;
        status = SagaStatus.FAILED;
        updatedAt = now;
    }

    /** The completed steps not yet undone, most recent first: the order to undo them in. */
    public List<String> pendingCompensations() {
        List<String> pending = new ArrayList<>(completedSteps.reversed());
        pending.removeAll(compensatedSteps);
        return List.copyOf(pending);
    }

    public boolean isCompensating() {
        return status == SagaStatus.RUNNING && failedStep != null;
    }

    public void put(String key, String value) {
        data.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    private void requireRunningForward() {
        if (status != SagaStatus.RUNNING || failedStep != null) {
            throw new IllegalStateException("Saga " + id + " is not running its steps (" + status + ")");
        }
    }

    private void requireCompensating() {
        if (!isCompensating()) {
            throw new IllegalStateException("Saga " + id + " is not compensating (" + status + ")");
        }
    }

    public UUID id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public SagaStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public List<String> completedSteps() {
        return List.copyOf(completedSteps);
    }

    public List<String> compensatedSteps() {
        return List.copyOf(compensatedSteps);
    }

    public Map<String, String> data() {
        return Map.copyOf(data);
    }

    public String failedStep() {
        return failedStep;
    }

    public String failureDetail() {
        return failureDetail;
    }

    public String failedCompensation() {
        return failedCompensation;
    }

    public String compensationFailureDetail() {
        return compensationFailureDetail;
    }
}
