# ResolvOS Backend: Product Requirements Document

**Version:** 1.0 | **Date:** 23 Oct 2026 | **Scope:** Spring Boot backend only. The Python LangGraph engine is out of scope; its gRPC boundary is defined and left empty.

## 1. Purpose

Build the system of record behind the ResolvOS demo frontend. It owns tenants, auth, customers, transactions, policy documents and their RAG index, cases (tickets), the deterministic Policy Enforcement Point (PEP), human review, refund execution, audit and metrics. It exposes REST and native WebSocket to the existing frontend and a gRPC contract for the future Python engine.

Trust model, which the code structure must make obvious:

```
LLM -> recommendation | LangGraph -> workflow | Spring PEP -> ALLOW/DENY/ESCALATE | Spring RefundService -> state change | PaymentGateway
```

Rules:

1. Spring Boot is the final authority for validation, enforcement and money movement. The engine can only propose.
2. The frontend must switch from Mock to Live with no component changes, so field names, enums, node names and event shapes follow the frontend contract exactly (Section 12 lists the few additions).
3. The agent works in the same workspace as the frontend and must read the frontend's `lib/schemas`, `lib/domains`, `lib/mock` and `lib/api` before coding, and derive seed data, enums and payloads from them.

**Out of scope:** Python engine, real payments, Kafka/RabbitMQ, Redis, Keycloak, GraphQL, STOMP/SockJS, Kubernetes, microservices, separate API gateway, claims as a separate entity (insurance claims are modeled as refund cases), MinIO/S3 (local file storage behind an interface), Stripe/Razorpay adapters.

## 2. Fixed Tech Stack

| Area | Choice |
| --- | --- |
| Language / build | Java 21 (virtual threads on), Maven |
| Framework | Spring Boot 4.1.x (GA June 2026; use the latest 4.1 patch and the 4.1 modular starters) |
| Web | Spring Web MVC, Bean Validation, `ProblemDetail` errors |
| Security | Spring Security, JWT via Nimbus encoder/decoder already bundled with Spring Security (HS256), no extra JWT library |
| Persistence | Spring Data JPA, Hibernate, PostgreSQL 16+, Flyway (SQL migrations), pgvector queried with native SQL (no pgvector Java library) |
| Realtime | Spring WebSocket, plain `TextWebSocketHandler` (frontend uses native WebSocket, not STOMP) |
| gRPC | Spring Boot 4.1 gRPC starters (`spring-boot-starter-grpc-server`, `spring-boot-starter-grpc-client`), protobuf; contract only |
| PDF | Apache PDFBox (only non-Spring runtime library; no native alternative for page-level text) |
| HTTP client | Spring `RestClient` for the embedding provider (no vendor SDK) |
| Observability | Micrometer Tracing with OpenTelemetry bridge, Boot structured JSON logs |
| Tests | JUnit 5, Mockito, Spring Boot Test, Testcontainers PostgreSQL with pgvector image. Never H2. |
| Local infra | Docker Compose: PostgreSQL with pgvector |

Ponytail rules apply to the backend: YAGNI, zero dependencies beyond this table, deletion over addition, simplicity over cleverness.

## 3. Workspace Layout

