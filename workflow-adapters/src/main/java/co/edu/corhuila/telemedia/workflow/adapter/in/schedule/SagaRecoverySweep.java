package co.edu.corhuila.telemedia.workflow.adapter.in.schedule;

import co.edu.corhuila.telemedia.workflow.application.port.in.RecoverInterruptedSagas;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run of the recovery sweep (WF-006): asks the saga to recover the sagas left {@code RUNNING}
 * and writes one log event per recovered saga. A failed run is logged and the next run tries again.
 */
public final class SagaRecoverySweep implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(SagaRecoverySweep.class);

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
        List<SagaInstance> recovered;
        try {
            recovered = recovery.recover(staleAfter, batch);
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "saga.recovery_failed")
                    .log("saga recovery sweep failed: {}", failure.toString());
            return;
        }
        for (SagaInstance saga : recovered) {
            LOG.atInfo().addKeyValue("event", "saga.recovered")
                    .addKeyValue("saga_id", saga.id())
                    .addKeyValue("status", saga.status())
                    .addKeyValue("failed_step", saga.failedStep())
                    .log("saga {} recovered as {}", saga.id(), saga.status());
        }
    }
}
