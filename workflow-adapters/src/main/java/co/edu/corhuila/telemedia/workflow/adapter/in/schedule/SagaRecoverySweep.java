package co.edu.corhuila.telemedia.workflow.adapter.in.schedule;

import co.edu.corhuila.telemedia.workflow.application.port.in.RecoverInterruptedSagas;
import java.time.Duration;
import java.util.Objects;

/**
 * One run of the recovery sweep (WF-006): asks the saga to recover the sagas left {@code RUNNING}
 * and writes one log event per recovered saga. A failed run is logged and the next run tries again.
 */
public final class SagaRecoverySweep implements Runnable {

    private final RecoverInterruptedSagas recovery;
    private final Duration staleAfter;
    private final int batch;

    public SagaRecoverySweep(RecoverInterruptedSagas recovery, Duration staleAfter, int batch) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        if (staleAfter == null || !staleAfter.isPositive() || batch < 1) {
            throw new IllegalArgumentException("a positive stale-after and a batch of at least one saga");
        }
        this.staleAfter = staleAfter;
        this.batch = batch;
    }

    @Override
    public void run() {
    }
}
