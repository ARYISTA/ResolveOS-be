---
name: resolvos-backend
description: Build the ResolvOS Spring Boot backend (Java 21, PostgreSQL + pgvector, REST, native WebSocket, gRPC contract) that serves the existing ResolvOS frontend and a future Python LangGraph engine. Use for any task that creates or changes backend code, migrations, API contracts, tests, or local infrastructure in this repo. Enforces the fixed stack, the Ponytail coding principles, the trust model, and frontend contract compatibility.
---

# ResolvOS Backend: Agent Skill

You are a senior backend engineer and mentor building the ResolvOS backend from scratch, inside the same workspace as the finished frontend. Read this file and `docs/backend-prd.md` before writing any code. Follow both exactly. Do not redesign the architecture, change the stack, or add scope.

## 1. What You Are Building

The system of record for an enterprise multi-agent refund and dispute resolution demo. It owns tenants, auth, customers, transactions, policy PDFs and their RAG index (pgvector), cases, the deterministic Policy Enforcement Point (PEP), human review, refund execution, audit and metrics. It serves the Next.js frontend over REST and native WebSocket, and defines a gRPC contract for a Python LangGraph engine that does **not exist yet**.

Trust model (never blur it):

```
LLM -> recommendation | LangGraph -> workflow | Spring PEP -> ALLOW/DENY/ESCALATE
Spring RefundService -> the only code that moves money | PaymentGateway (mock)
```

If this file and the PRD conflict, the PRD wins on behavior and contract, this file wins on method and conventions.

## 2. Read the Frontend First (mandatory)

The frontend is the contract. Before each phase, open and read the relevant files in the frontend folder (find it; do not assume its name):

- `lib/schemas` (Zod): field names, enums, WebSocket event shapes. Match them exactly.
- `lib/domains/*`: sample companies, customers, transactions, presets, thresholds. Seed data and tenant slugs come from here so ids match.
- `lib/mock/mockEngine.ts`: the event order and payloads Live mode must reproduce.
- `lib/api/client.ts`, `ws.ts`: how the frontend calls the backend (headers, WebSocket URL, token handling).
- Never edit frontend files. If the backend needs a frontend change, list it in your report (PRD Section 12 already lists the known additions).
- Never rename a field the frontend already uses. Add, do not rename.

## 3. Ponytail Core Coding Principles (non-negotiable)

```
# Ponytail Core Coding Principles
- YAGNI: You Ain't Gonna Need It. Build only what is required right now.
- Zero Dependencies: Prioritize native APIs and standard libraries first.
- Deletion over Addition: The cleanest code is code that was never written.
- Simplicity > Cleverness: Write code that is clean, transparent, and easy to audit.
```

### How to apply them here

**YAGNI**
- Build only the endpoint, table, or class the current phase needs. No speculative interfaces, no config flags nobody reads, no "extensible" hierarchies.
- An interface exists only if it has two implementations now or the PRD requires it (`EngineGateway`, `PaymentGateway`, `EmbeddingClient`, `StorageService`). Everything else is a plain class.
- No features from the PRD's out-of-scope list. No Stripe or Razorpay adapters, no MinIO, no Redis, no Kafka.
- Do not build later phases while doing the current one.

**Zero Dependencies**
- The allowlist below is closed. Before proposing a new library: (1) does the JDK or Spring already do it? (2) is it under 30 lines? Only if both answers are no, ask the user first.
- Defaults:

| Need | Use | Not |
|---|---|---|
| DTOs | Java `record` | Lombok, MapStruct |
| Mapping | hand-written `static from(...)` methods | mapper frameworks |
| JWT | Nimbus encoder/decoder bundled with Spring Security | jjwt, java-jwt |
| HTTP to embedding provider | Spring `RestClient` | vendor SDKs, WebClient, OkHttp |
| Vector search | native SQL with pgvector operators | pgvector-java, Hibernate vector add-ons |
| Realtime | plain `TextWebSocketHandler` | STOMP, SockJS |
| JSON | Boot's Jackson | Gson, extra JSON libs |
| Tokens approximation | whitespace split | tokenizer libraries |
| Utilities | JDK collections, `Objects`, `Optional`, streams | Guava, commons-lang, commons-io |
| Scheduling | `@Scheduled` | Quartz |
| Retry | a 10-line loop with attempt cap | resilience libraries |
| Logging | Boot structured JSON logging | extra logging libs |

**Deletion over Addition**
- Prefer removing or simplifying code over adding code. Delete dead code, unused imports, unused beans, unused properties, and commented-out code in the same change that obsoletes them.
- No pass-through service layers, no empty base classes, no `Util` dumping grounds, no DTO that duplicates another DTO.
- The simulated engine (`engine/sim`) is temporary by design: structure it so deleting the package is the only step needed when the Python engine lands.

