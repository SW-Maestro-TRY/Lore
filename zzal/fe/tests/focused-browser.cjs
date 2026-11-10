/** Current /zzal route, real React/HMR; every API is a strict local fixture. No service writes. */
const {chromium}=require('@playwright/test');
const assert=require('node:assert/strict');const fs=require('node:fs');
const base=process.env.FOCUSED_TEST_URL||'http://localhost:3203';
const image=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII=','base64');
(async()=>{const browser=await chromium.launch();const results=[];try{
for(const [width,height] of [[320,568],[390,844],[430,932]]){
 const context=await browser.newContext({viewport:{width,height},hasTouch:true,serviceWorkers:'block'}),page=await context.newPage();const errors=[],unexpected=[],events=[],calls=[];let careFail=false,reviewFail=false,submitted=false,meetingFail=true;let meetingState='DISCOVERED';
 page.setDefaultTimeout(15000);page.setDefaultNavigationTimeout(45000);
 let pet={petId:999999,name:'Fixture',phase:'ALIVE',ready:true,daysTogether:1,serverNow:'2026-10-22T09:00:00Z',hatchedAt:'2026-10-22T08:00:00Z',clock:{clockStartedAt:'2026-10-22T09:00:00Z',sleeping:false,canSleep:true,canWake:false},gauges:{fullness:2,happiness:2,clean:1,trash:3},food:{count:30},today:{snacks:0,bathDone:false},trip:null,sick:null,background:'room',attendanceRewards:{attendanceDay:1,ownedRewards:[],background:{selectedKey:'room',owned:[{key:'fixture',url:'/fixture/background.png'}],preview:null}},firstIntro:{version:4,key:'COMPLETE',completed:true,active:false},motions:['base','eat','joy','sad','sick','pet','hello','sleep'].map((key,i)=>({seq:i+1,key,label:key,unlocked:true,basicImageKey:`/fixture/${key}.png`}))};
 let game={playing:false,gameId:null,kind:null,round:null,hits:0,rounds:5,winAt:3,remainingToday:3,justUnlocked:[]};
 const chat=()=>({stageSpeeches:[],openSlot:'CONTINUOUS',calls:[],memories:[],session:{id:77,slot:'CONTINUOUS',kind:'CONTINUOUS',round:0,closed:false},turns:[],stageSpeech:null});
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',async route=>{const req=route.request(),url=new URL(req.url()),p=url.pathname,body=req.postData()?req.postDataJSON():null;
 if(p.startsWith('/fixture/'))return route.fulfill({contentType:'image/png',body:image});
 if(!p.startsWith('/api/')){if(url.origin===base)return route.continue();return route.fulfill({status:204,body:''});}
 calls.push({method:req.method(),path:p});let data;
 const fail=()=>route.fulfill({status:409,json:{success:false,data:null,error:{code:'ZZAL_CARE_NOT_NEEDED',message:'Fixture rejected'}}});
 if(p==='/api/v1/events'){events.push(...body.events);data={status:'persisted',accepted_event_ids:body.events.map(e=>e.event_id),rejected_event_ids:[]};}
 else if(p==='/api/v1/users/me')data={userId:999999,email:'fixture@example.invalid',role:'USER'};
 else if(p==='/api/zzal/v1/me/pets')data=[pet];
 else if(p==='/api/zzal/v1/me/pets/999999')data=pet;
 else if(p.endsWith('/games/current'))data=game;
 else if(p.endsWith('/first-meeting')){
   if(req.method()==='POST'&&body.action==='NEXT'&&meetingFail){meetingFail=false;return route.fulfill({status:503,json:{success:false,data:null,error:{code:'TEST_UNAVAILABLE',message:'Fixture unavailable'}}});}
   if(body?.action==='NEXT')meetingState='ADDRESS';if(body?.action==='DEFER_ADDRESS')meetingState='READY_TO_ENTER';if(body?.action==='ENTER_ROOM')meetingState='COMPLETE';
   data={version:1,state:meetingState,callMe:null,addressStatus:meetingState==='DISCOVERED'||meetingState==='ADDRESS'?'UNPROVIDED':'DEFERRED',discoveryLine:'Hello',discoveryStatus:'READY',discoverySource:'FALLBACK',greetingLine:'Welcome',greetingRetryAllowed:false};
 }
 else if(p.endsWith('/persona'))data={authoredPersona:null,callMe:'Friend',addressStatus:'EXPLICIT',personaSourceHash:'a'.repeat(64)};
 else if(p.endsWith('/album'))data={motions:[],postcards:[]};
 else if(p.endsWith('/presence'))data={outcome:'RESTORED',chat:chat()};
 else if(p.endsWith('/chat/history'))data={turns:[],nextBeforeId:null};
 else if(p.endsWith('/chat/history/legacy'))data={calls:[],nextBeforeId:null};
 else if(p.endsWith('/chat'))data=chat();
 else if(p.endsWith('/care')){if(careFail)return fail();data={...pet,careReceipt:{requestId:body.requestId,action:body.action,status:'APPLIED'},motionPlayback:{situationKey:body.action,basicSemantic:'base',sourceKind:'BASIC'}};}
 else if(p.endsWith('/sleep')||p.endsWith('/wake')){const sleeping=p.endsWith('/sleep');pet={...pet,clock:{...pet.clock,sleeping,canSleep:!sleeping,canWake:sleeping}};data=pet;}
 else if(p.endsWith('/games')&&req.method()==='POST'){game={...game,playing:true,gameId:9,round:0,hits:0};data=game;}
 else if(p.endsWith('/guess')){const round=game.round,hits=game.hits+1,finished=hits===3;data={gameId:9,round,pick:body.pick,answer:body.pick,hit:true,hits,finished,win:finished?true:null,nextRound:finished?null:round+1,rounds:5,winAt:3,remainingToday:2,justUnlocked:[]};game={...game,hits,round:data.nextRound,playing:!finished};}
 else if(p.endsWith('/feedback')){if(req.method()==='POST'){if(reviewFail)return fail();submitted=true;}data={submitted,rating:submitted?5:null,tags:[],text:null,createdAt:null};}
 else if(p==='/api/zzal/v1/dev/ping')data={enabled:false};
 else if(p.endsWith('/diary'))data={entries:[{id:1,writtenAt:'2026-10-22T08:00:00Z',body:'Private fixture journal',preview:'Journal',language:'ko',readAt:null,kind:'CHARACTER_DIARY'}],nextBeforeId:null,unreadCount:1,pending:false};
 else if(p.endsWith('/diary/1/read'))data={id:1,writtenAt:'2026-10-22T08:00:00Z',body:'Private fixture journal',preview:'Journal',language:'ko',readAt:'2026-10-22T09:00:00Z',kind:'CHARACTER_DIARY'};
 else if(p.endsWith('/background')){pet={...pet,background:body.key,attendanceRewards:{...pet.attendanceRewards,background:{...pet.attendanceRewards.background,selectedKey:body.key}}};data=pet;}
 else if(p==='/api/zzal/v1/me/inquiries')data={id:1,createdAt:'2026-10-22T09:00:00Z'};
 else if(p.endsWith('/credits/me'))data={balance:0};
 else if(p.includes('/announcements'))data=[];
 else{unexpected.push({method:req.method(),path:p});return route.fulfill({status:501,json:{success:false,error:{code:'UNEXPECTED_FIXTURE_REQUEST',message:p}}});}
 return route.fulfill({json:{success:true,data,error:null,message:null}});
 });
 console.log('START',width);await page.goto(base+'/zzal?lang=ko',{waitUntil:'domcontentloaded'});await page.locator('[data-action="meeting-next"]').click();await page.getByRole('alert').waitFor();await page.locator('[data-action="meeting-next"]').click();await page.locator('[data-action="meeting-later"]').click();await page.locator('[data-action="meeting-enter"]').click();await page.locator('[data-focused-room]').waitFor();console.log('ROOM',width);
 const group=async key=>{await page.locator(`[data-fr-tile="${key}"]`).click();};
 const action=async key=>page.locator(`[data-fr-care-choice][data-fr-action="${key}"]`).click();
 await group('table');await action('FEED');await page.locator('[data-fr-tile="table"]').waitFor();
 careFail=true;await group('table');await action('SNACK');await page.locator('[data-fr-tile="table"]').waitFor();careFail=false;
 console.log('CARE',width);await group('bed');await action('REST');await page.waitForFunction(()=>document.querySelector('[data-fr-tile="bed"]'));
 await group('bed');await action('REST');await page.locator('[data-fr-tile="play"]').waitFor();
 console.log('REST',width);await group('play');await action('GAME');for(let i=0;i<3;i++){await page.locator('[data-fr-side="LEFT"]').click();}await page.locator('[data-fr-tile="album"]').waitFor();
 console.log('GAME',width);await group('album');await action('collection');await page.locator('[data-fr-album]').waitFor();await page.keyboard.press('Escape');
 console.log('ALBUM',width);await page.getByRole('button',{name:'메뉴',exact:true}).click();await page.locator('[data-fr-action="feedback-menu"]').click();await page.locator('.fr-rating button').nth(4).click();
 reviewFail=true;await page.locator('[data-fr-action="review-submit"]').click();await page.locator('[data-fr-review] [role=alert]').waitFor();reviewFail=false;await page.locator('[data-fr-action="review-submit"]').click();await page.locator('[data-fr-review]').waitFor({state:'detached'});
 await page.getByRole('button',{name:'메뉴',exact:true}).click();await page.locator('[data-fr-action="attendance-menu"]').click();await page.locator('[data-fr-attendance]').waitFor();await page.keyboard.press('Escape');
 await group('album');await action('journal');await page.locator('[data-part="diary-list"] button').click();await page.locator('[data-part="diary-body"]').waitFor();await page.locator('[data-part="rewards-journal"] header button').click();
 await group('album');await action('background');await page.locator('[data-background="fixture"]').click();await page.locator('[data-action="apply-background"]').click();await page.locator('[data-part="background-room"] header button').click();
 await page.getByRole('button',{name:'메뉴',exact:true}).click();await page.locator('[data-fr-action="inquiry-menu"]').click();await page.locator('[data-fr-inquiry-body]').fill('Private inquiry fixture');await page.locator('[data-fr-inquiry-submit]').click();await page.locator('[data-fr-inquiry-saved]').waitFor();await page.locator('[data-fr-inquiry] header button').click();
 console.log('EXTRA',width);
 await page.waitForTimeout(5500);
 const observed=(action,outcome)=>events.some(e=>e.name==='zzal_action_result'&&e.props.action===action&&e.props.outcome===outcome);
 for(const pair of [['next','uncertain'],['next','success'],['defer_address','success'],['enter_room','success'],['feed_rice','success'],['feed_snack','failure'],['sleep','success'],['wake','success'],['game_start','success'],['game_guess','success'],['review_submit','failure'],['review_submit','success'],['background_apply','success'],['inquiry_submit','success']])assert.ok(observed(...pair),`missing ${pair}`);
 assert.ok(events.some(e=>e.name==='zzal_ui_shown'&&e.props.action==='collection'));
 assert.ok(events.filter(e=>e.name==='zzal_user_action').every(e=>e.props.user_action===true&&e.actor_state==='authenticated'&&e.actor_id===999999));
 assert.ok(!JSON.stringify(events).includes('Private inquiry fixture'));assert.ok(!JSON.stringify(events).includes('Private fixture journal'));
 assert.ok(events.some(e=>e.name==='zzal_user_action'&&e.props.action==='journal_open'));
 assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);
 results.push({width,height,hasTouch:true,passed:true,events:events.length,apiCalls:calls.length,realApiCalls:0,pageErrors:errors,unexpected});await context.close();console.log('PASS focused browser',width);
}
if(process.env.FOCUSED_TEST_REPORT)fs.writeFileSync(process.env.FOCUSED_TEST_REPORT,JSON.stringify(results,null,2));
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
