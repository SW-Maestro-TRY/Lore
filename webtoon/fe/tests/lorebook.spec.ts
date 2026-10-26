import {test,expect,type Page} from '@playwright/test';
import {readFileSync,mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
const base=resolve(__dirname,'../../..');
const worlds=JSON.parse(readFileSync(resolve(base,'webtoon/ai/new_harness/worlds.json'),'utf8')).presets;
const seeds=JSON.parse(readFileSync(resolve(base,'webtoon/ai/new_harness/lorebook.json'),'utf8')).worlds;
const books=Object.entries(worlds).map(([id,w]:[string,any])=>({id,name:w.label,source:'preset',confirmed:false,fixed_settings:w.text,progress_state:'',entries:(seeds[id]||[]).map((e:any)=>({title:e.name,content:e.text,keys:e.keys,kind:e.kind,always:false,enabled:true,evidence:''}))}));
const ok=(data:any)=>({success:true,data});
const hero={id:'hero-1',name:'민수',description:'다정한 청년',art_url:'/static/samples/ex-romance-1.jpg',source:'builtin',status:'ready',error:null,builtin:true,mine:false,created_at:'2026-10-01T00:00:00Z'};
async function setup(page:Page,empty=false){
 let sent:any=null;let personal:any[]=[];
 await page.addInitScript(({empty})=>{localStorage.setItem('lore_uid','lore-test');localStorage.setItem('lore_wt_notify_asked','1');if(!sessionStorage.getItem('lore_wizard_draft'))sessionStorage.setItem('lore_wizard_draft',JSON.stringify(empty?{}:{name:'민수',character:'다정한 청년',characterId:'hero-1',photos:[],quality:'wave',style:'romance',mode:'simple',create:'quick'}));},{empty});
 await page.route('**/api/**',async route=>{const p=new URL(route.request().url()).pathname;const send=(data:any)=>route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
 if(p==='/api/v1/users/me')return send(ok({userId:7,email:'writer@example.test',nickname:'작가',role:'USER'}));
 if(p==='/api/v1/credits/me')return send(ok({balance:48}));
 if(p.endsWith('/allowance'))return send({logged_in:true,balance:48,credit_cost:12,qualities:[{key:'wave',credits:12}]});
 if(p.endsWith('/lorebooks'))return send({books,personal_books:personal,extract_limit:3,extract_left:3});
 // 내 설정집 저장 — 새로 만들면 새 번호, 번호가 있으면 그 책을 고친다(PrivateLorebookService 와 같다).
 if(p.includes('/lorebooks/mine')&&route.request().method()!=='GET'){const tail=p.split('/').pop()!;const id=tail==='mine'?`my-${personal.length+1}`:decodeURIComponent(tail);const book={...route.request().postDataJSON(),library_id:id,library_owner:7};personal=[...personal.filter(b=>b.library_id!==id),book];return send(book);}
 if(p.endsWith('/lorebooks/extract'))return send({id:'extract-test',status:'RUNNING'});
 if(p.endsWith('/lorebooks/extract/extract-test'))return send({status:'DONE',book:{name:'다리 너머의 약속',source:'extracted',fixed_settings:'민수는 높은 곳을 무서워한다.',progress_state:'',entries:[],confirmed:false}});
 if(p.endsWith('/characters/random'))return send({name:'민수',description:'다정한 청년'});
 if(p.endsWith('/characters'))return send({characters:[hero]});
 if(p.endsWith('/characters/hero-1'))return send(hero);
 if(p.endsWith('/create')){sent=route.request().postDataJSON();return send({id:'created-book'});}
 if(p.endsWith('/jobs/created-book'))return send({id:'created-book',status:'queued',stage:'story',pct:0,log:[],queue:{ahead:0,minutes:1,line:'곧 시작해요'}});
 if(p.endsWith('/jobs/mine'))return send({jobs:[],cards:[]});
 if(p.endsWith('/runs'))return send({runs:[]});
 return send(ok([]));});
 return {get sent(){return sent;},get personal(){return personal;}};
}
const next=async(page:Page)=>page.getByRole('button',{name:'다음',exact:true}).filter({visible:true}).first().click();
async function finish(page:Page){await next(page);await expect(page).toHaveURL(/step=2/);await page.getByLabel('저작권 확인',{exact:true}).check();await page.getByRole('button',{name:'웹툰 만들기',exact:true}).filter({visible:true}).first().click();await expect(page).toHaveURL(/job=created-book/);}
const STORY='민수는 높은 곳을 무서워한다. 친구와 약속한 날, 다리 앞에 멈춰 서서 우회로를 찾기 시작한다.';
const DRAFT_DONE={status:'DONE',book:{name:'다리 너머의 약속',source:'extracted',fixed_settings:'민수는 높은 곳을 무서워한다.',progress_state:'',entries:[],confirmed:false}};
const advance=(page:Page)=>page.getByRole('button',{name:'다음',exact:true}).filter({visible:true}).first();
const attached=(page:Page)=>page.locator('.wt-lore-attached-list li');
// 붙인 설정집의 항목 내용 전부(여러 권이면 합쳐서)
const contents=(lb:any):string[]=>(lb.books?lb.books.flatMap((b:any)=>b.entries):lb.entries).map((e:any)=>e.content);
// 새 설정집은 빈 항목 하나로 시작한다 — 이름·내용을 적는다.
async function fillEntry(page:Page,title:string,content:string){
 await page.getByRole('textbox',{name:'항목 이름',exact:true}).last().fill(title);
 await page.getByRole('textbox',{name:`${title} 내용`,exact:true}).fill(content);
}
// 새 설정집 작성 → 이름·항목 → 확인하고 붙이기
async function writeBook(page:Page,name:string,fixed:string){
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.getByRole('button',{name:/새 설정집 작성/}).click();
 await page.getByRole('textbox',{name:'설정집 이름',exact:true}).fill(name);await fillEntry(page,'기본',fixed);
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();
}
// 새 설정집 작성 → AI로 만들기 → 적어 둔 이야기 가져오기 → 권리 확인 → 만들기
async function startExtract(page:Page){
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.getByRole('button',{name:/새 설정집 작성/}).click();
 await page.getByRole('button',{name:'AI로 만들기',exact:true}).click();await page.getByRole('button',{name:'적어 둔 이야기 가져오기'}).click();
 await page.getByLabel('입력한 글을 사용할 권리가 있어요.').check();await page.getByRole('button',{name:'AI로 설정집 만들기',exact:true}).click();
}

test('설정집 도움말(팝업), 직접 적어 붙이기·빼기',async({page},info)=>{
 await setup(page);await page.goto('/webtoon?view=create&step=1');
 await expect(page.getByRole('heading',{name:'어떤 이야기인가요?'})).toBeVisible();
 const help=page.getByRole('button',{name:'설정집이란?',exact:true});
 await help.click();await expect(help).toHaveAttribute('aria-expanded','true');
 await expect(page.getByRole('dialog',{name:'설정집이란?'})).toContainText('진행 상태');
 mkdirSync(resolve(base,'.impeccable/review'),{recursive:true});
 await page.addStyleTag({content:"nextjs-portal{display:none}"});
 await page.screenshot({path:resolve(base,`.impeccable/review/lore-help-${info.project.name}.png`),fullPage:true});
 await page.getByRole('dialog',{name:'설정집이란?'}).getByRole('button',{name:/닫기/}).first().click();await expect(help).toBeFocused();
 await writeBook(page,'다리 앞의 민수','주인공은 높은 곳을 무서워한다.');
 await expect(attached(page)).toHaveCount(1);
 await page.screenshot({path:resolve(base,`.impeccable/review/lore-attached-${info.project.name}.png`),fullPage:true});
 // 붙인 설정집 아래 설명 한 줄(세계 항목 내용) — 「고른 장르의 설정집」·「다시 붙이기」는 없다(2026-10-26)
 await expect(attached(page)).toContainText('주인공은 높은 곳을 무서워한다.');
 expect(await page.evaluate(()=>JSON.parse(sessionStorage.getItem('lore_wizard_draft')!).lorebook.confirmed)).toBe(true);
 await page.getByRole('button',{name:/설정집 빼기/}).click();await expect(attached(page)).toHaveCount(0);
 await expect(page.getByRole('button',{name:'방금 뺀 설정집 다시 붙이기'})).toHaveCount(0);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
test('붙인 설정집은 생성 요청에 들어가 내 내용 길로 가고, 빼면 남지 않음',async({page})=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=1');
 await writeBook(page,'다리 앞의 민수','주인공은 높은 곳을 무서워한다.');await finish(page);
 expect(server.sent.lorebook.confirmed).toBe(true);expect(contents(server.sent.lorebook)).toEqual(['주인공은 높은 곳을 무서워한다.']);expect(server.sent.lorebook.entries[0].keys).toEqual([]);
 // 확인하고 붙이면 내 설정집에도 저장된다.
 expect(server.personal.map((b:any)=>b.name)).toEqual(['다리 앞의 민수']);expect(server.sent.lorebook.library_id).toBe('my-1');
expect(server.sent.mode).toBe('own');
 // 만들기를 시작하면 초안이 비워진다 — 다시 붙였다가 빼고 만든다.
 await page.goto('/webtoon?view=create&step=1');await writeBook(page,'다리 앞의 민수','주인공은 높은 곳을 무서워한다.');await page.getByRole('button',{name:/설정집 빼기/}).click();await finish(page);
 expect(server.sent.lorebook).toBeNull();expect(server.sent.mode).toBe('quick');
});
test('장르를 고르면 그 장르 설정집이 붙고, 자동으로 돌리면 떨어지며 방식 선택은 남음',async({page})=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=1');
 await page.locator('.wt-create-genres').getByRole('button',{name:'무협',exact:true}).click();
 await expect(attached(page)).toHaveCount(1);await expect(attached(page)).not.toContainText('고른 장르의 설정집');
 await next(page);await expect(page).toHaveURL(/step=2/);await expect(page.getByRole('button',{name:/바로 만들기/})).toBeVisible();
 await page.getByRole('button',{name:/캐릭터 · 이야기/}).click();
 await page.locator('.wt-create-genres').getByRole('button',{name:'자동',exact:true}).click();await expect(attached(page)).toHaveCount(0);
 await page.locator('.wt-create-genres').getByRole('button',{name:'무협',exact:true}).click();await finish(page);
 expect(server.sent.lorebook.source).toBe('genre');expect(server.sent.mode).toBe('quick');
});
test('AI 초안은 검토 후 붙고, 원문을 바꾸면 다시 확인',async({page})=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=1');await page.locator('#wt-wiz-story').fill(STORY);
 await startExtract(page);
 await expect(page.getByRole('textbox',{name:'설정집 이름',exact:true})).toHaveValue('다리 너머의 약속');await expect(page.getByText('AI 초안',{exact:true})).toBeVisible();expect(server.sent).toBeNull();
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();await expect(advance(page)).toBeEnabled();
 await page.locator('#wt-wiz-story').fill('민수는 높은 곳을 무서워한다. 친구에게 무서운 이유를 털어놓고 함께 우회로를 걷는다.');
 await expect(attached(page)).toContainText('설정을 다시 확인해 주세요');await expect(advance(page)).toBeDisabled();
 await attached(page).first().getByRole('button').first().click();await page.getByRole('button',{name:'수정',exact:true}).click();await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await finish(page);expect(server.sent.mode).toBe('own');expect(server.sent.lorebook.source).toBe('extracted');
});
test('AI 초안 실패 안내 후 다시 시도할 수 있음',async({page},info)=>{
 await setup(page);let complete=false;
 await page.route('**/lorebooks/extract/extract-test',async route=>route.fulfill({contentType:'application/json',body:JSON.stringify(complete?{status:'ERROR',error:'설정을 정리하지 못했어요'}:{status:'RUNNING'})}));
 await page.goto('/webtoon?view=create&step=1');await page.locator('#wt-wiz-story').fill(STORY);await startExtract(page);
 const make=page.getByRole('button',{name:'AI로 설정집 만들기',exact:true});
 await expect(make).toBeDisabled();await expect(page.getByRole('status').filter({hasText:'설정집을 만드는 중'})).toBeVisible();
 await page.screenshot({path:resolve(base,`.impeccable/review/lore-loading-${info.project.name}.png`),fullPage:true});complete=true;
 await expect(page.getByRole('alert').filter({hasText:'설정을 정리하지 못했어요'})).toBeVisible();await expect(make).toBeEnabled();
 await page.screenshot({path:resolve(base,`.impeccable/review/lore-error-${info.project.name}.png`),fullPage:true});
});
test('설정집 여러 개 추가·개별 수정 후 모두 생성에 전달',async({page},info)=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=1');
 await writeBook(page,'밤의 도시','도시에는 밤마다 비가 온다.');await writeBook(page,'민수의 집','민수는 고양이와 산다.');
 await expect(attached(page)).toHaveCount(2);await expect(advance(page)).toBeEnabled();
 await page.reload();await expect(attached(page)).toHaveCount(2);
 await attached(page).first().getByRole('button').first().click();await page.getByRole('button',{name:'수정',exact:true}).click();
 await page.getByRole('textbox',{name:'기본 내용',exact:true}).fill('도시에는 밤마다 문이 열린다.');await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await expect(attached(page)).toHaveCount(2);
 await page.addStyleTag({content:'nextjs-portal{display:none}'});await page.screenshot({path:resolve(base,`.impeccable/review/lore-multiple-${info.project.name}.png`),fullPage:true});
 await finish(page);expect(server.sent.lorebook.books).toHaveLength(2);expect(contents(server.sent.lorebook)).toEqual(['도시에는 밤마다 문이 열린다.','민수는 고양이와 산다.']);
});
test('AI 초안 만드는 중과 검토 중에는 단계 이동·원문 변경을 막고 브라우저 뒤로가기도 보존',async({page})=>{
 await setup(page);let complete=false;
 await page.route('**/lorebooks/extract/extract-test',async route=>route.fulfill({contentType:'application/json',body:JSON.stringify(complete?DRAFT_DONE:{status:'RUNNING'})}));
 await page.goto('/webtoon?view=create&step=2');await page.getByRole('button',{name:/캐릭터 · 이야기/}).click();await expect(page).toHaveURL(/step=1/);
 const source=page.locator('#wt-wiz-story');await source.fill(STORY);await startExtract(page);
 await expect(advance(page)).toBeDisabled();await expect(source).toBeDisabled();await expect(page.getByRole('button',{name:'설정집 닫기'})).toBeDisabled();
 await page.goBack();await expect(page).toHaveURL(/step=1/);await expect(page.getByRole('status').filter({hasText:'설정집을 만드는 중'})).toBeVisible();
 complete=true;await expect(page.getByRole('textbox',{name:'설정집 이름',exact:true})).toHaveValue('다리 너머의 약속');await expect(advance(page)).toBeDisabled();await expect(source).toBeDisabled();
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();await expect(advance(page)).toBeEnabled();await expect(source).toBeEnabled();await expect(attached(page)).toHaveCount(1);
 await page.goBack();await expect(page).toHaveURL(/step=2/);
});
test('설정집 수정은 붙이거나 닫기 전까지 진행할 수 없고 닫으면 붙인 내용 유지',async({page})=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=1');
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.getByRole('button',{name:/새 설정집 작성/}).click();
 await expect(advance(page)).toBeDisabled();
 await page.getByRole('textbox',{name:'설정집 이름',exact:true}).fill('다리 앞의 민수');await fillEntry(page,'기본','주인공은 높은 곳을 무서워한다.');
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();await expect(advance(page)).toBeEnabled();
 await attached(page).first().getByRole('button').first().click();await page.getByRole('button',{name:'수정',exact:true}).click();
 await page.getByRole('textbox',{name:'기본 내용',exact:true}).fill('취소할 변경');await expect(advance(page)).toBeDisabled();
 await page.getByRole('button',{name:'설정집 닫기',exact:true}).click();await expect(advance(page)).toBeEnabled();
 await finish(page);expect(contents(server.sent.lorebook)).toEqual(['주인공은 높은 곳을 무서워한다.']);
});
test('내 설정집에 저장하고 목록에서 다시 불러오기',async({page})=>{
 await setup(page);await page.goto('/webtoon?view=create&step=1');
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await expect(page.getByText('저장한 설정집이 없어요')).toBeVisible();
 await page.getByRole('button',{name:/새 설정집 작성/}).click();
 await page.getByRole('textbox',{name:'설정집 이름',exact:true}).fill('내 도시 설정');
 await fillEntry(page,'도시','도시의 시계는 밤에 멈춘다.');
 await page.getByRole('button',{name:'내 설정집에 저장',exact:true}).click();
 await expect(page.locator('.wt-lore-read')).toContainText('도시의 시계는 밤에 멈춘다.');
 await page.getByRole('button',{name:'목록으로',exact:true}).click();
 await expect(page.locator('.wt-lore-personal button')).toHaveCount(1);
 await page.locator('.wt-lore-personal button').click();
 await expect(page.locator('.wt-lore-read')).toContainText('도시의 시계는 밤에 멈춘다.');
 await expect(page.locator('.wt-lore-panel').getByText(/공개|비공개/)).toHaveCount(0);
 await page.getByRole('button',{name:'이 설정집 사용'}).click();
 await expect(page.locator('.wt-lore-attached-list')).toContainText('내 도시 설정');
});
test('아무 글·설정집 없이 캐릭터만 고르면 다음으로 이동',async({page})=>{
 const server=await setup(page,true);await page.goto('/webtoon?view=create&step=1');
 await expect(page.getByRole('button',{name:'다음',exact:true}).filter({visible:true}).first()).toBeDisabled();
 await page.getByRole('button',{name:'민수',exact:true}).click();
 await expect(page.locator('#wt-wiz-story')).toHaveValue('');await expect(page.getByRole('button',{name:'+ 설정집',exact:true})).toBeVisible();
 expect(await page.evaluate(()=>JSON.parse(sessionStorage.getItem('lore_wizard_draft')!).name)).toBe('민수');
 await finish(page);expect(server.sent.lorebook).toBeNull();expect(server.sent.story).toBe('');expect(server.sent.mode).toBe('quick');
});
