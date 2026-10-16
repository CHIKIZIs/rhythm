"""Builds the installable (PWA) version of the Ритм planner from the artifact page ritm.html."""
import os, re, json, shutil

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "..", "ritm.html")
DIST = os.path.join(ROOT, "dist")
VERSION = "1.3.0"

src = open(SRC, encoding="utf-8").read()


def rep(s, a, b):
    assert s.count(a) == 1, ("anchor not found once", a[:80], s.count(a))
    return s.replace(a, b)


# ---- split the artifact page into head bits and body
title = re.search(r"<title>.*?</title>\n", src).group(0)
body = src.replace(title, "", 1)
body = body.replace('<meta name="claude:viewport-fit-sides" content="cover">\n', "", 1)

# ---- local file storage instead of the artifact's asset store
body = rep(body, "const blobUrl=id=>'/_blob/'+id;",
           "const blobUrl=id=>LOCAL_URLS.get(id)||'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';")

# ---- deep links from app shortcuts (?tab=tasks)
body = rep(body, "if(!TABS.includes(ui.tab))ui.tab='plan';",
           "try{const qt=new URLSearchParams(location.search).get('tab');if(qt)ui.tab=qt;}catch(e){}\nif(!TABS.includes(ui.tab))ui.tab='plan';")

# ---- system notifications when the app is in the background
body = rep(body, "function showAlert(r){\n", "function showAlert(r){\n  notifyOS(r.title,r.sub,r.key);\n")
body = rep(body, "function ring(a){\n", "function ring(a){\n  notifyOS('Будильник '+a.time,a.label||'Пора вставать','alarm-'+a.id,true);\n")

# ---- settings: an "Приложение" section
CHEV = '<svg class="chev" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>'
DATA_ANCHOR = '  <details class="grp" id="grpData">'
PWA_ROWS = '''  <div class="set-row" id="installRow"><div><b>Установить на устройство</b><small id="installText">Иконка на рабочем столе и работа без интернета</small></div><button class="link-btn" id="installBtn">Установить</button></div>
  <div class="set-row"><div><b>Системные уведомления</b><small id="notifText">Напоминания и будильник, даже когда окно свёрнуто</small></div><button class="switch" id="notifSw" role="switch" aria-checked="false" aria-label="Системные уведомления"></button></div>
  <div class="set-row"><div><b>Версия</b><small>Ритм ''' + VERSION + '''</small></div><button class="link-btn" id="updBtn">Проверить обновление</button></div>
  <div class="set-row" id="apkRow" hidden><div><b>Приложение для Android</b><small>С виджетами и будильником, который звонит, даже когда Ритм закрыт</small></div><a class="link-btn" href="ritm.apk" download>Скачать</a></div>
'''
APP_SETTINGS = '  <details class="grp" id="grpApp"><summary><span><b>Приложение</b><small>Установка, уведомления, версия</small></span>' + CHEV + '</summary><div class="grp-body">\n' + PWA_ROWS + '  </div></details>\n'
body = rep(body, DATA_ANCHOR, APP_SETTINGS + DATA_ANCHOR)

# ---- install banner shown once on first visits
BANNER = '''<div class="install-bar" id="installBar" hidden>
  <img src="icon-192.png" alt="" width="40" height="40">
  <div><b>Установи Ритм</b><small id="installBarText">Будет открываться как обычное приложение</small></div>
  <button id="installBarGo">Установить</button><button id="installBarX" aria-label="Скрыть">✕</button>
</div>
<div class="toast" id="toast" role="status">'''
body = rep(body, '<div class="toast" id="toast" role="status">', BANNER)

APP_CSS = '''
/* ---------- installed app ---------- */
.install-bar{position:fixed;z-index:42;left:50%;transform:translateX(-50%);bottom:calc(96px + env(safe-area-inset-bottom,0px));width:min(460px,calc(100vw - 24px));
  display:flex;align-items:center;gap:12px;background:var(--surface);border-radius:18px;padding:12px;box-shadow:0 20px 50px -12px rgb(0 0 0 / .4),0 0 0 1px var(--line);animation:drop .5s cubic-bezier(.3,1.4,.5,1) both}
.install-bar img{border-radius:10px;flex:none}
.install-bar div{flex:1;min-width:0;display:flex;flex-direction:column}
.install-bar b{font-size:15px}
.install-bar small{font-size:13px;color:var(--muted)}
.install-bar button{flex:none;font-weight:700;border-radius:12px;padding:9px 14px;background:var(--accent);color:var(--accent-ink)}
.install-bar #installBarX{background:none;color:var(--muted);padding:9px}
.ios-steps{margin:0;padding-left:20px;display:flex;flex-direction:column;gap:6px;font-size:15px}
@media (display-mode: standalone){.only-browser{display:none!important}}
</style>'''
body = rep(body, "</style>", APP_CSS)

