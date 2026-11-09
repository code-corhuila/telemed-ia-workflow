ALTER DEFAULT PRIVILEGES IN SCHEMA workflow
  REVOKE SELECT, INSERT, UPDATE ON TABLES FROM workflow_writer;

ALTER DEFAULT PRIVILEGES IN SCHEMA workflow
  REVOKE SELECT ON TABLES FROM workflow_reader;

REVOKE SELECT, INSERT, UPDATE
  ON workflow.saga_instance
  FROM workflow_writer;

REVOKE SELECT
  ON workflow.saga_instance
  FROM workflow_reader;
