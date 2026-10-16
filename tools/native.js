/* ---------- Android app: alarms, reminders and widgets live in the phone ---------- */
const N=window.RitmNative;
const LocalDownloadsNative={save({filename,data}){return new Promise((res,rej)=>{window.__ritmSaved=ok=>{ok?res({status:'saved'}):rej({code:'declined'})};N.saveFile(filename,typeof data==='string'?data:'')})}};
function hexOf(c){c=String(c||'').trim();if(c[0]==='#')return c.length===4?'#'+c.slice(1).split('').map(x=>x+x).join(''):c.slice(0,7);const m=c.match(/[\d.]+/g);if(!m)return'#3346F0';return'#'+m.slice(0,3).map(x=>Math.round(+x).toString(16).padStart(2,'0')).join('')}
function nativeBars(){try{const bg=getComputedStyle(document.body).backgroundColor;const dark=getComputedStyle(document.documentElement).colorScheme.includes('dark');N.setSystemBars(hexOf(bg),dark)}catch(e){}}
let nT=0;
function nativeSync(){if(!N)return;clearTimeout(nT);nT=setTimeout(nativeSyncNow,300)}
function nativePayload(){
  const now=Date.now(),rem=[],days={};
  for(let i=0;i<14;i++){
    const d=iso(addDays(new Date(),i));
    state.events.forEach(e=>{
      if(e.remind<0||!evOn(e,d))return;const t=at(d,e.start)-e.remind*60000;if(t<=now)return;
      const when=e.remind===0?'Начинается сейчас':e.remind===1440?'Завтра':'Через '+(e.remind>=60?(e.remind/60)+' ч':e.remind+' мин');
      rem.push({id:'e|'+e.id+'|'+d,at:t,title:e.title,body:when+', в '+e.start+(e.end?'–'+e.end:'')+(e.note?'\n'+e.note:''),tab:'plan'});
    });
    state.tasks.forEach(x=>{
      if(!x.remindTime)return;const on=isRec(x)?rtOn(x,d):x.due===d;if(!on||tDone(x,d))return;
      const t=at(d,x.remindTime);if(t<=now)return;
      const left=x.subs.filter(s=>!s.d).length;
      rem.push({id:'t|'+x.id+'|'+d,at:t,title:x.title,body:(x.priority===1?'Срочная задача':'Задача')+' на сегодня'+(left?', осталось шагов: '+left:'')+(x.note?'\n'+x.note:''),tab:'tasks',taskId:x.id,date:d});
    });
    if(i<7){
      const ev=dayEvents(d).map(e=>({s:e.start,e:e.end,t:e.title,c:hexOf(cssVar('--sw'+e.color))}));
      const tk=state.tasks.filter(x=>isRec(x)?rtOn(x,d):
        (x.due===d)||(i===0&&!x.done&&x.due&&x.due<d)||(i===0&&x.done&&x.doneAt&&iso(new Date(x.doneAt))===d)||(i===0&&!x.done&&!x.due&&x.priority===1))
        .map(x=>({id:x.id,t:x.title,p:x.priority,d:tDone(x,d)}));
      days[d]={events:ev,tasks:tk};
    }
  }
  rem.sort((a,b)=>a.at-b.at);
  const alarms=state.alarms.map(a=>({id:a.id,time:a.time,days:a.days,label:a.label,sound:a.sound,snooze:a.snooze,grad:a.grad,on:a.on}));
  return{accent:hexOf(cssVar('--accent')),alarms,reminders:rem.slice(0,80),widget:{days,updated:now}};
}
function nativeSyncNow(){if(!N)return;try{N.sync(JSON.stringify(nativePayload()))}catch(e){}}
function nativeEvents(){
  if(!N)return false;
  let ev=[];try{ev=JSON.parse(N.takeEvents()||'[]')}catch(e){}
  let ch=false;
  ev.forEach(x=>{
    if(x.type==='alarm_once_done'){const a=state.alarms.find(a=>a.id===x.id);if(a&&a.on&&!a.days.length){a.on=false;ch=true}}
    else if(x.type==='task_done'){const t=state.tasks.find(t=>t.id===x.id);if(t&&!tDone(t,x.date)){if(isRec(t))t.doneDates.push(x.date);else{t.done=true;t.doneAt=Date.now()}ch=true}}
  });
  if(ch){persist();render()}
  return ch;
}
const PERMS=[['notif','Уведомления','Без них не будет напоминаний и звонка будильника',0],
  ['exact','Точное время','Чтобы будильник и напоминания срабатывали минута в минуту',31],
  ['fullscreen','Будильник на весь экран','Показывать будильник поверх экрана блокировки',34],
  ['battery','Работа в фоне','Чтобы телефон не усыплял Ритм. Особенно важно на Xiaomi, Huawei, Honor и Samsung',0]];
