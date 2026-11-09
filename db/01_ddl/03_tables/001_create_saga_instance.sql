-- -------------------------------------------------------------------------
-- saga_instance: the state of one saga run (ADR-028, norm 5.8, Anexo E).
--
-- Saved after every step by the SagaStore of telemed-ia-workflow. The start
-- request is never stored, only its SHA-256 (request_hash), so the password
-- of the new professional is not either. The ids the participants returned
-- (accountId, professionalId) live in data and are external references to
-- other domains: no FK across schemas.
--
-- idempotency_key is unique: two concurrent starts with the same
-- Idempotency-Key cannot both be stored.
-- -------------------------------------------------------------------------

CREATE TABLE workflow.saga_instance (
    id                          uuid          NOT NULL,
    type                        varchar(60)   NOT NULL,
    idempotency_key             varchar(255)  NOT NULL,
    request_hash                char(64)      NOT NULL,
    status                      varchar(20)   NOT NULL DEFAULT 'RUNNING',
    completed_steps             jsonb         NOT NULL DEFAULT '[]'::jsonb,
    compensated_steps           jsonb         NOT NULL DEFAULT '[]'::jsonb,
    data                        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    failed_step                 varchar(60),
    failure_detail              text,
    failed_compensation         varchar(60),
    compensation_failure_detail text,
    created_at                  timestamptz   NOT NULL,
    updated_at                  timestamptz   NOT NULL,

    CONSTRAINT pk_saga_instance PRIMARY KEY (id),
    CONSTRAINT uq_saga_instance_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_saga_instance_status CHECK (status IN ('RUNNING', 'COMPLETED', 'COMPENSATED', 'FAILED')),
    CONSTRAINT ck_saga_instance_failed_step CHECK (status NOT IN ('COMPENSATED', 'FAILED') OR failed_step IS NOT NULL),
    CONSTRAINT ck_saga_instance_failed_compensation CHECK (status <> 'FAILED' OR failed_compensation IS NOT NULL),
    CONSTRAINT ck_saga_instance_completed_steps CHECK (jsonb_typeof(completed_steps) = 'array'),
    CONSTRAINT ck_saga_instance_compensated_steps CHECK (jsonb_typeof(compensated_steps) = 'array'),
    CONSTRAINT ck_saga_instance_data CHECK (jsonb_typeof(data) = 'object')
);
