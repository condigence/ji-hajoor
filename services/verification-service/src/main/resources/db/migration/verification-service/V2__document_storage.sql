-- Records where each verification_document row's bytes are stored, and the file name the provider
-- uploaded, so admins can view the documents they approve providers on.
--
-- Until now the storage adapter kept no bytes: it derived an s3://bucket/key reference, logged it and
-- returned it, and nothing was written anywhere. Existing rows therefore get NULL storage, which the
-- service reads as "not stored" (404 DOCUMENT_NOT_STORED) without looking in any store. New rows
-- always set it to the store the bytes went to (LOCAL or S3). The CHECK mirrors the one Hibernate
-- generates for a STRING-mapped enum; a NULL passes it.
-- Additive and metadata-only on PostgreSQL 11+ (nullable columns, no default): no table rewrite.
ALTER TABLE verification.verification_document
    ADD COLUMN storage character varying(10);

ALTER TABLE verification.verification_document
    ADD CONSTRAINT verification_document_storage_check CHECK (storage IN ('LOCAL', 'S3'));

ALTER TABLE verification.verification_document
    ADD COLUMN file_name character varying(255);
