UPDATE question_asset
SET url = REPLACE(url, 'http://127.0.0.1:9000/', 'http://127.0.0.1:19000/')
WHERE url LIKE 'http://127.0.0.1:9000/%';
