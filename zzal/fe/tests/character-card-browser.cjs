/** Character card on the current /zzal route (not the scrapbook skin). Every API is a strict local fixture; no service writes. */
const {chromium}=require('@playwright/test');
const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
const base=process.env.FOCUSED_TEST_URL||'http://localhost:3203';
const shots=process.env.CARD_SHOTS_DIR||path.join(__dirname,'.results');fs.mkdirSync(shots,{recursive:true});
const sprite=fs.readFileSync(path.resolve(__dirname,'../../../apps/web/public/zzal-entry/romi-sample/basic8/base.webp'));
const authored={version:1,fields:[['world',['판타지'],'바닷가 작은 마을'],['job',['가게 운영'],'조개껍데기 가게 점원'],['persona',['다정함','느긋함'],''],['likes',['간식'],'햇볕에 데운 돌멩이'],['dislikes',['벌레'],''],['extra',[],'']].map(([fieldId,chips,rawText])=>({fieldId,chips,rawText,skipped:!chips.length&&!rawText}))};

async function room(browser,{width,height,returning=false}){
 const context=await browser.newContext({viewport:{width,height},hasTouch:true,serviceWorkers:'block'}),page=await context.newPage();
 const s={errors:[],unexpected:[],renames:[],guide:[],hash:'a'.repeat(64),callMe:'주인님',renameDelay:0};
 page.setDefaultTimeout(15000);page.setDefaultNavigationTimeout(45000);
 s.pet={petId:999999,name:'여울',phase:'ALIVE',ready:true,daysTogether:1,serverNow:'2026-10-22T09:00:00Z',hatchedAt:'2026-10-22T08:00:00Z',clock:{clockStartedAt:'2026-10-22T09:00:00Z',sleeping:false,canSleep:true,canWake:false},gauges:{fullness:2,happiness:2,clean:1,trash:3},food:{count:30},today:{snacks:0,bathDone:false},trip:null,sick:null,background:'room',attendanceRewards:{attendanceDay:1,ownedRewards:[],background:{selectedKey:'room',owned:[],preview:null}},firstIntro:returning?{version:3,key:'COMPLETE',completed:true,active:false}:{version:4,key:'COMPLETE',completed:true,active:false},motions:['base','eat','joy','sad','sick','pet','hello','sleep'].map((key,i)=>({seq:i+1,key,label:key,unlocked:true,basicImageKey:`/fixture/${key}.webp`}))};
 const chat=()=>({stageSpeeches:[],openSlot:'CONTINUOUS',calls:[],memories:[],session:{id:77,slot:'CONTINUOUS',kind:'CONTINUOUS',round:0,closed:false},turns:[],stageSpeech:null});
 page.on('pageerror',e=>s.errors.push(e.message));
 await page.route('**/*',async route=>{const req=route.request(),url=new URL(req.url()),p=url.pathname,body=req.postData()?req.postDataJSON():null;
  if(p.startsWith('/fixture/'))return route.fulfill({contentType:'image/webp',body:sprite});
  if(!p.startsWith('/api/')){if(url.origin===base)return route.continue();return route.fulfill({status:204,body:''});}
  let data;
  if(p==='/api/v1/events')data={status:'persisted',accepted_event_ids:body.events.map(e=>e.event_id),rejected_event_ids:[]};
  else if(p==='/api/v1/users/me')data={userId:999999,email:'fixture@example.invalid',role:'USER'};
  else if(p==='/api/zzal/v1/me/pets')data=[s.pet];
  else if(p==='/api/zzal/v1/me/pets/999999')data=s.pet;
  else if(p.endsWith('/name')&&req.method()==='PATCH'){s.renames.push(body);if(s.renameDelay)await new Promise(r=>setTimeout(r,s.renameDelay));s.pet={...s.pet,name:body.name.trim()};data=s.pet;}
  else if(p.endsWith('/games/current'))data={playing:false,gameId:null,kind:null,round:null,hits:0,rounds:5,winAt:3,remainingToday:3,justUnlocked:[]};
  else if(p.endsWith('/first-meeting'))data={version:1,state:'COMPLETE',callMe:s.callMe,addressStatus:'EXPLICIT',discoveryLine:'Hello',discoveryStatus:'READY',discoverySource:'FALLBACK',greetingLine:'Welcome',greetingRetryAllowed:false};
  else if(p.endsWith('/persona'))data={authoredPersona:authored,callMe:s.callMe,addressStatus:'EXPLICIT',personaSourceHash:s.hash};
  else if(p.endsWith('/returning-guide')){if(req.method()==='POST')s.guide.push(body);data={eligible:true,completed:!!body?.complete,step:5};}
  else if(p.endsWith('/album'))data={motions:[],postcards:[]};
  else if(p.endsWith('/presence'))data={outcome:'RESTORED',chat:chat()};
  else if(p.endsWith('/chat/history'))data={turns:[],nextBeforeId:null};
  else if(p.endsWith('/chat/history/legacy'))data={calls:[],nextBeforeId:null};
  else if(p.endsWith('/chat'))data=chat();
  else if(p==='/api/zzal/v1/dev/ping')data={enabled:false};
  else if(p.endsWith('/diary'))data={entries:[],nextBeforeId:null,unreadCount:0,pending:false};
  else if(p.endsWith('/credits/me'))data={balance:0};
  else if(p.includes('/announcements'))data=[];
  else if(p.endsWith('/feedback'))data={submitted:true,rating:5,tags:[],text:null,createdAt:null};
  else{s.unexpected.push({method:req.method(),path:p});return route.fulfill({status:501,json:{success:false,error:{code:'UNEXPECTED_FIXTURE_REQUEST',message:p}}});}
  return route.fulfill({json:{success:true,data,error:null,message:null}});
 });
 await page.goto(base+'/zzal?lang=ko',{waitUntil:'domcontentloaded'});
 return {context,page,s};
}
/** Layout numbers that must hold on every scene. */
async function measure(page,root){
 return page.evaluate(sel=>{const card=document.querySelector(sel);const pens=[...card.querySelectorAll('[data-card-edit]')].map(b=>{const r=b.getBoundingClientRect();return [r.width,r.height]});const chips=card.querySelector('[data-card-chips]');
  return {pens,overflowX:Math.max(0,document.documentElement.scrollWidth-document.documentElement.clientWidth,document.body.scrollWidth-window.innerWidth),chipOverflow:chips?chips.scrollWidth-chips.clientWidth:0,cardOverflow:card.scrollWidth-card.clientWidth,savedCopy:(document.body.innerText.match(/저장했어요/g)||[]).length}},root);
}
function assertLayout(m,pens=2){
 assert.equal(m.pens.length,pens,'pencil count');for(const [w,h] of m.pens){assert.ok(w>=44&&h>=44,`pencil ${w}x${h}`);}
 assert.equal(m.overflowX,0,'page horizontal overflow');assert.equal(m.chipOverflow,0,'chip row overflow');assert.equal(m.cardOverflow,0,'card overflow');assert.equal(m.savedCopy,0,'"저장했어요" copy');
}
const shot=(page,name)=>page.screenshot({path:path.join(shots,`${name}.png`)});

