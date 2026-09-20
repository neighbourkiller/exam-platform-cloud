ALTER TABLE anti_cheat_event
    ADD COLUMN evidence_json TEXT NULL AFTER payload;