```
/ (repo root)
  <frontend>/                  existing Next.js app, read-only for this work
  backend/
    pom.xml
    src/main/java/com/resolvos/
      ResolvosApplication.java
      common/                  config/ (Security, Cors, WebSocket, Async, Properties)
                               error/ (GlobalExceptionHandler, ApiProblem)
                               trace/ (RequestIdFilter, TraceContext)
                               page/ (PageResponse)
      tenant/                  Tenant, TenantSettings, TenantContext, TenantController,
                               TenantAwareTransactionManager (sets RLS variable)
      auth/                    AuthController, JwtService, JwtAuthFilter, User, Role, RefreshToken
      customer/                Customer, Transaction, controllers, repositories
      policy/                  Policy, PolicyVersion, PolicyChunk, PolicyController
        ingestion/             PdfTextExtractor, SectionDetector, Chunker, IngestionService
        retrieval/             PolicyRetrievalService, EmbeddingClient, HttpEmbeddingClient
        storage/               StorageService, LocalFileStorage, PolicyFileToken
      ticket/                  Ticket, TicketController, TicketService, CaseNodeRun,
                               ResolutionOrchestrator, CaseEventHandler, CaseValidationService
      engine/                  EngineGateway, EngineEvent (records)
        grpc/                  GrpcEngineGateway (empty), BackendCallbackGrpcService (empty)
        sim/                   SimulatedEngineGateway (profile sim, temporary)
      enforcement/             PolicyEnforcementPoint, EnforcementRule(s), Verdict
      refund/                  Refund, RefundService, PaymentGateway, MockPaymentGateway
      review/                  HumanReview, EscalationController, ReviewService
      audit/                   AuditLog, AuditService, AuditController
      realtime/                CaseWebSocketHandler, TenantWebSocketHandler,
                               WsHandshakeInterceptor, RealtimeBroadcaster, WsEvent
      outbox/                  OutboxEvent, OutboxPoller
      metrics/                 MetricsController, MetricsService
      securitylab/             SecurityLabController, AttackRunner
      compare/                 CompareController, BenchmarkResult
      demo/                    DemoController, DemoSeeder (profile demo)
    src/main/resources/
      application.yml, application-dev.yml, application-sim.yml, application-demo.yml
      db/migration/            V1__tenants_users.sql ... (Section 6)
    src/test/java/com/resolvos/  integration and unit tests mirroring packages
  proto/resolvos/engine/v1/    engine.proto, callback.proto (shared with the future Python engine)
  demo-data/                   seed fixtures (JSON) derived from frontend domain configs
  docker-compose.yml           postgres (pgvector)
  docs/                        PRD files, ADRs
```

Package by feature. Dependencies flow controller to service to repository. Only `engine` is allowed to know about gRPC. Controllers never touch repositories directly.

## 4. Multi-Tenancy and Security

**Tenant = company.** Every tenant-owned table has `tenant_id`. PostgreSQL Row-Level Security is the enforcement layer; Java filters are not trusted alone.

- Flyway runs as `resolvos_owner`. The application connects as `resolvos_app`, a role that owns nothing and has no `BYPASSRLS`. Also use `FORCE ROW LEVEL SECURITY` on tenant tables.
- `TenantAwareTransactionManager` (a `JpaTransactionManager` subclass) runs `select set_config('app.tenant_id', ?, true)` right after each transaction begins. Policies: `USING (tenant_id = current_setting('app.tenant_id')::uuid)`.
- Request threads get the tenant from the JWT claim. Non-request threads (orchestrator, outbox poller, engine event handler) must set `TenantContext` explicitly from the row they process.
- No RLS (documented exceptions): `tenants`, `users` (login lookup; hash never serialized), `outbox_events` (ids and types only), `benchmark_results` (global fixtures).
- `audit_logs` is append-only: revoke `UPDATE` and `DELETE` from `resolvos_app`.

**Auth.** `POST /api/auth/login` returns an access JWT (15 min, claims: sub, tenantId, role) in the body and sets a refresh token as an httpOnly cookie scoped to `/api/auth` (7 days). `POST /api/auth/refresh` rotates it. Passwords hashed with the Spring Security default `PasswordEncoder`.

**Demo login (profile `demo` only).** `POST /api/auth/demo-login { tenantId, role }` issues tokens without a password so the frontend role switcher works. It must not exist outside profile `demo`.

**Roles:** `SUPER_ADMIN`, `TENANT_ADMIN`, `SUPPORT_AGENT`, `REVIEWER`. Simple role checks with method security, no RBAC engine. The customer simulator uses a `SUPPORT_AGENT` token. Reviewers and admins can read audit logs.

