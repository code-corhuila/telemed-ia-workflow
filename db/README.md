# Workflow schema (`db/`)

Migrations of the saga state of `telemed-ia-workflow` (ADR-028, WF-003): one table,
`workflow.saga_instance`, in the shared PostgreSQL instance of `telemed-ia-infra-postgres`.
The folder follows the layout of Anexo A, keeping only the folders it uses.

- **Owner of the instance, the schema and the roles:** `telemed-ia-infra-postgres`
  (`postgres/init/01-instance.sh` creates `workflow_app`, `workflow_reader`, `workflow_writer`
  and the schema `workflow`).
- **Who migrates:** the one-shot Liquibase runner of `deploy/compose.yml`, included by the
  infra-postgres `compose.yml`, connected as `workflow_app`. Control tables
  `databasechangelog_workflow` and `databasechangeloglock_workflow` (ADR-026). **The workflow
  service never runs migrations.**
- **Rules:** every change is a new changeset in its folder and `changelog/expected-count.txt` is
  raised with it; an applied changeset is never edited. Each changeset has its rollback in
  `05_rollbacks/`. No foreign keys to other schemas; the start request and the password are never
  stored; no role may `DELETE`.

## Test

Requires Docker. Rebuilds the schema on a disposable PostgreSQL, checks a second update applies
nothing, rolls every changeset back, rebuilds again and runs `tests/sql/*.sql`:

```bash
bash db/tests/run-db-tests.sh
```

## Apply

From `telemed-ia-infra-postgres`, after `WORKFLOW_APP_PASSWORD` is set in its env file:

```bash
docker compose --env-file env/dev.env --profile tooling run --rm workflow-db-migrate
```
