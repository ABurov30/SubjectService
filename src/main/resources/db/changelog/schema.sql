--liquibase formatted sql
--changeset aburov:1
CREATE TABLE subjects (id UUID PRIMARY KEY, name VARCHAR(255) NOT NULL, status VARCHAR(20) NOT NULL CHECK (status IN ('CREATED','REVIEW','TERMINATED')), version BIGINT NOT NULL DEFAULT 0);

CREATE TABLE subject_service_outbox (
 id UUID PRIMARY KEY, subject_id UUID NOT NULL REFERENCES subjects(id), aggregate_type VARCHAR(255) NOT NULL,
 event_type VARCHAR(255) NOT NULL CHECK (event_type='CREATE_JIRA_TASK'), event_key VARCHAR(255) NOT NULL,
 payload JSONB NOT NULL, status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','IN_PROGRESS','PUBLISHED','FAILED')),
 attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0), retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count>=0),
 error_message TEXT, http_status INTEGER, created_at TIMESTAMPTZ NOT NULL, sent_at TIMESTAMPTZ,
 next_retry_at TIMESTAMPTZ, uncertain_until TIMESTAMPTZ, locked_at TIMESTAMPTZ, locked_until TIMESTAMPTZ, locked_by VARCHAR(255),
 jira_issue_id VARCHAR(255), jira_issue_key VARCHAR(255)
);

CREATE UNIQUE INDEX uq_subject_outbox ON subject_service_outbox(subject_id);
CREATE INDEX idx_outbox_ready ON subject_service_outbox(next_retry_at,created_at,id) WHERE status='PENDING';
CREATE INDEX idx_outbox_expired ON subject_service_outbox(locked_until,created_at,id) WHERE status='IN_PROGRESS';