**WebSocket auth.** Browsers cannot set headers on WebSocket, so the handshake accepts `?access_token=<jwt>`, validated by `WsHandshakeInterceptor`. Redact query strings from all logs.

**PDF viewing auth.** An `<iframe>` cannot send headers. `POST /api/policies/{id}/versions/{v}/file-url` returns a short-lived signed URL (HMAC token in `?t=`, 5 minutes) served by `GET .../file`.

**CORS.** Allow origin from `FRONTEND_ORIGIN`, credentials on, allow headers `Authorization, Content-Type, X-Request-Id`, expose `X-Request-Id`.

**Secrets** (JWT secret, embedding API key, DB passwords) only from environment variables. Never committed, never logged.

## 5. Data Model (Flyway migrations)

All money columns `numeric(14,2)`, Java `BigDecimal`, JSON as numbers in INR. Timestamps `timestamptz`, JSON ISO-8601 UTC.

| Migration | Tables |
| --- | --- |
| V1 | `tenants(id, slug, name, domain, created_at)`, `tenant_settings(tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence)`, `users(id, tenant_id, email, password_hash, role, display_name)`, `refresh_tokens` |
| V2 | `customers(id, tenant_id, name, tier, history_count)`, `transactions(id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at, force_gateway_failure)` |
| V3 | `policies(id, tenant_id, name)`, `policy_versions(id, policy_id, version, status, active, file_path, page_count, ingestion_config jsonb, uploaded_at)`, `policy_chunks(id, tenant_id, policy_id, policy_version, document_name, page_number, section, token_count, content, embedding vector(${embedding_dimensions}))` with an HNSW cosine index. Dimension comes from a Flyway placeholder. |
| V4 | `tickets(id, tenant_id, case_number, customer_id, transaction_id, message, requested_amount, status, outcome, trace_id, event_seq, created_by, created_at, completed_at)`, `case_node_runs(ticket_id, node, status, duration_ms, output jsonb, started_at, completed_at)`, `ai_runs(id, ticket_id, trace_id, model_config jsonb, started_at, finished_at)`, `ai_decisions`, `risk_assessments`, `enforcement_results(ticket_id, verdict, checks jsonb, reason)` |
| V5 | `human_reviews(ticket_id PK, status, reason, risk_level, reviewer_id, decision_amount, comment, created_at, resolved_at)`, `refunds(id, tenant_id, ticket_id UNIQUE, amount, gateway_ref, status, idempotency_key UNIQUE, created_at)` |
| V6 | `audit_logs(id, ts, tenant_id, case_id, actor, actor_type, action, old_state, new_state, trace_id, metadata jsonb)`, `outbox_events(id, tenant_id, type, payload jsonb, status, attempts, created_at, processed_at)` |
| V7 | `benchmark_results(id, preset_id, approach, decision, amount, correct, latency_ms, estimated_cost_usd, citation_present, unsafe_action)` |
| V8 | RLS enablement, policies, grants, roles |

Enums (store as text, validate in code): domain `BANKING | INSURANCE | SUBSCRIPTIONS | ECOMMERCE | TRAVEL`; ticket status `RECEIVED | PROCESSING | AWAITING_HUMAN | COMPLETED | FAILED`; outcome `AUTO_APPROVED | HUMAN_APPROVED | DENIED | HUMAN_REJECTED`; decision `APPROVE_REFUND | REJECT | PARTIAL_REFUND | NEEDS_INFO`; verdict `ALLOW | DENY | ESCALATE`; risk `LOW | MEDIUM | HIGH`; node status `PENDING | RUNNING | SUCCESS | WARNING | FAILED | SKIPPED | WAITING_HUMAN`.

## 6. Resolution Workflow

**Nodes (fixed strings, in order):** `INTAKE, VALIDATION, POLICY_RETRIEVAL, DECISION, RISK, ENFORCEMENT, HUMAN_REVIEW, EXECUTION`.

