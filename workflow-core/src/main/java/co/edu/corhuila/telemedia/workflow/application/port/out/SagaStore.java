package co.edu.corhuila.telemedia.workflow.application.port.out;

import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;

/** Where every saga is kept, so a restart knows what is left to do or to undo (Anexo E, rule 2). */
public interface SagaStore {

    void insert(SagaInstance saga);

    void update(SagaInstance saga);
}
