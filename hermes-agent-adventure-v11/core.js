(()=>{'use strict';
const $=id=>document.getElementById(id), W=1600,H=900,T=32,COLS=50,ROWS=28, saveKey='hermes_rpg_v11';
const key=(x,y)=>`${x},${y}`, freshSave=()=>({x:25,y:16,dir:'down',exp:0,level:1,quest:0,unlocked:['agent'],ach:[],inventory:['新手手冊'],explored:{},battle:0,bossDown:false,sound:true});
let save=freshSave();try{save={...save,...JSON.parse(localStorage.getItem(saveKey)||'{}')}}catch(e){}
const persist=()=>{try{localStorage.setItem(saveKey,JSON.stringify(save))}catch(e){}};
const state={started:false,moving:false,fromX:save.x,fromY:save.y,toX:save.x,toY:save.y,moveT:0,dir:save.dir,near:null,dialogue:null,dialoguePage:0,lastArea:'中央廣場',audio:null};
const palette={grass:'#74b96a',grass2:'#67a95e',path:'#d9c493',path2:'#c2a873',water:'#62cae0',water2:'#44b4cf'};
const terrain=Array.from({length:ROWS},()=>Array(COLS).fill('g')),blocked=new Set();
function fill(x,y,w,h,type,block=false){for(let yy=y;yy<y+h;yy++)for(let xx=x;xx<x+w;xx++){if(terrain[yy]?.[xx]!=null)terrain[yy][xx]=type;if(block)blocked.add(key(xx,yy))}}
for(let x=0;x<COLS;x++){blocked.add(key(x,0));blocked.add(key(x,ROWS-1))}for(let y=0;y<ROWS;y++){blocked.add(key(0,y));blocked.add(key(COLS-1,y))}
fill(0,14,COLS,5,'p');fill(23,0,6,ROWS,'p');fill(15,10,20,12,'p');fill(0,3,10,8,'w',true);fill(36,22,12,5,'w',true);fill(22,25,10,3,'w',true);
const buildings=[{id:'lab',name:'AGENT 研究所',x:15,y:3,w:8,h:6,roof:'#4778e9',door:[19,9],label:'培養你的 AI 團隊'},{id:'n8n',name:'n8n 自動化工坊',x:29,y:4,w:8,h:6,roof:'#ef8a43',door:[33,10],label:'串接一切・讓工作自動發生'},{id:'studio',name:'內容工作室',x:8,y:18,w:9,h:6,roof:'#dc6988',door:[12,24],label:'寫下想法・讓世界看見'},{id:'tower',name:'數據塔',x:39,y:16,w:5,h:7,roof:'#509e86',door:[41,23],label:'用數據看見成長'}];
buildings.forEach(b=>{for(let yy=b.y;yy<b.y+b.h;yy++)for(let xx=b.x;xx<b.x+b.w;xx++)blocked.add(key(xx,yy));blocked.delete(key(...b.door))});
const trees=[[2,13],[4,12],[7,13],[9,12],[12,11],[14,12],[42,3],[44,4],[46,5],[47,7],[3,20],[5,22],[18,24],[20,25],[31,24],[35,23],[45,20],[47,19],[36,2],[39,2],[13,4],[11,5],[6,2],[2,24],[33,2]];trees.forEach(([x,y])=>blocked.add(key(x,y)));
const fences=[];for(let x=1;x<COLS-1;x++)if(![4,5,6,24,25,26,45,46].includes(x)){fences.push([x,1],[x,ROWS-2])}for(let y=2;y<ROWS-2;y++)if(![14,15,16,17,18].includes(y)){fences.push([1,y],[COLS-2,y])}fences.forEach(([x,y])=>blocked.add(key(x,y)));
const signs=[{x:12,y:15,t:'AI 自媒體城'},{x:30,y:13,t:'連接工具・設計流程'},{x:8,y:16,t:'更好的創作者從這裡出發'}];
const npc={
 guide:{name:'小玄導師',x:25,y:18,home:[25,18],radius:2,style:{hair:'#18213a',outfit:'#21365d',accent:'#78c6ff',glasses:true},unlock:['agent'],dialog:[['你真的懂 Agent 是什麼嗎？','Agent 不是「再問 AI 一題」，而是「交代一個目標，讓它自己把工作往下做」。','teach'],['你每天找題目、查資料、寫稿、存檔、通知，其實是在一個人扮演整間公司。','主線第一站：去北邊找顧問博士，先搞懂 ChatGPT 跟 Agent 差在哪。','point']]},
 professor:{name:'顧問博士',x:18,y:11,home:[18,11],radius:1,style:{hair:'#d9e2e8',outfit:'#f1f4f4',accent:'#64727c',glasses:true},unlock:['chatgpt'],dialog:[['ChatGPT 像很聰明的顧問。','你問，它回答；你再問，它再回答。這很強，但下一步通常還是你自己推。','calm'],['Agent 更像會做事的員工。','它能記住工作方式、使用工具、照排程啟動，完成後再回報。去旁邊找 Hermes 隊長。','happy']]},
 hermes:{name:'Hermes 隊長',x:21,y:11,home:[21,11],radius:1,style:{hair:'#244b8f',outfit:'#386ad8',accent:'#6fe3ff',robot:true},unlock:['hermes','skill'],dialog:[['把我想成 AI 員工。','我負責思考、研究、規劃、產生內容，也能把常用 SOP 變成 Skills。','confident'],['但「會思考」不等於所有工作都要我親手搬。','固定、重複、需要穩定串接的工作，交給 n8n。去東北方找工程師。','point']]},
 engineer:{name:'n8n 工程師',x:34,y:12,home:[34,12],radius:2,style:{hair:'#2a2625',outfit:'#dc7337',accent:'#ffb869',goggles:true},unlock:['n8n','workflow'],dialog:[['Hermes 負責「想」，我負責「送」。','例如 Hermes 選題後，我把結果送進 Notion、Drive、Gmail、資料庫或其他工具。','teach'],['所以 Hermes 是大腦，n8n 是輸送帶。','兩者合作，你才有一套會自己往下跑的 AI 工作系統。下一站去內容工作室找阿莓。','happy']]},
 creator:{name:'阿莓創作者',x:18,y:22,home:[18,22],radius:1,style:{hair:'#ef7fa5',outfit:'#d95582',accent:'#ffd0df'},unlock:['content'],dialog:[['我每天都在找新聞、想主題、查資料、寫稿、改稿、整理素材。','問題不是我不會用 AI，而是每一段還是要我自己推。','worried'],['附近有一隻「重複工作怪」。','幫我把工作分回 Hermes 和 n8n，讓重複工作不要再吃掉我的時間！','alert']]},
 analyst:{name:'數據研究員',x:37,y:23,home:[37,23],radius:1,style:{hair:'#17222e',outfit:'#3c7f69',accent:'#a4f3c9',glasses:true},unlock:['metrics'],dialog:[['自動化不是發越多越好。','真正重要的是：哪些題目有人分享？什麼 Hook 有效？什麼時間比較好？','teach'],['把成效送回 Agent，它才能提出下一輪策略。','現在你已經理解「目標 → 思考 → 串接 → 執行 → 回饋」。最後去課程之門。','happy']]},
 boss:{name:'重複工作怪',x:20,y:22,home:[20,22],radius:0,style:{hair:'#5e315f',outfit:'#7d3c7e',accent:'#ff7ca4',monster:true},unlock:[],dialog:[['每件事都自己做！每一段都自己搬！','想通過，就把工作分回正確角色！','angry']]}
};
Object.values(npc).forEach(n=>Object.assign(n,{fx:n.x,fy:n.y,tx:n.x,ty:n.y,moveT:1,dir:'down',nextMove:1000+Math.random()*1500,step:0}));
const dex=[['agent','AI Agent','接到目標後，可以規劃、使用工具、持續把工作往下做。','🤖'],['chatgpt','ChatGPT','像聰明顧問，擅長回答、分析與創作。','💬'],['hermes','Hermes Agent','像 AI 員工，負責思考、研究、規劃與執行。','⚕'],['n8n','n8n','像自動化輸送帶，負責固定串接與資料搬運。','🔗'],['workflow','工作流','把一件事拆成可重複、可交接、可驗收的步驟。','🧩'],['skill','Skill','把常用 SOP 封裝成 Hermes 可重複使用的能力。','🛠'],['content','內容系統','把研究、選題、腳本、文案串成一條內容生產線。','✍️'],['metrics','數據回饋','把成效送回 Agent，讓下一輪策略更好。','📊']];
const questSteps=['與小玄導師對話','找顧問博士：ChatGPT ≠ Agent','找 Hermes 隊長：認識 AI 員工','找 n8n 工程師：理解自動化輸送帶','幫阿莓挑戰重複工作怪','打倒重複工作怪','找數據研究員：理解回饋','進入課程之門'],questTarget=['guide','professor','hermes','engineer','creator','boss','analyst','portal'];
const battleTasks=[['比較 10 則 AI 新聞，判斷今天最值得做哪一題','hermes'],['把完成的腳本存進 Notion','n8n'],['判斷爭議新聞是否暫停發布','hermes'],['把素材存進 Google Drive','n8n'],['根據受眾選一個 Hook','hermes'],['完成後發 Telegram 通知','n8n']];
window.HG={$ ,W,H,T,COLS,ROWS,key,freshSave,get save(){return save},set save(v){save=v},persist,state,palette,terrain,blocked,buildings,trees,fences,signs,npc,dex,questSteps,questTarget,battleTasks};
})();