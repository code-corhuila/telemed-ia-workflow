package co.edu.corhuila.telemedia.workflow.application.port.in;

import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import java.time.Duration;
import java.util.List;

/**
 * The recovery of the sagas a restart left {@code RUNNING} (WF-006): they are never resumed, only
 * undone, or marked completed when every step was already done.
 */
public interface RecoverInterruptedSagas {

    /**
     * Recovers at most {@code limit} sagas that have been {@code RUNNING} without a change for longer
     * than {@code unchangedFor}, oldest first.
     *
     * @return the recovered sagas in their final state
     */
    List<SagaInstance> recover(Duration unchangedFor, int limit);
}