APP_JS = r'''/* ---------- installed app: local files, downloads, install, notifications ---------- */
const LOCAL_URLS=new Map();
const FilesDB=(()=>{
  let p=null;
  const open=()=>p||(p=new Promise((res,rej)=>{const r=indexedDB.open('ritm-files',1);r.onupgradeneeded=()=>r.result.createObjectStore('files');r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error)}));
  const run=(mode,fn)=>open().then(db=>new Promise((res,rej)=>{const t=db.transaction('files',mode);const st=t.objectStore('files');let out;const req=fn(st);if(req)req.onsuccess=()=>{out=req.result};t.oncomplete=()=>res(out);t.onerror=()=>rej(t.error);t.onabort=()=>rej(t.error)}));
  const hex=()=>Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');
  return{
    async loadAll(){const db=await open();await new Promise(res=>{const t=db.transaction('files','readonly');const c=t.objectStore('files').openCursor();c.onsuccess=()=>{const cur=c.result;if(cur){if(!LOCAL_URLS.has(cur.key))LOCAL_URLS.set(cur.key,URL.createObjectURL(cur.value.blob));cur.continue()}};t.oncomplete=res;t.onerror=res})},
    async upload(blob,opt){const type=(opt&&opt.type)||blob.type||'application/octet-stream';const id=hex();const b=new Blob([blob],{type});
      try{await run('readwrite',st=>st.put({blob:b,type,size:b.size,created:new Date().toISOString()},id))}catch(e){throw{code:'quota_or_state'}}
      const url=URL.createObjectURL(b);LOCAL_URLS.set(id,url);return{id,url,sizeBytes:b.size,contentType:type}},
    async list(){const db=await open();const assets=[];await new Promise(res=>{const t=db.transaction('files','readonly');const c=t.objectStore('files').openCursor();c.onsuccess=()=>{const cur=c.result;if(cur){assets.push({id:cur.key,url:LOCAL_URLS.get(cur.key)||'',contentType:cur.value.type,sizeBytes:cur.value.size,createdAt:cur.value.created});cur.continue()}};t.oncomplete=res;t.onerror=res});
      let maxBytes=2*1073741824;try{const e=await navigator.storage.estimate();if(e&&e.quota)maxBytes=e.quota}catch(e){}
      return{assets,usage:{files:assets.length,bytes:assets.reduce((a,x)=>a+x.sizeBytes,0),maxFiles:100000,maxBytes}}},
    async delete(id){await run('readwrite',st=>st.delete(id));const u=LOCAL_URLS.get(id);if(u){URL.revokeObjectURL(u);LOCAL_URLS.delete(id)}return{deleted:true}}
  };
})();
const LocalDownloads={async save({filename,data}){const b=data instanceof Blob?data:new Blob([data],{type:'application/json'});const u=URL.createObjectURL(b);const a=document.createElement('a');a.href=u;a.download=filename;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(u),4000);return{status:'saved'}}};

/* service worker */
let swReg=null;
if('serviceWorker' in navigator){navigator.serviceWorker.register('sw.js').then(r=>{swReg=r;r.addEventListener('updatefound',()=>{const w=r.installing;if(!w)return;w.addEventListener('statechange',()=>{if(w.state==='installed'&&navigator.serviceWorker.controller)toast('Вышло обновление','Обновить',()=>location.reload())})})}).catch(()=>{})}
$('#updBtn').onclick=async()=>{if(!swReg){toast('Обновления работают, когда приложение открыто с сайта');return}try{await swReg.update();toast(swReg.installing||swReg.waiting?'Скачиваю обновление…':'У тебя последняя версия')}catch(e){toast('Нет интернета, проверю позже')}};

/* install */
const isStandalone=()=>matchMedia('(display-mode: standalone)').matches||navigator.standalone===true;
const isIOS=/iphone|ipad|ipod/i.test(navigator.userAgent)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
let deferredPrompt=null;
addEventListener('beforeinstallprompt',e=>{e.preventDefault();deferredPrompt=e;renderInstall();maybeBanner()});
addEventListener('appinstalled',()=>{deferredPrompt=null;$('#installBar').hidden=true;renderInstall();sfx('celebrate');toast('Ритм установлен! Ищи иконку на рабочем столе');try{navigator.storage&&navigator.storage.persist&&navigator.storage.persist()}catch(e){}});
function renderInstall(){
  const row=$('#installRow');
  if(isStandalone()){row.hidden=false;$('#installText').textContent='Приложение установлено';$('#installBtn').hidden=true;return}
  $('#installBtn').hidden=false;
  $('#installText').textContent=deferredPrompt?'Иконка на рабочем столе и работа без интернета':isIOS?'В Safari: «Поделиться» → «На экран Домой»':'Открой меню браузера и выбери «Установить приложение»';
}
async function doInstall(){
  if(deferredPrompt){deferredPrompt.prompt();const c=await deferredPrompt.userChoice.catch(()=>null);deferredPrompt=null;$('#installBar').hidden=true;if(c&&c.outcome!=='accepted')toast('Можно установить позже в настройках');renderInstall();return}
  showIosHelp();
}
function showIosHelp(){
  const bar=$('#installBar');$('#installBar').hidden=true;
  const steps=isIOS?['Открой эту страницу в Safari','Нажми кнопку «Поделиться» (квадрат со стрелкой вверх)','Выбери «На экран Домой» и нажми «Добавить»']
    :['Открой меню браузера (три точки ⋮ вверху справа)','Выбери «Установить приложение» или «Добавить на главный экран»','Подтверди установку'];
  showAlert({key:'install',title:'Как установить Ритм',sub:'',noSnooze:true});
  const el=$('#alerts').lastElementChild;if(el){el.querySelector('small').innerHTML='<ol class="ios-steps">'+steps.map(s=>'<li>'+s+'</li>').join('')+'</ol>';el.querySelector('.bell').innerHTML='<img src="icon-192.png" width="40" height="40" style="border-radius:10px" alt="">'}
}
$('#installBtn').onclick=doInstall;$('#installBarGo').onclick=doInstall;
$('#installBar').hidden=true;
$('#installBarX').onclick=()=>{$('#installBar').hidden=true;lsSet('ritm-install-dismiss',Date.now())};
function maybeBanner(){
  if(isStandalone())return;const d=lsGet('ritm-install-dismiss',0);if(d&&Date.now()-d<7*864e5)return;
  if(!deferredPrompt&&!isIOS)return;
  $('#installBarText').textContent=isIOS?'Нажми, и я покажу, как добавить на экран':'Будет открываться как обычное приложение';
  setTimeout(()=>{if(!openDlg)$('#installBar').hidden=false},2500);
}
renderInstall();if(isIOS)maybeBanner();
if(/android/i.test(navigator.userAgent)&&!window.RitmNative)$('#apkRow').hidden=false;

/* system notifications */
function notifState(){return('Notification' in window)?Notification.permission:'unsupported'}
function renderNotif(){
  const st=notifState();const on=st==='granted'&&settings.osNotif!==false;
  $('#notifSw').setAttribute('aria-checked',String(on));
  $('#notifText').textContent=st==='unsupported'?(isIOS&&!isStandalone()?'Сначала установи приложение на экран Домой':'Этот браузер не умеет показывать уведомления'):st==='denied'?'Уведомления запрещены в настройках браузера':'Напоминания и будильник, даже когда окно свёрнуто';
}
$('#notifSw').onclick=async()=>{
  const st=notifState();if(st==='unsupported'||st==='denied'){renderNotif();toast(st==='denied'?'Разреши уведомления для сайта в настройках браузера':'Уведомления здесь недоступны');return}
  if(st==='granted'){settings.osNotif=settings.osNotif===false;saveSettings();renderNotif();return}
  const r=await Notification.requestPermission().catch(()=>'denied');settings.osNotif=true;saveSettings();renderNotif();
  if(r==='granted')notifyOS('Уведомления включены','Так будут выглядеть напоминания Ритма','test',false,true);
};
function notifyOS(title,body,tag,urgent,force){
  try{
    if(notifState()!=='granted'||settings.osNotif===false)return;
    if(!force&&!document.hidden)return;
    const opt={body:body||'',tag:String(tag||'ritm'),icon:'icon-192.png',badge:'icon-192.png',renotify:true,requireInteraction:!!urgent,vibrate:urgent?[400,200,400,200,400]:[200,100,200]};
    if(swReg&&swReg.showNotification)swReg.showNotification(title,opt);else new Notification(title,opt);
  }catch(e){}
}
const origSettingsOpen=$('#settingsBtn').onclick;
$('#settingsBtn').onclick=()=>{origSettingsOpen();renderInstall();renderNotif()};

/* ---------- boot ---------- */'''
body = rep(body, "/* ---------- boot ---------- */", APP_JS)