(async()=>{const browser=await chromium.launch();const report=[];try{
 for(const [width,height] of [[390,844],[320,568]]){
  const {context,page,s}=await room(browser,{width,height});
  await page.locator('[data-focused-room]').waitFor();
  await page.getByRole('button',{name:'메뉴',exact:true}).click();await page.locator('[data-fr-action="info-menu"]').click();
  // This must be the new card inside the focused-room info sheet, not the old skin or the old editor.
  const card='[data-fr-info] [data-card]';await page.locator(card).waitFor();
  assert.equal(await page.locator('[data-part="persona-editor"]').count(),0,'old PersonaEditor gone');
  assert.equal(await page.locator('[data-fr-info] h3').count(),0,'duplicate sheet name heading removed');
  await page.locator(`${card} [data-card-back]`).waitFor();await page.locator(`${card} [data-card-art="cropped"] img`).waitFor();
  assert.equal(await page.locator(`${card} [data-card-name]`).innerText(),'여울');
  assert.equal(await page.locator(`${card} [data-card-callme]`).innerText(),'주인님');
  assert.deepEqual(await page.locator(`${card} .zcc-chip`).allInnerTexts(),['판타지','가게 운영','다정함','느긋함']);
  assert.equal(await page.locator(`${card} [data-action="persona-edit"]`).innerText(),'상세 설정 고치기');
  const scenes={};scenes.base=await measure(page,card);assertLayout(scenes.base);await shot(page,`${width}-01-base`);
  if(width===390){
   await page.locator(`${card} [data-card-edit="name"]`).click();const input=page.locator(`${card} [data-card-input="name"]`);
   assert.equal(await input.evaluate(el=>el===document.activeElement),true,'focus moves into the input');
   await input.fill('바다여울');assert.equal(await page.locator(`${card} [data-card-count]`).innerText(),'4/12');
   assert.equal(await page.locator(`${card} [data-card-edit="callMe"]`).isDisabled(),true,'second pencil blocked while dirty');
   scenes.editing=await measure(page,card);assertLayout(scenes.editing,1);await shot(page,`${width}-02-editing`);
   await input.fill('');await page.locator(`${card} [data-card-error]`).waitFor();
   assert.equal(await page.locator(`${card} [data-card-error]`).innerText(),'이름을 한 글자 이상 적어 주세요.');
   assert.equal(await page.locator(`${card} [data-card-save]`).isDisabled(),true);
   scenes.error=await measure(page,card);assertLayout(scenes.error,1);await shot(page,`${width}-03-error`);
   // Save: rapid double tap sends one request; header and card agree afterwards; focus returns to the pencil.
   await input.fill('바다여울');s.renameDelay=400;const save=page.locator(`${card} [data-card-save]`);
   await save.evaluate(el=>{el.click();el.click();});await page.locator(`${card} [data-card-name]`).waitFor();s.renameDelay=0;
   assert.equal(s.renames.length,1,'one PATCH for a double tap');assert.deepEqual(s.renames[0],{name:'바다여울'});
   const header=await page.locator('.fr-header h1').innerText(),cardName=await page.locator(`${card} [data-card-name]`).innerText();
   assert.equal(cardName,'바다여울');assert.equal(header,cardName,'header name equals card name');
   assert.equal(await page.locator(`${card} [data-card-edit="name"]`).evaluate(el=>el===document.activeElement),true,'focus returns to the pencil');
   assert.equal(await page.locator(`${card} [role=alert],${card} [role=status]`).count(),0,'no message after save');
   scenes.saved=await measure(page,card);assertLayout(scenes.saved);await shot(page,`${width}-04-saved`);
  }
  assert.deepEqual(s.errors,[]);assert.deepEqual(s.unexpected,[]);
  report.push({width,height,scenes,renames:s.renames.length});await context.close();console.log('PASS character card',width);
 }
 // Returning-guide settings sheet shows the same card; "상세 설정 고치기" -> "이 설정으로 시작하기" still completes the guide.
 {const {context,page,s}=await room(browser,{width:390,height:844,returning:true});
  await page.locator('[data-returning-edit]').click();const card='[data-returning-settings] [data-card]';await page.locator(`${card} [data-card-back]`).waitFor();
  const m=await measure(page,card);assertLayout(m);await shot(page,'390-05-returning');
  await page.locator(`${card} [data-action="persona-edit"]`).click();
  for(let i=0;i<12&&await page.locator('[data-action="persona-settings-skip"]').count();i++)await page.locator('[data-action="persona-settings-skip"]').click();
  const confirm=page.locator('[data-action="persona-settings-next"]');assert.equal(await confirm.innerText(),'이 설정으로 시작하기');await confirm.click();
  await page.locator('[data-returning-settings]').waitFor({state:'detached'});
  assert.deepEqual(s.guide.at(-1),{step:5,complete:true,expectedPersonaHash:s.hash},'guide completed with the current hash');
  assert.deepEqual(s.errors,[]);assert.deepEqual(s.unexpected,[]);report.push({width:390,returning:true,layout:m});await context.close();console.log('PASS returning sheet card');
 }
 console.log(JSON.stringify(report));
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
