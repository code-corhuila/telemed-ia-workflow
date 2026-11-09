package co.edu.corhuila.telemedia.workflow.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SagaInstanceTest {

    private static final Instant T0 = Instant.parse("2026-11-07T15:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);

    private static SagaInstance started() {
        return SagaInstance.start(UUID.fromString("35920dc9-bd55-41a0-8e41-5832377383e5"),
                "professional-onboarding", "key-1", "hash-1", T0);
    }

    @Test
    void startsRunningWithNothingDone() {
        SagaInstance saga = started();

        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.completedSteps()).isEmpty();
        assertThat(saga.failedStep()).isNull();
        assertThat(saga.isCompensating()).isFalse();
        assertThat(saga.idempotencyKey()).isEqualTo("key-1");
        assertThat(saga.requestHash()).isEqualTo("hash-1");
        assertThat(saga.createdAt()).isEqualTo(T0);
    }

    @Test
    void completesAfterItsStepsInOrder() {
        SagaInstance saga = started();

        saga.stepCompleted("create-account", T1);
        saga.stepCompleted("create-profile", T1);
        saga.complete(T1);

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.completedSteps()).containsExactly("create-account", "create-profile");
        assertThat(saga.updatedAt()).isEqualTo(T1);
    }

    @Test
    void aFailedStepStartsTheCompensationOfTheCompletedStepsInReverseOrder() {
        SagaInstance saga = started();
        saga.stepCompleted("create-account", T1);
        saga.stepCompleted("create-profile", T1);

        saga.stepFailed("activate-account", "identity answered 503", T1);

        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.isCompensating()).isTrue();
        assertThat(saga.failedStep()).isEqualTo("activate-account");
        assertThat(saga.failureDetail()).isEqualTo("identity answered 503");
        assertThat(saga.pendingCompensations()).containsExactly("create-profile", "create-account");
    }

    @Test
    void endsCompensatedOnlyOnceEveryCompletedStepWasUndone() {
        SagaInstance saga = started();
        saga.stepCompleted("create-account", T1);
        saga.stepCompleted("create-profile", T1);
        saga.stepFailed("activate-account", "timeout", T1);

        saga.stepCompensated("create-profile", T1);
        assertThatThrownBy(() -> saga.compensated(T1)).isInstanceOf(IllegalStateException.class);

        saga.stepCompensated("create-account", T1);
        saga.compensated(T1);

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.compensatedSteps()).containsExactly("create-profile", "create-account");
        assertThat(saga.pendingCompensations()).isEmpty();
    }

    @Test
    void aFailureInTheFirstStepLeavesNothingToUndo() {
        SagaInstance saga = started();

        saga.stepFailed("create-account", "409", T1);
        saga.compensated(T1);

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.failedStep()).isEqualTo("create-account");
    }

    @Test
    void undoesTheStepsOnlyInReverseOrder() {
        SagaInstance saga = started();
        saga.stepCompleted("create-account", T1);
        saga.stepCompleted("create-profile", T1);
        saga.stepFailed("activate-account", "timeout", T1);

        assertThatThrownBy(() -> saga.stepCompensated("create-account", T1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> saga.stepCompensated("activate-account", T1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedCompensationLeavesTheSagaFailedForAPersonToDecide() {
        SagaInstance saga = started();
        saga.stepCompleted("create-account", T1);
        saga.stepFailed("create-profile", "409", T1);

        saga.compensationFailed("create-account", "identity unreachable", T1);

        assertThat(saga.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(saga.failedStep()).isEqualTo("create-profile");
        assertThat(saga.failedCompensation()).isEqualTo("create-account");
        assertThat(saga.compensationFailureDetail()).isEqualTo("identity unreachable");
        assertThat(saga.pendingCompensations()).containsExactly("create-account");
    }

    @Test
    void aFinishedSagaAcceptsNoMoreChanges() {
        SagaInstance saga = started();
        saga.stepCompleted("create-account", T1);
        saga.complete(T1);

        assertThatThrownBy(() -> saga.stepCompleted("create-profile", T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> saga.stepFailed("create-profile", "x", T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> saga.complete(T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doesNotRunStepsWhileCompensating() {
        SagaInstance saga = started();
        saga.stepFailed("create-account", "409", T1);

        assertThatThrownBy(() -> saga.stepCompleted("create-profile", T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> saga.complete(T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doesNotCompleteTheSameStepTwice() {
        SagaInstance saga = started();
        saga.stepCompleted("create-account", T1);

        assertThatThrownBy(() -> saga.stepCompleted("create-account", T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keepsTheValuesAStepProducedForTheNextSteps() {
        SagaInstance saga = started();

        saga.put("accountId", "7a1e9c3f-0000-4000-8000-000000000003");

        assertThat(saga.get("accountId")).contains("7a1e9c3f-0000-4000-8000-000000000003");
        assertThat(saga.get("professionalId")).isEmpty();
        assertThat(saga.data()).containsOnlyKeys("accountId");
    }

    @Test
    void aSnapshotRestoresTheSameSaga() {
        SagaInstance saga = started();
        saga.stepCompleted("create-account", T1);
        saga.put("accountId", "7a1e9c3f-0000-4000-8000-000000000003");
        saga.stepFailed("create-profile", "409", T1);

        SagaInstance restored = SagaInstance.restore(saga.snapshot());

        assertThat(restored.snapshot()).isEqualTo(saga.snapshot());
        assertThat(restored.isCompensating()).isTrue();
        assertThat(restored.pendingCompensations()).containsExactly("create-account");
    }

    @Test
    void aRestoredSagaGoesOnFromWhereItWasStored() {
        SagaInstance saga = started();
        saga.stepCompleted("create-account", T1);
        saga.stepFailed("create-profile", "409", T1);

        SagaInstance restored = SagaInstance.restore(saga.snapshot());
        restored.stepCompensated("create-account", T1);
        restored.compensated(T1);

        assertThat(restored.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(restored.compensatedSteps()).containsExactly("create-account");
    }

    @Test
    void doesNotRestoreAnUndoneStepThatWasNeverDone() {
        SagaInstance saga = started();
        saga.stepCompleted("create-account", T1);
        SagaInstance.Snapshot stored = saga.snapshot();
        SagaInstance.Snapshot broken = new SagaInstance.Snapshot(stored.id(), stored.type(), stored.idempotencyKey(),
                stored.requestHash(), SagaStatus.RUNNING, stored.completedSteps(), java.util.List.of("create-profile"),
                stored.data(), "create-profile", "409", null, null, stored.createdAt(), stored.updatedAt());

        assertThatThrownBy(() -> SagaInstance.restore(broken)).isInstanceOf(IllegalArgumentException.class);
    }
}
