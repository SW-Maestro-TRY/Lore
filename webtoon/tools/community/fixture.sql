INSERT INTO users(id,email,role,status,created_at,updated_at) OVERRIDING SYSTEM VALUE VALUES
 (91001,'writer@community.invalid','USER','ACTIVE',now(),now()),
 (91002,'reader@community.invalid','USER','ACTIVE',now(),now()),
 (91003,'admin@community.invalid','ADMIN','ACTIVE',now(),now());
INSERT INTO webtoon_job(public_id,browser_uid,checkpoints,created_at,updated_at,stage,status,style,run_id,user_id,input_json)
VALUES ('community-job-1','community-writer',false,now(),now(),'BIND','DONE','romance_fantasy','community-run-1',91001,'{"name":"하린"}'),
 ('community-job-2','community-writer',false,now(),now(),'BIND','DONE','romance_fantasy','community-run-2',91001,'{"name":"하린"}');
INSERT INTO webtoon_work(browser_uid,created_at,job_id,run_id,user_id,is_public) VALUES
 ('community-writer',now(),'community-job-1','community-run-1',91001,true),
 ('community-writer',now(),'community-job-2','community-run-2',91001,false);
INSERT INTO webtoon_story(chosen,created_at,n,run_id,title,plot,genre) VALUES
 (true,now(),1,'community-run-1','창가에 남겨 둔 편지','헌책방에서 시작된 작은 이야기','일상'),
 (true,now(),1,'community-run-2','편지를 기다리는 사람','기다리던 답장이 도착했다','일상');
INSERT INTO webtoon_page(bytes,created_at,page_no,run_id,s3_key,width) VALUES
 (1000,now(),1,'community-run-1','static/samples/ex-harin-1.jpg',1080),
 (1000,now(),1,'community-run-2','static/samples/ex-harin-1.jpg',1080);
INSERT INTO webtoon_character(public_id,name,description,source,status,owner_id,browser_uid,created_at,updated_at,art_key)
 VALUES ('community-harin','하린','낮에는 헌책방을 지키고, 밤에는 주인을 잃은 편지를 배달한다.','PROMPT','READY',91001,'community-writer',now(),now(),'static/samples/ex-harin-1.jpg');
-- 연재 모듈의 실제 스키마 계약을 검증하는 두 번째 회차.
CREATE TABLE webtoon_series_chapter(job_id varchar(40) PRIMARY KEY,root_job varchar(40) NOT NULL,episode_number integer NOT NULL);
INSERT INTO webtoon_series_chapter VALUES('community-job-2','community-job-1',2);

INSERT INTO webtoon_page(bytes,created_at,page_no,run_id,s3_key,width) SELECT bytes,created_at,page_no,run_id,s3_key,320 FROM webtoon_page WHERE run_id LIKE 'community-run-%' AND width=1080;
