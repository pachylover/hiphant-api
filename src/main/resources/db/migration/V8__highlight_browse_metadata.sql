ALTER TABLE highlights
    ADD COLUMN thumbnail_image_url text,
    ADD COLUMN publish_date text;

CREATE INDEX idx_highlights_video_latest ON highlights (video_id, created_at DESC, id DESC);
