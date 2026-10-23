-- V3: Policies, versions, and chunks (pgvector)
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE policies (
    id        UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id UUID        NOT NULL REFERENCES tenants(id),
    name      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_policies_tenant_id ON policies(tenant_id);

CREATE TABLE policy_versions (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    policy_id        UUID        NOT NULL REFERENCES policies(id),
    version          INT         NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING|PARSING|CHUNKING|EMBEDDING|INDEXED|FAILED
    active           BOOLEAN     NOT NULL DEFAULT false,
    file_path        TEXT,
    page_count       INT,
    ingestion_config JSONB,
    uploaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(policy_id, version)
);

CREATE INDEX idx_policy_versions_policy_id ON policy_versions(policy_id);

-- Dimension is a Flyway placeholder; set to 768 in application.yml
CREATE TABLE policy_chunks (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    policy_id       UUID        NOT NULL REFERENCES policies(id),
    policy_version  INT         NOT NULL,
    document_name   TEXT        NOT NULL,
    page_number     INT         NOT NULL,
    section         TEXT        NOT NULL,
    token_count     INT         NOT NULL,
    content         TEXT        NOT NULL,
    embedding       VECTOR(${embedding_dimensions})
);

CREATE INDEX idx_policy_chunks_tenant_id ON policy_chunks(tenant_id);
CREATE INDEX idx_policy_chunks_policy_id ON policy_chunks(policy_id);

-- HNSW cosine index for fast ANN search
CREATE INDEX idx_policy_chunks_embedding_hnsw
    ON policy_chunks
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
