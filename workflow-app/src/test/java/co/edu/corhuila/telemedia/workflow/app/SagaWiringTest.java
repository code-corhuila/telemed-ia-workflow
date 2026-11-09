package co.edu.corhuila.telemedia.workflow.app;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.telemedia.workflow.adapter.out.jdbc.JdbcSagaStore;
import co.edu.corhuila.telemedia.workflow.adapter.out.participants.HttpIdentityAccounts;
import co.edu.corhuila.telemedia.workflow.adapter.out.participants.HttpProfessionalProfiles;
import co.edu.corhuila.telemedia.workflow.application.port.in.GetSaga;
import co.edu.corhuila.telemedia.workflow.application.port.in.StartProfessionalOnboarding;
import co.edu.corhuila.telemedia.workflow.application.port.out.IdentityAccounts;
import co.edu.corhuila.telemedia.workflow.application.port.out.ProfessionalProfiles;
import co.edu.corhuila.telemedia.workflow.application.port.out.SagaStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The composition root wires the onboarding saga to its HTTP participants and its store. */
@SpringBootTest
class SagaWiringTest {

    @Autowired
    private StartProfessionalOnboarding start;

    @Autowired
    private GetSaga get;

    @Autowired
    private IdentityAccounts identity;

    @Autowired
    private ProfessionalProfiles profiles;

    @Autowired
    private SagaStore store;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("JWT_PUBLIC_KEY", TestTokens::publicKeyPem);
        registry.add("WORKFLOW_CLIENT_SECRET", () -> "test-secret");
        // The pool connects on first use; these tests never reach the store.
        registry.add("WORKFLOW_DB_PASSWORD", () -> "test-only");
    }

    @Test
    void wiresTheSagaToItsParticipantsAndItsStore() {
        assertThat(start).isSameAs(get);
        assertThat(identity).isInstanceOf(HttpIdentityAccounts.class);
        assertThat(profiles).isInstanceOf(HttpProfessionalProfiles.class);
        // ADR-028: the saga state survives a restart in the workflow schema of PostgreSQL.
        assertThat(store).isInstanceOf(JdbcSagaStore.class);
    }
}
