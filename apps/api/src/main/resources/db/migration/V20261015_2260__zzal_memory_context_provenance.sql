-- All locally fetched remote history checked: 20261015_2260 unused on 2026-10-15.
-- Store which memory candidates a PET line was given, without changing its original text.
ALTER TABLE zzal_chat_turn ADD COLUMN recalled_memory_ids text;
ALTER TABLE zzal_fact_memory ADD COLUMN operation varchar(12) NOT NULL DEFAULT 'ADD';
