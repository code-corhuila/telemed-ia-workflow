package co.edu.corhuila.telemedia.workflow.adapter.out.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.telemedia.workflow.application.port.out.DuplicateSagaException;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemorySagaStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-08T15:00:00Z");

    private final InMemorySagaStore store = new InMemorySagaStore();

    @Test
    void findsAStoredSagaByIdAndByIdempotencyKey() {
        SagaInstance saga = saga("key-1");

        store.insert(saga);

        assertThat(store.findById(saga.id())).containsSame(saga);
        assertThat(store.findByIdempotencyKey("key-1")).containsSame(saga);
        assertThat(store.findById(UUID.randomUUID())).isEmpty();
        assertThat(store.findByIdempotencyKey("key-2")).isEmpty();
    }

    @Test
    void refusesASecondSagaWithTheSameIdempotencyKey() {
        store.insert(saga("key-1"));
        SagaInstance twin = saga("key-1");

        assertThatThrownBy(() -> store.insert(twin)).isInstanceOf(DuplicateSagaException.class);
        assertThat(store.findById(twin.id())).isEmpty();
    }

    @Test
    void anUpdateReplacesTheStoredSaga() {
        SagaInstance saga = saga("key-1");
        store.insert(saga);

        store.update(saga);

        assertThat(store.findById(saga.id())).containsSame(saga);
    }

    @Test
    void refusesToUpdateASagaThatWasNeverInserted() {
        assertThatThrownBy(() -> store.update(saga("key-1"))).isInstanceOf(IllegalStateException.class);
    }

    private static SagaInstance saga(String idempotencyKey) {
        return SagaInstance.start(UUID.randomUUID(), "professional-onboarding", idempotencyKey, "hash", NOW);
    }
}
