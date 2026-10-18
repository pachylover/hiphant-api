-- Retain highlights after routine chat retention deletes the source row.
-- No FK: source IDs stay as immutable deduplication keys after chat cleanup.
ALTER TABLE highlights ADD COLUMN source_chat_id bigint;
CREATE UNIQUE INDEX idx_highlights_source_chat ON highlights (source_chat_id);
