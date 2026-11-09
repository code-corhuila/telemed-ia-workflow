-- Sagas left RUNNING after a restart are compensated (WF-006); this keeps
-- finding them cheap however many finished sagas the table holds.
CREATE INDEX ix_saga_instance_running
  ON workflow.saga_instance (updated_at)
  WHERE status = 'RUNNING';
