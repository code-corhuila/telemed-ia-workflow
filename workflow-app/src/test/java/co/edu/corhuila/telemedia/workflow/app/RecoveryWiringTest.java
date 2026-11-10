package co.edu.corhuila.telemedia.workflow.app;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.telemedia.workflow.adapter.in.schedule.SagaRecoverySweep;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** WF-006: the recovery sweep is on by default and can be turned off. */
class RecoveryWiringTest {

    static void required(DynamicPropertyRegistry registry) {
        registry.add("JWT_PUBLIC_KEY", TestTokens::publicKeyPem);
        registry.add("WORKFLOW_CLIENT_SECRET", () -> "test-secret");
        registry.add("WORKFLOW_DB_PASSWORD", () -> "test-only");
        // The first run would reach the store, which these tests do not start.
        registry.add("WORKFLOW_RECOVERY_INITIAL_DELAY", () -> "PT1H");
    }

    @Nested
    @SpringBootTest
    class ByDefault {

        @Autowired
        private ApplicationContext context;

        @DynamicPropertySource
        static void settings(DynamicPropertyRegistry registry) {
            required(registry);
        }

        @Test
        void theSweepIsScheduled() {
            assertThat(context.getBeansOfType(SagaRecoverySweep.class)).hasSize(1);
        }
    }

    @Nested
    @SpringBootTest
    class TurnedOff {

        @Autowired
        private ApplicationContext context;

        @DynamicPropertySource
        static void settings(DynamicPropertyRegistry registry) {
            required(registry);
            registry.add("WORKFLOW_RECOVERY_ENABLED", () -> "false");
        }

        @Test
        void noSweepRuns() {
            assertThat(context.getBeansOfType(SagaRecoverySweep.class)).isEmpty();
        }
    }
}