# capabilities -> local equivalents
body = rep(body,
    "(async()=>{try{if(window.claude&&window.claude.use){const r=await Promise.all([window.claude.use('downloads'),window.claude.use('assets')]);downloads=r[0];assets=r[1]}}catch(e){}if(!downloads)$('#exportRow').hidden=true;if(openDlg){const b=openDlg.querySelector('.att-edit');if(b)renderAtt(b)}})();",
    "downloads=LocalDownloads;\n(async()=>{try{if('indexedDB' in window){await FilesDB.loadAll();assets=FilesDB}}catch(e){}render();if(openDlg){const b=openDlg.querySelector('.att-edit');if(b)renderAtt(b)}})();")
# the attachment hint mentions the Claude account; in the app it is about this device
body = body.replace("Вложения работают, когда планер открыт в твоём аккаунте Claude", "Вложения не поддерживаются этим браузером")
body = body.replace("Сохранено в аккаунте", "Сохранено")

HEAD = f'''<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
{title.strip()}
<meta name="description" content="Планер: расписание, задачи, заметки, будильник и фокус-таймер">
<link rel="manifest" href="manifest.webmanifest?v={VERSION}">
<meta name="theme-color" content="#F1F2F7" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0D0F1E" media="(prefers-color-scheme: dark)">
<link rel="icon" type="image/png" sizes="32x32" href="favicon-32.png">
<link rel="apple-touch-icon" href="apple-touch-icon.png">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Ритм">
<meta name="apple-mobile-web-app-status-bar-style" content="default">
<style>:root{{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}}body{{margin:0;font:14px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}}img{{max-width:100%}}[hidden]{{display:none!important}}</style>
</head>
<body>
'''
html = HEAD + body.rstrip() + "\n</body>\n</html>\n"
open(os.path.join(DIST, "index.html"), "w", encoding="utf-8").write(html)

