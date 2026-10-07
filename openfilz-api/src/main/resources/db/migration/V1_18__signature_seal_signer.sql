-- Name (CN) of the certificate that sealed a completed envelope's signed document, read from
-- the PDF at completion so the UI can show who vouches for it instead of the sealer id.
ALTER TABLE signature_envelope ADD COLUMN IF NOT EXISTS seal_signer VARCHAR(255);
