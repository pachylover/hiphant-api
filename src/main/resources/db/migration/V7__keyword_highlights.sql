ALTER TABLE highlights ADD COLUMN keyword varchar(50);
CREATE UNIQUE INDEX idx_highlights_keyword_minute
    ON highlights (video_id, keyword, minute) WHERE highlight_type = 'KEYWORD';