| Node | Executed by | `output` shape on `NODE_COMPLETED` |
| --- | --- | --- |
| INTAKE | Engine | `{intent, entities:{orderId?, amount?, reason?}, message}` |
| VALIDATION | Spring `CaseValidationService` | `{checks:[{rule, passed, reason}], message}` |
| POLICY\_RETRIEVAL | Engine, using Spring retrieval | `{chunks:[PolicyReference], message}` |
| DECISION | Engine (LLM) | `{decision, recommendedAmount, confidence, reasoningSummary, message}` |
| RISK | Engine | `{riskLevel, riskFactors:[string], message}` |
| ENFORCEMENT | Spring `PolicyEnforcementPoint` | `{verdict, checks:[{rule, passed, reason}], reason, message}` |
| HUMAN\_REVIEW | Spring `ReviewService` | `{reviewer?, decision?, approvedAmount?, comment?, message}` |
| EXECUTION | Spring `RefundService` | `{refundRef, gatewayStatus, amount, message}` |

`message` is the one-line agent message the frontend shows in its agent feed (for example "Retrieval Agent: found 4 relevant clauses in Refund Policy v3").

**Flow.**

1. `POST /api/tickets` validates schema and tenant only, creates the case (`RECEIVED`), generates `traceId`, audits `CASE_CREATED`. Business checks (ownership, window, duplicates, amount) are deliberately not done here so attack presets reach the pipeline and are caught visibly.
2. `POST /api/tickets/{id}/resolve` atomically moves `RECEIVED` to `PROCESSING` and writes an outbox row `RESOLVE_REQUESTED` in the same transaction. Calling it again is idempotent (returns the current state, 202).
3. `OutboxPoller` (`FOR UPDATE SKIP LOCKED`, scheduled) picks it up and calls `ResolutionOrchestrator`, which sets `TenantContext` and calls `EngineGateway.resolve(request, listener)`.
4. Every engine event goes through `CaseEventHandler`: persist `case_node_runs`, bump `event_seq`, broadcast over WebSocket after commit. Spring-owned services write their own audit events inside the service (`RULE_CHECKED`, `REFUND_EXECUTED`, `REFUND_FAILED`), so audit is identical whichever engine runs.
5. On `HITL_REQUIRED`: create `human_reviews` (OPEN), case `AWAITING_HUMAN`, audit `HITL_TRIGGERED`, broadcast to case and tenant channels. The case id is the escalation id (one open escalation per case).
6. Approve or reject (REST) validates and records the decision, audits `HUMAN_APPROVED` or `HUMAN_REJECTED`, emits the `HUMAN_REVIEW` node events, broadcasts `HITL_RESOLVED`, writes outbox `RESUME_REQUESTED`; the poller calls `EngineGateway.resume(...)`.
7. On completion: set status `COMPLETED` and outcome, broadcast `CASE_COMPLETED`.
8. `POST /api/tickets/{id}/retry` re-runs from the failed node (409 if no node failed). If the engine is unreachable or times out, mark the current node `FAILED`, case `FAILED`, audit it, and allow retry.

**Policy Enforcement Point.** Receives the engine's decision and returns `ALLOW`, `DENY` or `ESCALATE` with seven named checks, always all seven, always in this order:

| Rule id | Passes when | Failure effect |
| --- | --- | --- |
| `SCHEMA_VALID` | Decision payload valid, amount positive | DENY |
| `TENANT_VALID` | Case, customer, transaction, policy chunks all belong to the case tenant | DENY |
| `POLICY_CONDITIONS_MET` | At least one active-policy citation supports the action; decision is not `REJECT` | DENY |
| `AMOUNT_WITHIN_LIMIT` | Amount <= transaction amount, <= per-customer limit for the period | DENY |
| `TRANSACTION_VALID` | Transaction exists, belongs to the customer, within refund window, not already refunded | DENY |
| `RISK_ACCEPTABLE` | Risk is not `HIGH` and confidence >= `min_confidence` | ESCALATE |
| `HITL_REQUIRED` | No human review needed (amount <= `auto_approve_limit`; amount > `hitl_trigger_limit` fails) | ESCALATE |

