package co.edu.corhuila.telemedia.workflow.adapter.in.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.telemedia.workflow.application.port.in.RecoverInterruptedSagas;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SagaRecoverySweepTest {

    private final FakeRecovery recovery = new FakeRecovery();

    @Test
    void eachRunRecoversTheSagasUnchangedForTheThresholdInBatches() {
        SagaRecoverySweep sweep = new SagaRecoverySweep(recovery, Duration.ofMinutes(5), 20);

        sweep.run();
        sweep.run();

        assertThat(recovery.calls).containsExactly("PT5M limit 20", "PT5M limit 20");
    }

    @Test
    void aFailedRunDoesNotStopTheNextOne() {
        recovery.failure = new IllegalStateException("workflow-db does not respond");
        SagaRecoverySweep sweep = new SagaRecoverySweep(recovery, Duration.ofMinutes(5), 20);

        assertThatCode(sweep::run).doesNotThrowAnyException();
        recovery.failure = null;
        sweep.run();

        assertThat(recovery.calls).hasSize(2);
    }

    @Test
    void aThresholdOrBatchThatCannotWorkIsRejectedAtStartup() {
        assertThatThrownBy(() -> new SagaRecoverySweep(recovery, Duration.ZERO, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SagaRecoverySweep(recovery, Duration.ofMinutes(5), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeRecovery implements RecoverInterruptedSagas {
        private final List<String> calls = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public List<SagaInstance> recover(Duration unchangedFor, int limit) {
            calls.add(unchangedFor + " limit " + limit);
            if (failure != null) {
                throw failure;
            }
            return List.of();
        }
    }
}
