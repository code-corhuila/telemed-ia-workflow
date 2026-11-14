package co.edu.corhuila.telemedia.workflow.app;

import co.edu.corhuila.telemedia.workflow.adapter.in.schedule.SagaRecoverySweep;
import co.edu.corhuila.telemedia.workflow.application.port.in.RecoverInterruptedSagas;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;

/**
 * WF-006: the sagas a restart left {@code RUNNING} are recovered by a sweep that runs every
 * {@code workflow.recovery.interval}, counted from the end of the previous run.
 * {@code WORKFLOW_RECOVERY_ENABLED=false} turns it off.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "workflow.recovery.enabled", havingValue = "true", matchIfMissing = true)
class RecoveryConfiguration {

    @Bean
    SagaRecoverySweep sagaRecoverySweep(RecoverInterruptedSagas recovery,
                                        @Value("${workflow.recovery.stale-after}") Duration staleAfter,
                                        @Value("${workflow.recovery.batch}") int batch) {
        return new SagaRecoverySweep(recovery, staleAfter, batch);
    }

    @Bean
    SchedulingConfigurer sagaRecoverySchedule(SagaRecoverySweep sweep,
                                              @Value("${workflow.recovery.interval}") Duration interval,
                                              @Value("${workflow.recovery.initial-delay}") Duration initialDelay) {
        return registrar -> registrar.addFixedDelayTask(new FixedDelayTask(sweep, interval, initialDelay));
    }
}