Verdict: any DENY-class failure gives `DENY`; else any ESCALATE-class failure (or `NEEDS_INFO`) gives `ESCALATE`; else `ALLOW`. Pure functions, unit tested one rule at a time.

**Refund execution.** `RefundService.execute` is the only code that moves money. It refuses unless a stored `ALLOW` enforcement result or an approved `human_reviews` row exists for the case with amount >= the refund amount. It uses idempotency key `refund:{caseId}` and the unique constraint on `refunds.ticket_id`; ten concurrent identical requests produce one refund. `PaymentGateway` is an interface with `MockPaymentGateway` only; it fails when the transaction has `force_gateway_failure` (so the frontend can demo `NODE_FAILED` and Retry on `EXECUTION`).

**Simulated engine (profile `sim`, temporary).** `SimulatedEngineGateway` emits the same event sequence as the real engine, using real retrieval results and calling the real validation, PEP and refund services. Presets decide behavior from the case text and amount: valid case, high-amount case (HITL), engine failure at `DECISION`, attack cases where the simulated LLM naively complies so the PEP must catch them. Delete this package when the Python engine lands.

## 7. RAG: Ingestion and Retrieval

**Ingestion** (async, after upload): store file, then `PARSING` (PDFBox text per page), `CHUNKING` (detect numbered headings such as `4.2 Refund window` as `section`, split by configured size and overlap, never across pages), `EMBEDDING` (batch through `EmbeddingClient`), `INDEXED` (insert chunks). Each stage publishes `INGESTION_PROGRESS` on the tenant channel. Failure publishes stage `FAILED` with an error message and marks the version failed.

Config (RQ2 knobs, all in `application.yml`): `rag.chunk-size-tokens` (400), `rag.chunk-overlap-tokens` (60), `rag.top-k` (4), `embedding.provider`, `embedding.model`, `embedding.dimensions`, `embedding.base-url`. Token count is a whitespace approximation (no tokenizer library). The effective config is stored in `policy_versions.ingestion_config` and each run's `ai_runs.model_config`, with provider, model, version and date, so experiments stay reproducible and provider-agnostic.

`EmbeddingClient` has one HTTP implementation for an OpenAI-compatible embeddings endpoint and a deterministic hash-based fake used only under `test` and `sim` profiles.

**Retrieval.** `PolicyRetrievalService.search(tenantId, queryText, topK)` embeds the query, then runs native SQL ordered by cosine distance over chunks of **active** policy versions of that tenant (RLS also applies). Each result is a `PolicyReference`:

```json
{ "policyId":"", "version":3, "documentName":"Refund Policy", "page":7,
  "section":"4.2", "chunkId":"", "excerpt":"...", "score":0.83 }
```

This is what lets the UI show "Decision based on Refund Policy v3, Section 4.2, Page 7". Never return chunks without these fields.

## 8. REST API

Base `/api`. JSON camelCase. Lists use `{items, page, size, totalElements, totalPages}`. Errors are `ProblemDetail` plus `traceId`, `requestId` and `errors[]` for validation. Roles: SA SUPER\_ADMIN, A TENANT\_ADMIN, S SUPPORT\_AGENT, R REVIEWER. Every request accepts `X-Request-Id` and echoes it.