let nStatus={};
function readStatus(){try{nStatus=JSON.parse(N.getStatus())}catch(e){nStatus={}}return nStatus}
function renderPerms(){
  if(!N)return;
  readStatus();const box=$('#permList');
  if(box){box.innerHTML=PERMS.filter(p=>!p[3]||nStatus.sdk>=p[3]).map(p=>'<div class="set-row"><div><b>'+p[1]+'</b><small>'+p[2]+'</small></div>'+
      (nStatus[p[0]]?'<span class="perm-ok">✓ Включено</span>':'<button class="link-btn" data-perm="'+p[0]+'">Включить</button>')+'</div>').join('')+
      '<div class="set-row"><div><b>Громкость будильника</b><small>Звонок идёт с громкостью «Будильник» в настройках звука телефона</small></div><button class="link-btn" data-perm="sound">Открыть</button></div>';
    $('#nVer').textContent='Ритм '+(nStatus.version||'');}
  const w=$('#permWarn');
  if(w){
    const need=!nStatus.notif?['notif','Разреши уведомления, иначе будильник не сможет зазвонить']:
      (nStatus.sdk>=34&&!nStatus.fullscreen)?['fullscreen','Разреши показ будильника на весь экран']:
      (nStatus.sdk>=31&&!nStatus.exact)?['exact','Разреши точное время для будильника']:null;
    w.innerHTML=need&&state.alarms.some(a=>a.on)?'<div class="perm-warn"><span>'+need[1]+'</span><button data-perm="'+need[0]+'">Разрешить</button></div>':'';
  }
}
document.addEventListener('click',e=>{const b=e.target.closest('[data-perm]');if(b&&N)N.openSetting(b.dataset.perm)});
if(N){
  $('#nTest').onclick=()=>{N.testAlarm('Проверка будильника','bells');toast('Зазвонит через 10 секунд. Можно заблокировать телефон')};
  const vNum=v=>String(v||'0').split('.').map(Number);
  const newer=(a,b)=>{a=vNum(a);b=vNum(b);for(let i=0;i<3;i++){if((a[i]||0)>(b[i]||0))return true;if((a[i]||0)<(b[i]||0))return false}return false};
  const checkUpdate=async manual=>{
    try{const r=await fetch('https://chikizis.github.io/rhythm/android.json?t='+Date.now(),{cache:'no-store'});const j=await r.json();
      readStatus();
      if(newer(j.version,nStatus.version))toast('Вышла версия '+j.version,'Скачать',()=>N.openUrl(j.url||'https://chikizis.github.io/rhythm/ritm.apk'));
      else if(manual)toast('У тебя последняя версия');
    }catch(e){if(manual)toast('Нет интернета, проверю позже')}
  };
  $('#nUpd').onclick=()=>checkUpdate(true);
  if(Date.now()-lsGet('ritm-upd-check',0)>864e5){lsSet('ritm-upd-check',Date.now());setTimeout(()=>checkUpdate(false),4000)}
  const _persist=persist;persist=function(){_persist();nativeSync()};
  const _aa=applyAccent;applyAccent=function(){_aa();setTimeout(nativeBars,30);nativeSync()};
  const _at=applyTheme;applyTheme=function(){_at();setTimeout(nativeBars,30)};
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change',()=>setTimeout(()=>{nativeBars();nativeSync()},60));
  const _so=$('#settingsBtn').onclick;$('#settingsBtn').onclick=()=>{_so();renderPerms()};
  const _rc=renderClock;renderClock=function(){_rc();renderPerms()};
  window.__ritmResume=()=>{nativeEvents();nativeSync();renderPerms();if(!openDlg){renderHero();renderWeek();renderSchedule();renderTasks();if(ui.tab==='focus')renderAlarms()}};
  window.__ritmBack=()=>{
    if(!$('#lb').hidden){closeLb();return true}
    if(!$('#night').hidden){$('#nightExit').click();return true}
    if(openDlg){closeDlg();return true}
    if(ui.tab!=='plan'){ui.tab='plan';lsSet('ritm-tab',ui.tab);render();return true}
    return false;
  };
  window.__ritmOpen=(tab,add)=>{
    if(!$('#lb').hidden)closeLb();if(openDlg)closeDlg();
    if(tab==='plan'||tab==='tasks')ui.day=todayIso();
    if(TABS.includes(tab)){ui.tab=tab;lsSet('ritm-tab',tab);render()}
    if(add==='task'||add==='event'){ui.tab=add==='task'?'tasks':'plan';render();setTimeout(()=>openEditor(),reduce?0:350)}
  };
  try{const qp=new URLSearchParams(location.search);const add=qp.get('add');if(add)setTimeout(()=>__ritmOpen(qp.get('tab')||'plan',add),700)}catch(e){}
  setTimeout(()=>{nativeEvents();nativeSync();nativeBars()},400);
}
/* show a snoozed alarm (kept by the phone) as the next one */
if(N){
  const _ra=renderAlarms;renderAlarms=function(){
    _ra();
    try{readStatus();const nx=nextAlarm();const t=nStatus.next;
      if(t&&t>Date.now()&&(!nx||t<nx.t-60000)){const d=new Date(t);$('#nextAl').innerHTML='<span>Отложенный будильник</span><b>'+pad(d.getHours())+':'+pad(d.getMinutes())+'</b><span>через '+untilText(t-Date.now())+'</span>'}
    }catch(e){}
  };
}
/* icon & widget colour (Android only) */
if(N&&N.setIconColor){
  const ICONS=[['cobalt','Кобальт','#3346F0','#8E3FE0'],['ocean','Океан','#0A8DB0','#3346F0'],['mint','Мята','#16A085','#2EE6C8'],['green','Лес','#1E9970','#5E9400'],['sunset','Закат','#FF8A3D','#D93A40'],
    ['amber','Янтарь','#E0A000','#E06A0A'],['rose','Роза','#E0548A','#B42FB0'],['red','Рубин','#D93A40','#8E1C3A'],['violet','Фиалка','#8E3FE0','#5B3DF5'],['graphite','Графит','#4A5270','#15172C']];
  const CHECK='<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5a8.5 8.5 0 1 1-8.5 8.5" opacity="1"/><path d="M8 12.5l2.8 2.8L16.5 9.6"/></svg>';
  const st=document.createElement('style');st.textContent=`
.ic-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}
.ic-grid button{display:flex;flex-direction:column;align-items:center;gap:4px;font-size:11px;color:var(--muted);font-weight:600}
.ic-grid i{width:52px;height:52px;border-radius:16px;display:grid;place-items:center;box-shadow:var(--shadow);transition:transform .2s}
.ic-grid button[aria-pressed="true"] i{outline:3px solid var(--accent);outline-offset:3px;transform:scale(1.06)}
.ic-grid button[aria-pressed="true"]{color:var(--ink)}
.wprev{display:flex;gap:10px;align-items:stretch}
.wprev div{flex:1;border-radius:16px;padding:10px 12px;font-size:12px;display:flex;flex-direction:column;gap:2px;min-width:0}
.wprev b{font-size:20px;font-weight:800}`;
  document.head.appendChild(st);
  const grp=document.createElement('details');grp.className='grp';grp.id='grpIcon';
  grp.innerHTML='<summary><span><b>Иконка и виджеты</b><small>Цвет иконки Ритма и виджетов на главном экране</small></span><svg class="chev" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg></summary>'+
    '<div class="grp-body"><div class="set-row set-col"><div><b>Цвет</b><small>Меняет иконку на рабочем столе и все виджеты Ритма</small></div><div class="ic-grid" id="icGrid"></div></div>'+
    '<div class="set-row set-col"><div><b>Стиль виджетов</b></div><div class="pill-seg"><input class="radio" type="radio" name="wstyle" id="ws-color" value="color"><label for="ws-color">Цветные</label><input class="radio" type="radio" name="wstyle" id="ws-plain" value="plain"><label for="ws-plain">Обычные</label></div>'+
    '<div class="wprev" id="wPrev"></div></div>'+
    '<p class="hint" style="padding:4px 2px 10px">Иконка на телефоне обновится через несколько секунд. На некоторых телефонах её нужно заново перетащить на рабочий стол из списка приложений.</p></div>';
  const data=$('#grpData');data.parentNode.insertBefore(grp,data);
  let cur='cobalt',wst='color';
  function renderIcons(){
    try{const s=JSON.parse(N.getStatus());cur=s.icon||cur;wst=s.wstyle||wst}catch(e){}
    $('#icGrid').innerHTML=ICONS.map(c=>'<button type="button" data-icon="'+c[0]+'" aria-pressed="'+(c[0]===cur)+'"><i style="background:linear-gradient(160deg,'+c[2]+','+c[3]+')">'+CHECK+'</i>'+c[1]+'</button>').join('');
    $('#ws-'+wst).checked=true;
    const c=ICONS.find(x=>x[0]===cur)||ICONS[0];
    const colored=wst==='color';
    const box=(t,s)=>'<div style="background:'+(colored?c[2]:'var(--surface)')+';color:'+(colored?'#fff':'var(--ink)')+';border:1px solid '+(colored?'transparent':'var(--line)')+'"><span style="opacity:.8">'+t+'</span><b style="color:'+(colored?'#fff':c[2])+'">'+s+'</b></div>';
    $('#wPrev').innerHTML=box('Будильник','07:00')+box('Задачи','2 из 5');
  }
  grp.addEventListener('toggle',()=>{if(grp.open)renderIcons()});
  $('#icGrid').addEventListener('click',e=>{const b=e.target.closest('[data-icon]');if(!b)return;cur=b.dataset.icon;N.setIconColor(cur);sfx('done');renderIcons();toast('Цвет «'+b.textContent+'» выбран. Иконка обновится через пару секунд')});
  grp.addEventListener('change',e=>{if(e.target.name==='wstyle'){wst=e.target.value;N.setWidgetStyle(wst);renderIcons();toast(wst==='color'?'Виджеты стали цветными':'Виджеты в обычном стиле')}});
}