**Simplicity over Cleverness**
- Plain methods, early returns, small classes. No reflection tricks, no custom annotations unless the PRD calls for one, no AOP beyond the tenant transaction manager and method security.
- A junior engineer must be able to audit any class in two minutes.
- Comments explain *why*, never *what*.

## 4. Fixed Tech Stack (do not substitute)

Java 21 (virtual threads on), Maven, Spring Boot 4.1.x (latest 4.1 patch; verify exact starter artifact names against the Spring Boot 4.1 reference before adding them), PostgreSQL 16+ with pgvector, Docker Compose for local PostgreSQL.

**Dependency allowlist (everything else needs user approval):**

- Spring Boot starters: web MVC, websocket, data-jpa, security, oauth2-resource-server (for the bundled Nimbus JWT support), validation, flyway (plus the PostgreSQL Flyway module), actuator, grpc-server, grpc-client
- Micrometer tracing OpenTelemetry bridge and OTLP exporter
- PostgreSQL JDBC driver
- Apache PDFBox
- protobuf build plugin and gRPC codegen (build time only)
- Test: Spring Boot test starters, Spring Security test, Testcontainers (PostgreSQL, JUnit Jupiter), Mockito (via Boot)

Not used, ever, without approval: Lombok, MapStruct, Redis, Kafka, RabbitMQ, Keycloak, GraphQL, STOMP, H2, any LLM or embedding SDK, any AWS SDK.

## 5. Architecture Rules

Layout is in PRD Section 3. Package by feature. Hard rules:

1. **Layers:** controller to service to repository. Controllers never use repositories. Entities never leave the service layer; controllers return records.
2. **Money:** `BigDecimal` and `numeric(14,2)` only. Never `double` or `float`. JSON as numbers in INR.
3. **Time:** `Instant` and `timestamptz`, UTC. The frontend formats to IST.
4. **Schema:** Flyway SQL migrations are the only way the schema changes. Hibernate `ddl-auto=validate`. Never edit an applied migration; add a new one.
5. **Tenancy:** every tenant-owned table has `tenant_id` and an RLS policy. The app connects as `resolvos_app` (no `BYPASSRLS`, owns nothing). `TenantAwareTransactionManager` sets `app.tenant_id` per transaction. Non-request threads (orchestrator, outbox poller, engine handler) set `TenantContext` explicitly from the row they process. Never rely on `WHERE tenant_id = ?` alone. Documented non-RLS tables: `tenants`, `users`, `outbox_events`, `benchmark_results`.
6. **Final authority:** only `RefundService` moves money, and it independently re-checks for a stored `ALLOW` or an approved human review before executing. The PEP is pure functions with one unit test per rule.
7. **Engine boundary:** only the `engine` package knows about gRPC. Everything else talks to the `EngineGateway` interface. Spring-owned services (`CaseValidationService`, `PolicyEnforcementPoint`, `PolicyRetrievalService`, `RefundService`) must work without any engine and write their own audit events inside the service.
8. **gRPC is contract only.** Write the `.proto` files under `/proto/resolvos/engine/v1/`, generate stubs, and add the adapter classes **empty** (`GrpcEngineGateway` throws `UnsupportedOperationException`; `BackendCallbackGrpcService` returns `UNIMPLEMENTED`). Do not implement a Python engine. This is the only place empty code is allowed.
9. **Profiles:** `dev` (local), `sim` (simulated engine plus fake embeddings), `demo` (seed, reset, demo-login), `grpc` (empty adapters). `demo-login` must not be registered outside profile `demo`.
10. **WebSocket:** plain WebSocket, flat JSON with a `type`, per-case `seq`, broadcast only after commit, auth via `?access_token=`, redact query strings in logs. Slow clients are dropped; they never block the pipeline.
11. **Outbox:** engine dispatch (`RESOLVE_REQUESTED`, `RESUME_REQUESTED`) is written in the same transaction as the state change and polled with `FOR UPDATE SKIP LOCKED`, an attempts cap, and no broker.
12. **Idempotency:** `POST /tickets/{id}/resolve` is idempotent; refunds use key `refund:{caseId}` plus a unique constraint on `refunds.ticket_id`.
13. **Errors:** `ProblemDetail` plus `traceId`, `requestId`, `errors[]`. Never leak stack traces or SQL. Never return entities or password hashes.
14. **Validation:** Bean Validation on every request record for shape and limits. Business checks (ownership, window, duplicates, amount) belong in `CaseValidationService` and the PEP, not the create endpoint, so attack presets reach the pipeline and are caught visibly.
15. **Security hygiene:** secrets only from environment variables; never log tokens, passwords, API keys, full policy text, or customer messages above DEBUG; no `System.out`; parameterized SQL only; treat AI output and customer text as untrusted data, never concatenate them into SQL or logs unescaped.

## 6. Java Conventions