| Method and path | Purpose | Roles |
| --- | --- | --- |
| POST `/auth/login`, `/auth/refresh`, `/auth/demo-login` | Tokens | public |
| GET `/tenants` / POST `/tenants` | List (with `slug`, `domain`) / create company | SA, A / SA |
| GET `/tenants/{id}/settings` / PUT | Thresholds (auto-approve, HITL trigger, per-customer limit, window, min confidence) | A, S, R / A |
| GET `/customers` (`?q=`) | Customer picker | A, S, R |
| GET `/customers/{id}/transactions` | Transaction picker | A, S, R |
| POST `/policies` (multipart `file`, `name`) | Upload, version 1, returns 202 `{policyId, version, status}` | A |
| GET `/policies` / GET `/policies/{id}?version=` | Library; detail with versions, sections, chunks (`chunkId, section, page, tokenCount, excerpt`) | A, S, R |
| POST `/policies/{id}/versions` | Upload new version | A |
| POST `/policies/{id}/versions/{v}/activate` | Make version active | A |
| POST `/policies/{id}/versions/{v}/file-url`, GET `.../file?t=` | Signed PDF URL and stream | A, S, R |
| GET `/policies/search?q=&topK=` | Retrieval debug | A |
| POST `/tickets` | Create case `{customerId, transactionId, message, requestedAmount}` returns case with `caseId`, `caseNumber`, `traceId` | A, S |
| GET `/tickets` | List with filters `status, risk, outcome, from, to, customerId, q` (case id prefix), `sort` (amount, date, status), `page`, `size` (20) | A, S, R |
| GET `/tickets/{id}` | Case detail and reconnect snapshot: case fields, customer, transaction, `nodes[]`, `resolution` (ResolutionResult or null), `audit[]`, `lastSeq` | A, S, R |
| POST `/tickets/{id}/resolve`, `/retry` | Start or retry, 202 | A, S |
| GET `/escalations` (open) / GET `/escalations/{id}` | Queue; detail (case plus AI recommendation, citations, enforcement) | A, R |
| POST `/escalations/{id}/approve` `{amount?, comment?}` | Amount must be > 0 and <= min(transaction amount, per-customer limit); 422 otherwise | A, R |
| POST `/escalations/{id}/reject` `{comment}` | Comment >= 10 characters | A, R |
| GET `/audit-logs` | Filters `action, actorType, caseId`, paged | A, R |
| GET `/metrics/summary` | See below | A, S, R |
| POST `/security-lab/run` `{attack}` | Run one attack | A |
| POST `/compare/run` `{caseId? presetId?}` | Stored five-approach results plus aggregate accuracy | A |
| POST `/demo/seed`, `/demo/reset` (profile `demo`) | Seed or restore demo data | A, SA |

Status codes: 400 schema, 401, 403, 404, 409 (state conflict such as already resolved), 422 (business rule), 429 not implemented.

`ResolutionResult` (exactly as the frontend schema): `caseId, decision, recommendedAmount, confidence, riskLevel, policyReferences[], requiresHumanReview, reasoningSummary, enforcement[{rule, passed, reason}], verdict, traceId`.

`GET /metrics/summary` returns aggregates only (frontend must not compute): `totalCases, autoResolved, escalated, rejected, totalRefunded, avgResolutionMs, casesByOutcome[], casesOverTime[{date,count}], avgLatencyByStage[{node, avgMs}]`. It doubles as the frontend's connection test.

Security lab attacks: `PROMPT_INJECTION, AMOUNT_ESCALATION, UNAUTHORIZED_REFUND, VALIDATION_BYPASS, DUPLICATE_REFUND, POLICY_MANIPULATION`. Response `{attack, blocked, caughtBy (LLM_GUARD | SCHEMA_VALIDATION | POLICY_ENFORCEMENT | TENANT_ISOLATION), verdict, caseId?, auditHint}` plus summary counts. Attacks run through the real stack, not canned results.

## 9. WebSocket

Plain WebSocket, two channels, auth by `?access_token=`.

- `/ws/cases/{caseId}`: case events.
- `/ws/tenant`: tenant-wide events (needed for the escalation queue, bell count and ingestion progress).

Messages are flat JSON with a `type` field. Case events also carry a per-case increasing `seq`; the client discards events with `seq <= lastSeq` from the REST snapshot, so reconnect loses nothing.

