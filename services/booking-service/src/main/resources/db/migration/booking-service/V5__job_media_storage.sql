-- Records which store holds each job_media row's bytes, so photos stay readable when the store the
-- service writes to changes (S3 turned on, or a replica falling back to local disk because S3 was
-- unreachable at startup). Every existing row was written by the local adapter, which is what the
-- default records; the application always sets the column explicitly from now on. The CHECK
-- mirrors the one Hibernate generates for a STRING-mapped enum (as on job_interval.kind).
-- Additive and metadata-only on PostgreSQL 11+ (constant default): no table rewrite.
ALTER TABLE booking.job_media
    ADD COLUMN storage character varying(10) NOT NULL DEFAULT 'LOCAL';

ALTER TABLE booking.job_media
    ADD CONSTRAINT job_media_storage_check CHECK (storage IN ('LOCAL', 'S3'));
