package co.edu.corhuila.telemedia.workflow.application.port.in;

import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import java.util.UUID;

public interface GetSaga {

    /**
     * @throws co.edu.corhuila.telemedia.workflow.domain.saga.UnknownSagaException when no saga has that id
     */
    SagaInstance get(UUID id);
}