```
INGESTION_PROGRESS  { policyId, version, stage: UPLOADED|PARSING|CHUNKING|EMBEDDING|INDEXED|FAILED, percent, error? }   tenant
NODE_STARTED        { caseId, seq, node, ts }                                      case
NODE_COMPLETED      { caseId, seq, node, durationMs, output }                      case
NODE_FAILED         { caseId, seq, node, error }                                   case
HITL_REQUIRED       { caseId, seq, reason, riskLevel }                             case + tenant
HITL_RESOLVED       { caseId, seq, decision, reviewer }                            case + tenant
CASE_COMPLETED      { caseId, seq, verdict, refundRef? }                           case + tenant
AUDIT_EVENT         { caseId, seq, action, actor, ts }                             case
```

Broadcast only after the database transaction commits. Server pings every 25 s. Slow clients are dropped, never block the pipeline.

## 10. gRPC Boundary (contract only, implementations left empty)

Proto files live in `/proto/resolvos/engine/v1/` so the Python engine can use them later. Generate Java stubs in the build. The adapter classes exist but are empty: `GrpcEngineGateway` throws `UnsupportedOperationException`, `BackendCallbackGrpcService` methods return gRPC `UNIMPLEMENTED`. This is the only place an empty implementation is allowed. Wiring is selected by profile: `sim` uses the simulated gateway, `grpc` uses the empty adapters.

**`ResolutionEngine` (hosted by Python, called by Spring):**

- `Resolve(ResolveCaseRequest) returns (stream EngineEvent)`, request: `tenant_id, case_id, customer_id, transaction_id, ticket_text, requested_amount, trace_id`. IDs and text only, not the database.
- `Resume(ResumeCaseRequest) returns (stream EngineEvent)`, request: `case_id, trace_id, reviewer, decision, approved_amount, comment`.
- `EngineEvent` is a `oneof`: node started, node completed (`node, duration_ms, output_json`), node failed, hitl required, final decision. Final decision carries `decision, recommended_action, recommended_amount, confidence, risk_level, policy_references, requires_human_review, reasoning_summary, trace_id, model_config` (provider, model, version, date).
- `trace_id` travels in gRPC metadata as well.

**`BackendCallback` (hosted by Spring, called by Python):** `GetCaseContext`, `ValidateCase`, `SearchPolicyChunks(tenant_id, query_text, top_k)`, `EnforceDecision`, `ExecuteRefund(case_id, amount, idempotency_key)`. Each one delegates to the same Java services the simulated engine already uses, so enabling gRPC later is wiring only. The engine can never change state except through these calls.

## 11. Audit, Observability, Outbox

- **Audit actions:** `CASE_CREATED, POLICY_RETRIEVED, AI_DECISION_GENERATED, RULE_CHECKED, HITL_TRIGGERED, HUMAN_APPROVED, HUMAN_REJECTED, REFUND_EXECUTED, REFUND_FAILED`. Actor types `AI, SYSTEM, HUMAN, CUSTOMER`. Every row carries `trace_id`.
- **Tracing:** one `trace_id` per case from creation, in logs (MDC), WebSocket events, audit rows, gRPC metadata and the API response. `X-Request-Id` per request. Log per-stage latency, which feeds `avgLatencyByStage`.
- **Outbox:** used for reliable engine dispatch (`RESOLVE_REQUESTED`, `RESUME_REQUESTED`), written in the same transaction as the state change, polled with retries and an attempts cap. No broker.
- **Logs:** structured JSON, never log tokens, passwords, API keys, full policy text or customer messages above DEBUG.

## 12. Frontend Alignment: Contract Additions

The frontend schemas and its Phase 8 contract table must accept these backend additions. List them in the report; do not rename existing fields.

