-- =========================================================================
-- Schema tests for workflow.saga_instance (HU-05, ADR-028).
--
-- Run by tests/run-db-tests.sh as workflow_app after the migrations.
-- Everything runs inside one transaction that is rolled back at the end,
-- so no test data persists. Each block raises an exception on failure and
-- psql stops (ON_ERROR_STOP).
-- =========================================================================

BEGIN;

-- 1. Structure ------------------------------------------------------------

DO $$
BEGIN
    IF to_regclass('workflow.saga_instance') IS NULL THEN
        RAISE EXCEPTION 'table workflow.saga_instance does not exist';
    END IF;
END $$;

-- Exact column set: guards against storing the start request or the
-- password of the new professional (only the request hash is kept).
DO $$
DECLARE
    expected text := 'compensated_steps:jsonb:NO,'
                  || 'compensation_failure_detail:text:YES,'
                  || 'completed_steps:jsonb:NO,'
                  || 'created_at:timestamp with time zone:NO,'
                  || 'data:jsonb:NO,'
                  || 'failed_compensation:character varying:YES,'
                  || 'failed_step:character varying:YES,'
                  || 'failure_detail:text:YES,'
                  || 'id:uuid:NO,'
                  || 'idempotency_key:character varying:NO,'
                  || 'request_hash:character:NO,'
                  || 'status:character varying:NO,'
                  || 'type:character varying:NO,'
                  || 'updated_at:timestamp with time zone:NO';
    actual text;
BEGIN
    SELECT string_agg(column_name || ':' || data_type || ':' || is_nullable, ',' ORDER BY column_name)
      INTO actual
      FROM information_schema.columns
     WHERE table_schema = 'workflow'
       AND table_name = 'saga_instance';

    IF actual IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'unexpected columns. expected [%] got [%]', expected, actual;
    END IF;
END $$;

-- The column holds 255 characters, more than the 8 to 128 the API accepts (07-api/guidelines.md).
DO $$
DECLARE
    max_length integer;
BEGIN
    SELECT character_maximum_length INTO max_length
      FROM information_schema.columns
     WHERE table_schema = 'workflow' AND table_name = 'saga_instance' AND column_name = 'idempotency_key';

    IF max_length IS DISTINCT FROM 255 THEN
        RAISE EXCEPTION 'idempotency_key must hold 255 characters, got %', max_length;
    END IF;
END $$;

-- 2. Defaults -------------------------------------------------------------

DO $$
DECLARE
    row_found workflow.saga_instance%ROWTYPE;
BEGIN
    INSERT INTO workflow.saga_instance (id, type, idempotency_key, request_hash, created_at, updated_at)
    VALUES (gen_random_uuid(), 'professional-onboarding', 'defaults-key', repeat('a', 64), now(), now())
    RETURNING * INTO row_found;

    IF row_found.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'status default must be RUNNING, got %', row_found.status;
    END IF;
    IF row_found.completed_steps <> '[]'::jsonb OR row_found.compensated_steps <> '[]'::jsonb THEN
        RAISE EXCEPTION 'step lists must default to empty arrays';
    END IF;
    IF row_found.data <> '{}'::jsonb THEN
        RAISE EXCEPTION 'data must default to an empty object';
    END IF;
END $$;

-- 3. Rules ----------------------------------------------------------------

-- Two starts with the same Idempotency-Key cannot both be stored.
DO $$
BEGIN
    INSERT INTO workflow.saga_instance (id, type, idempotency_key, request_hash, created_at, updated_at)
    VALUES (gen_random_uuid(), 'professional-onboarding', 'twin-key', repeat('b', 64), now(), now());
    BEGIN
        INSERT INTO workflow.saga_instance (id, type, idempotency_key, request_hash, created_at, updated_at)
        VALUES (gen_random_uuid(), 'professional-onboarding', 'twin-key', repeat('b', 64), now(), now());
        RAISE EXCEPTION 'a second saga with the same idempotency_key was accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END $$;

DO $$
BEGIN
    BEGIN
        INSERT INTO workflow.saga_instance (id, type, idempotency_key, request_hash, status, created_at, updated_at)
        VALUES (gen_random_uuid(), 'professional-onboarding', 'bad-status', repeat('c', 64), 'DONE', now(), now());
        RAISE EXCEPTION 'an unknown status was accepted';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END $$;

-- A compensated saga names the step that failed.
DO $$
BEGIN
    BEGIN
        INSERT INTO workflow.saga_instance (id, type, idempotency_key, request_hash, status, created_at, updated_at)
        VALUES (gen_random_uuid(), 'professional-onboarding', 'no-failed-step', repeat('d', 64), 'COMPENSATED', now(), now());
        RAISE EXCEPTION 'a COMPENSATED saga without failed_step was accepted';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END $$;

-- A failed saga names the compensation that failed.
DO $$
BEGIN
    BEGIN
        INSERT INTO workflow.saga_instance (id, type, idempotency_key, request_hash, status, failed_step,
                                            created_at, updated_at)
        VALUES (gen_random_uuid(), 'professional-onboarding', 'no-failed-comp', repeat('e', 64), 'FAILED',
                'create-profile', now(), now());
        RAISE EXCEPTION 'a FAILED saga without failed_compensation was accepted';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END $$;

-- The step lists are JSON arrays and data is a JSON object.
DO $$
BEGIN
    BEGIN
        INSERT INTO workflow.saga_instance (id, type, idempotency_key, request_hash, completed_steps,
                                            created_at, updated_at)
        VALUES (gen_random_uuid(), 'professional-onboarding', 'steps-object', repeat('f', 64), '{}'::jsonb, now(), now());
        RAISE EXCEPTION 'completed_steps accepted a JSON object';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END $$;

-- 4. Privileges -----------------------------------------------------------

-- Saga history is an audit trail: no role may delete it.
DO $$
BEGIN
    IF NOT has_table_privilege('workflow_reader', 'workflow.saga_instance', 'SELECT') THEN
        RAISE EXCEPTION 'workflow_reader must read saga_instance';
    END IF;
    IF has_table_privilege('workflow_reader', 'workflow.saga_instance', 'INSERT') THEN
        RAISE EXCEPTION 'workflow_reader must not write saga_instance';
    END IF;
    IF NOT has_table_privilege('workflow_writer', 'workflow.saga_instance', 'INSERT, UPDATE') THEN
        RAISE EXCEPTION 'workflow_writer must insert and update saga_instance';
    END IF;
    IF has_table_privilege('workflow_writer', 'workflow.saga_instance', 'DELETE') THEN
        RAISE EXCEPTION 'workflow_writer must not delete saga_instance';
    END IF;
END $$;

-- 5. Recovery index (WF-006) ---------------------------------------------

DO $$
BEGIN
    IF to_regclass('workflow.ix_saga_instance_running') IS NULL THEN
        RAISE EXCEPTION 'index ix_saga_instance_running does not exist';
    END IF;
END $$;

ROLLBACK;