manifest = {
    "name": "Ритм — планер",
    "short_name": "Ритм",
    "description": "Расписание, задачи, заметки, будильник и фокус-таймер",
    "lang": "ru",
    "id": "./",
    "start_url": "./",
    "scope": "./",
    "display": "standalone",
    "display_override": ["window-controls-overlay", "standalone"],
    "orientation": "any",
    "background_color": "#F1F2F7",
    "theme_color": "#3346F0",
    "categories": ["productivity", "lifestyle"],
    "icons": [
        {"src": "icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
        {"src": "icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
        {"src": "maskable-192.png", "sizes": "192x192", "type": "image/png", "purpose": "maskable"},
        {"src": "maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"},
    ],
    "shortcuts": [
        {"name": "План на сегодня", "url": "./?tab=plan", "icons": [{"src": "icon-192.png", "sizes": "192x192"}]},
        {"name": "Задачи", "url": "./?tab=tasks", "icons": [{"src": "icon-192.png", "sizes": "192x192"}]},
        {"name": "Заметки", "url": "./?tab=notes", "icons": [{"src": "icon-192.png", "sizes": "192x192"}]},
        {"name": "Будильник", "url": "./?tab=focus", "icons": [{"src": "icon-192.png", "sizes": "192x192"}]},
    ],
}
json.dump(manifest, open(os.path.join(DIST, "manifest.webmanifest"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)

SW = '''/* Ритм service worker: works offline, updates in the background */
const VERSION='ritm-''' + VERSION + '''';
const CORE=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png','./maskable-192.png','./maskable-512.png','./apple-touch-icon.png','./favicon-32.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(VERSION).then(c=>Promise.all(CORE.map(u=>fetch(new Request(u,{cache:'reload'})).then(r=>{if(r.ok)return c.put(u,r)}).catch(()=>{})))).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==VERSION&&k!=='ritm-fonts').map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const req=e.request;if(req.method!=='GET')return;
  const url=new URL(req.url);
  if(url.origin===location.origin){
    if(req.mode==='navigate'){
      e.respondWith(fetch(req,{cache:'no-cache'}).then(r=>{if(r.ok){const cp=r.clone();caches.open(VERSION).then(c=>c.put('./index.html',cp))}return r}).catch(()=>caches.match('./index.html',{ignoreSearch:true})));
      return;
    }
    if(/\\.(webmanifest|html|js)$/.test(url.pathname)){
      e.respondWith(fetch(req,{cache:'no-cache'}).then(r=>{if(r.ok){const cp=r.clone();caches.open(VERSION).then(c=>c.put(url.pathname.endsWith('.webmanifest')?'./manifest.webmanifest':req,cp))}return r}).catch(()=>caches.match(req,{ignoreSearch:true})));
      return;
    }
    e.respondWith(caches.match(req,{ignoreSearch:true}).then(hit=>hit||fetch(req).then(r=>{if(r.ok){const cp=r.clone();caches.open(VERSION).then(c=>c.put(req,cp))}return r})));
    return;
  }
  if(url.host==='fonts.googleapis.com'||url.host==='fonts.gstatic.com'){
    e.respondWith(caches.open('ritm-fonts').then(c=>c.match(req).then(hit=>{const net=fetch(req).then(r=>{if(r.ok||r.type==='opaque')c.put(req,r.clone());return r}).catch(()=>hit);return hit||net})));
  }
});
self.addEventListener('notificationclick',e=>{
  e.notification.close();
  e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(list=>{for(const c of list){if('focus' in c)return c.focus()}return self.clients.openWindow('./')}));
});
'''
open(os.path.join(DIST, "sw.js"), "w", encoding="utf-8").write(SW)

README = '''РИТМ — ПЛАНЕР. КАК УСТАНОВИТЬ

Чтобы планер ставился как приложение, эти файлы нужно один раз выложить
в интернет (бесплатно). Потом открываешь ссылку на телефоне или компьютере
и нажимаешь «Установить».

СПОСОБ 1. GitHub Pages (бесплатно и навсегда)
 1. Зарегистрируйся на github.com.
 2. Нажми «+» вверху → «New repository». Название, например: ritm. Тип: Public. Create repository.
 3. Нажми «uploading an existing file» и перетащи ВСЁ содержимое этой папки
    (все файлы: index.html, sw.js, manifest.webmanifest и картинки .png). Нажми «Commit changes».
 4. Settings → Pages → Branch: main, папка / (root) → Save.
 5. Через 1–2 минуты появится ссылка вида https://ТВОЙ-НИК.github.io/ritm/

СПОСОБ 2. Netlify (перетащил и готово)
 1. Открой app.netlify.com/drop и войди (можно через почту или GitHub).
 2. Перетащи эту папку целиком в окно браузера.
 3. Получишь ссылку вида https://что-то.netlify.app

УСТАНОВКА ПО ССЫЛКЕ
 • Android (Chrome): открой ссылку → внизу появится «Установить Ритм»,
   или меню ⋮ → «Установить приложение».
 • iPhone (Safari): кнопка «Поделиться» → «На экран Домой» → «Добавить».
 • Компьютер (Chrome, Edge, Яндекс Браузер): значок установки справа
   в адресной строке, или меню → «Установить Ритм».

ПЕРЕНОС ЗАПИСЕЙ ИЗ CLAUDE
 Версия в Claude и приложение хранят данные отдельно. Чтобы перенести:
 в Claude → Настройки → «Резервная копия» → Сохранить файл.
 В приложении → Настройки → «Восстановить из копии» → выбрать этот файл.

ВАЖНО
 • Все записи и вложения хранятся на самом устройстве. Делай резервную копию.
 • Напоминания и будильник срабатывают, пока приложение запущено
   (можно свёрнутым). На ночь включай «Ночные часы».
 • Открывать index.html двойным щелчком с диска нельзя: установка работает
   только по https-ссылке.
'''
open(os.path.join(DIST, "КАК УСТАНОВИТЬ.txt"), "w", encoding="utf-8").write(README)
print("built", len(html) // 1024, "KB")