1. `/ws/tenant` channel for escalation queue, bell count and ingestion progress.
2. `seq` on case events and `lastSeq` on `GET /tickets/{id}` for reconnect.
3. `INGESTION_PROGRESS` also carries `version`, stage `FAILED` and `error`.
4. Audit action `HUMAN_REJECTED` (rejections must be auditable).
5. `caseNumber` (display id) next to `caseId` (UUID used in routes).
6. Comparison cost field is `estimatedCostUsd` (LLM cost is in USD, refunds in INR).
7. Pagination envelope, ProblemDetail error shape, `X-Request-Id`.
8. Policy PDF access via signed `file-url` because an iframe cannot send `Authorization`.
9. Frontend uploads sample policy PDFs through the normal multipart endpoint; the backend has no sample-policy endpoint.

## 13. Testing Requirements

Testcontainers PostgreSQL with pgvector for every database test. Required automated tests:

- Tenant A cannot read Tenant B data through any endpoint, and RLS blocks raw queries with the wrong `app.tenant_id`.
- Invalid, expired and role-escalated JWTs; WebSocket rejects bad tokens.
- Each of the seven PEP rules, plus verdict combinations.
- `RefundService` refuses without ALLOW or approved review; refuses amount above approval.
- 10 concurrent identical resolve or refund requests produce exactly one refund.
- Amount manipulation, unauthorized refund, duplicate refund, prompt-injection preset (via sim) all end DENY or ESCALATE.
- Ingestion produces chunks with all metadata fields; retrieval returns only active versions of the caller's tenant.
- Event order and `seq` monotonic for a safe case and a HITL case; reconnect snapshot plus WebSocket has no gaps.
- Audit rows are append-only (update and delete fail).
- Contract test: JSON of every response and WebSocket event validates against schemas derived from the frontend Zod definitions.

## 14. Build Phases and Acceptance

| Phase | Scope | Unblocks frontend |
| --- | --- | --- |
| B1 | Maven project, Docker Compose, Flyway V1-V8 skeleton, roles and RLS, JWT auth, error handling, request id, CORS | Phase 8 login |
| B2 | Tenants, settings, customers, transactions, demo seed and reset, demo-login | Phases 1 to 3 data |
| B3 | Policy upload, ingestion, embedding client, retrieval, signed PDF URLs, `/ws/tenant` | Phase 2 wizard, Phase 7 viewer |
| B4 | Tickets, validation, PEP, refund service, audit, outbox, EngineGateway, sim engine, case WebSocket, case detail | Phases 3 and 4 |
| B5 | Escalations, approve and reject, resume, retry | Phase 5 |
| B6 | Ticket list filters, audit endpoint, metrics | Phase 6 |
| B7 | Security lab, comparison | Phase 7 |
| B8 | Proto files, empty gRPC adapters, OpenTelemetry, concurrency and contract tests, README | Phase 8 |

**Acceptance (demo ready):**

1. With only `sim`, `demo` and `dev` profiles, the frontend in Live mode runs the full golden path: domain, setup with real PDF upload and ingestion progress, safe case to `ALLOW` with a real citation (document, version, section, page), high-risk case through approve and reject, audit trail, security lab, comparison.
2. Switching the frontend Mock to Live changes no component.
3. No refund can occur without PEP `ALLOW` or an approved human review; proven by test.
4. Tenant isolation holds at the database layer; proven by test.
5. Every case has a `traceId` visible in API, events, audit and logs.
6. gRPC proto files compile and empty adapters are in place; no Python code exists.

## 15. Open Questions

1. Which embedding provider and dimension for the demo (default assumption: an OpenAI-compatible endpoint, dimension 768)?
2. Spring Boot 4.1 versus the older 3.5 line: assumed 4.1 because gRPC support is now in Boot itself.
3. Should `NEEDS_INFO` always escalate? Assumed yes.
4. Frontend folder name and sample company slugs: the agent reads them from the frontend domain configs.
5. Is the retry for `EXECUTION` allowed to re-call the gateway? Assumed yes with the same idempotency key.
