package co.edu.corhuila.telemedia.workflow.adapter.out.memory;

import co.edu.corhuila.telemedia.workflow.application.port.out.DuplicateSagaException;
import co.edu.corhuila.telemedia.workflow.application.port.out.SagaStore;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY store: keeps the sagas in memory, so a restart loses them and Anexo E rule 2 (the state
 * survives a restart) is not met yet. It lets the saga run end to end until the PostgreSQL store of
 * the saga-store ADR replaces it; it must not reach qa or main as the only store.
 */
public final class InMemorySagaStore implements SagaStore {

    private final Map<UUID, SagaInstance> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> idByKey = new ConcurrentHashMap<>();

    @Override
    public void insert(SagaInstance saga) {
        // The key is claimed first, so two concurrent twins cannot both be stored.
        if (idByKey.putIfAbsent(saga.idempotencyKey(), saga.id()) != null) {
            throw new DuplicateSagaException(saga.idempotencyKey());
        }
        byId.put(saga.id(), saga);
    }

    @Override
    public void update(SagaInstance saga) {
        if (byId.replace(saga.id(), saga) == null) {
            throw new IllegalStateException("saga " + saga.id() + " was never inserted");
        }
    }

    @Override
    public Optional<SagaInstance> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<SagaInstance> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(idByKey.get(idempotencyKey)).map(byId::get);
    }
}
