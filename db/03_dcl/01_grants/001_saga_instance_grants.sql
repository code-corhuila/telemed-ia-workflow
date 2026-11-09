-- -------------------------------------------------------------------------
-- Privileges for the workflow schema.
--
-- workflow_reader and workflow_writer are created by
-- telemed-ia-infra-postgres (postgres/init/01-instance.sh); workflow_app is
-- a member of both. Saga history is an audit trail of what was done and
-- undone in other domains, so no role receives DELETE.
-- -------------------------------------------------------------------------

GRANT SELECT
  ON workflow.saga_instance
  TO workflow_reader;

GRANT SELECT, INSERT, UPDATE
  ON workflow.saga_instance
  TO workflow_writer;

ALTER DEFAULT PRIVILEGES IN SCHEMA workflow
  GRANT SELECT ON TABLES TO workflow_reader;

ALTER DEFAULT PRIVILEGES IN SCHEMA workflow
  GRANT SELECT, INSERT, UPDATE ON TABLES TO workflow_writer;
