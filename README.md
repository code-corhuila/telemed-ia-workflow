# telemed-ia-workflow

> Saga orchestration across TeleMed IA domains (course norm 5.8, Anexo E)

Part of the **TeleMed IA** distributed system.
Governance and documentation live in [`telemed-ia-docs`](https://github.com/code-corhuila/telemed-ia-docs).

## What this service does

A database transaction cannot span two domains: each service owns its own data. When one
business process has to write in more than one domain, this service runs it as a **saga**:

1. it calls each participant in order;
2. it stores the saga state after every step, so a restart knows what is left to do or undo;
3. when a step fails, it runs the compensation of every completed step, in reverse order;
4. when a compensation fails too, the saga ends `FAILED` and a person must decide.

It owns no business data: domain state and rules stay in each domain's `-api`.

### Planned saga: professional onboarding

| Step | Participant | Action | Compensation |
|---|---|---|---|
| 1 | identity-and-access | create the professional account, inactive | deactivate the account |
| 2 | professional-management | create the professional profile for that account | deactivate the profile |
| 3 | identity-and-access | activate the account | — (last step) |

The account can only sign in once every step succeeded.

## Structure

| Module | Responsibility |
|---|---|
| `workflow-core` | saga domain, ports and the orchestrator use case; plain Java, no framework |
| `workflow-adapters` | HTTP entry point, participant HTTP clients and the saga store |
| `workflow-app` | composition root (Spring Boot) |

## Run locally

Requires Java 21 and Maven 3.9.

```bash
mvn verify                                  # build and run every test
JWT_PUBLIC_KEY="$(cat identity-public.pem)" WORKFLOW_CLIENT_SECRET=<your-secret> \
  mvn -pl workflow-app -am spring-boot:run  # start on PORT (default 8080)
curl http://localhost:8080/health           # {"status":"UP"}
```

Configuration comes from environment variables; `.env.example` lists the names, never the values.
The service does not start without `JWT_PUBLIC_KEY`, the identity service's RSA public key: every
route but `/health` requires an RS256 access token signed by identity. It does not start without
`WORKFLOW_CLIENT_SECRET` either: the saga calls identity and professional-management with the
workflow's own service token, never with the caller's token.

**Temporary:** the sagas are kept in memory, so a restart loses every saga in progress (Anexo E,
rule 2 is not met yet). The PostgreSQL store, decided in the saga-store ADR, replaces it.

## Branching

Three permanent branches. **None of them accepts a direct commit**: you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/git-conventions.md` in `telemed-ia-docs`.
