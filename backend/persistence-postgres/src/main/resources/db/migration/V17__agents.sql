-- V17: agents (P3.1, Alibaba AI Gateway agent-topology model)
-- An agent is a managed smart-agent resource whose egress is bound to one
-- upstream credential (the product follows from the credential). Usage
-- observability aggregates by the credential, giving the per-agent view.

CREATE TABLE agents (
    id                       uuid          PRIMARY KEY,
    tenant_id                uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name                     varchar(200)  NOT NULL,
    description              varchar(2000),
    upstream_credential_id   uuid          NOT NULL
                             REFERENCES upstream_credentials (id) ON DELETE RESTRICT,
    status                   varchar(16)   NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE', 'DISABLED')),
    version                  bigint        NOT NULL DEFAULT 0,
    created_by               uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_agents_tenant_name ON agents (tenant_id, name);
-- One credential backs at most one agent: usage aggregation by credential is
-- the per-agent observability, so sharing a credential would blur the view
-- (CodeRabbit review, PR #112). The unique index also serves the lookup.
CREATE UNIQUE INDEX uq_agents_tenant_credential ON agents (tenant_id, upstream_credential_id);