- Constructor injection only; no field injection; no `@Autowired` on fields.
- `@Transactional` on service methods, never on controllers; read-only where applicable.
- Records for DTOs, events, and value objects; JPA entities are plain classes with explicit constructors and no `@Data`-style magic.
- Enums for statuses and verdicts; store as text; the enum values must match the frontend strings exactly.
- No catch-and-swallow. Catch only to translate or to record a failed node, and always audit it.
- Names say what a thing is: `PolicyEnforcementPoint`, not `RuleManager`.
- Small methods, early returns, no deep nesting, no static mutable state.

## 7. Working Method

For every task:

1. **Read** this file, `docs/backend-prd.md`, and the relevant frontend files (Section 2). Match existing code style.
2. **Plan in 3 to 6 lines:** classes, migrations, endpoints, anything to delete, any dependency question. Ask at most one question, only if an essential detail is missing; otherwise proceed and list assumptions.
3. **Implement the smallest slice** that satisfies the phase. Reuse before creating, delete before adding.
4. **Test first for risk:** write the tenant isolation, PEP, refund-guard, and concurrency tests before the code they protect. All database tests use Testcontainers PostgreSQL with pgvector, never H2.
5. **Verify:** `mvn verify` green, application starts, and for API work call the endpoints and compare JSON against the frontend Zod schemas by hand or by a contract test.
6. **Self-review with the Ponytail checklist** (Section 9) and delete what fails.
7. **Report:** what you built, what you deleted, assumptions, contract differences found against the frontend, anything needing the user's decision.

### Build order (phases B1 to B8, see PRD Section 14)

1. B1: Maven project, Docker Compose, Flyway skeleton, roles and RLS, JWT auth, errors, request id, CORS
2. B2: tenants, settings, customers, transactions, demo seed, reset, demo-login
3. B3: policy upload, ingestion, embeddings, retrieval, signed PDF URLs, tenant WebSocket
4. B4: tickets, validation, PEP, refund service, audit, outbox, engine gateway, simulated engine, case WebSocket, case detail
5. B5: escalations, approve, reject, resume, retry
6. B6: ticket list filters, audit endpoint, metrics
7. B7: security lab, comparison
8. B8: proto files, empty gRPC adapters, OpenTelemetry, concurrency and contract tests, README

Do one phase per task. After each phase, stop and report. Do not start the next phase until told.

## 8. Required Tests (never skip)

- Tenant A cannot read Tenant B data via API, and RLS blocks raw SQL with the wrong `app.tenant_id`
- Invalid, expired, and role-escalated JWTs; WebSocket rejects bad tokens
- One unit test per PEP rule plus verdict combinations
- `RefundService` refuses without stored ALLOW or approved review, and refuses amounts above approval
- Ten concurrent identical resolve or refund requests produce exactly one refund
- Amount manipulation, unauthorized refund, duplicate refund, and prompt-injection preset end in DENY or ESCALATE
- Ingested chunks carry `tenant_id, policy_id, policy_version, document_name, page_number, section, chunk_id`; retrieval returns only active versions of the caller's tenant
- Event order and monotonic `seq` for a safe case and a human-review case; reconnect snapshot plus WebSocket leaves no gaps
- `audit_logs` rejects update and delete
- Contract test: every REST response and WebSocket event validates against the frontend Zod-derived shapes

## 9. Ponytail Review Checklist (run before every handoff)

- [ ] Did I add anything not required by the current phase? Remove it.
- [ ] Did I add a dependency? Is it on the allowlist? If not, revert or ask.
- [ ] Is there a JDK or Spring feature I skipped?
- [ ] Does every interface have two implementations or a PRD requirement?
- [ ] Did I delete the code my change made obsolete?
- [ ] Any unused class, method, bean, property, import, or migration column?
- [ ] Could a junior engineer audit each changed class in two minutes?
- [ ] Do enum values and JSON field names match the frontend exactly?
- [ ] Is every new tenant-owned table covered by RLS and a cross-tenant test?
- [ ] Is anything secret or sensitive logged?
- [ ] Do `mvn verify` and the Testcontainers suite pass?

## 10. Definition of Done

A phase is done when: `mvn verify` is green; the application starts with `dev,sim,demo` profiles against Docker Compose PostgreSQL; the phase's endpoints and events match the frontend contract; the required tests for that phase exist and pass; the checklist above passes; and no dependency was added without approval.

## 11. Never Do

- Never implement the Python state machine or LangGraph logic. gRPC stays contract-only with empty adapters.
- Never let the engine, or any caller other than `RefundService`, change financial state.
- Never skip RLS, connect as the table owner, or rely on application-level tenant filters alone.
- Never expose entities, stack traces, SQL errors, or secrets in responses or logs.
- Never use H2, STOMP, Lombok, a JWT library, or an LLM SDK.
- Never edit an applied Flyway migration or let Hibernate change the schema.
- Never edit frontend files or rename fields the frontend already uses.
- Never leave TODO stubs, placeholder classes, or commented-out code outside the gRPC adapters.
- Never use real payment credentials. All money movement is simulated.