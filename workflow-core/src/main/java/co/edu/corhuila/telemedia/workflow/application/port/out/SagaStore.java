package co.edu.corhuila.telemedia.workflow.application.port.out;

import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import java.util.Optional;
import java.util.UUID;

/** Where every saga is kept, so a restart knows what is left to do or to undo (Anexo E, rule 2). */
public interface SagaStore {

    /**
     * @throws DuplicateSagaException when a saga with the same Idempotency-Key is already stored
     */
    void insert(SagaInstance saga);

    void update(SagaInstance saga);

    Optional<SagaInstance> findById(UUID id);

    Optional<SagaInstance> findByIdempotencyKey(String idempotencyKey);
}
