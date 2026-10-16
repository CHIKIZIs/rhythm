"""Builds the page for the Android app: the same Ритм, wired to the phone through window.RitmNative.
Runs build.py first (web version), then adapts its page for the app."""
import os, re, glob, shutil

exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "build.py"), encoding="utf-8").read())

ANDROID = os.path.join(ROOT, "..", "rhythm", "android", "app", "src", "main", "assets", "www")
FONTS_SRC = "/tmp/fs"

nb = body
nb = rep(nb, "if('serviceWorker' in navigator){navigator.serviceWorker.register('sw.js')", "if(false){navigator.serviceWorker.register('sw.js')")
nb = rep(nb, APP_SETTINGS, '  <div hidden id="pwaRows">\n' + PWA_ROWS + '  </div>\n' +
    '  <details class="grp" id="grpPhone" open><summary><span><b>Будильник и напоминания</b><small>Разрешения телефона, проверка будильника, версия</small></span>' + CHEV + '</summary><div class="grp-body">\n' +
    '''  <div id="permList"></div>
  <div class="set-row"><div><b>Проверить будильник</b><small>Зазвонит через 10 секунд. Можно сразу заблокировать телефон</small></div><button class="link-btn" id="nTest">Проверить</button></div>
  <div class="set-row"><div><b>Версия</b><small id="nVer">Ритм</small></div><button class="link-btn" id="nUpd">Проверить обновление</button></div>
  </div></details>
''')
nb = rep(nb, '<link rel="preconnect" href="https://fonts.googleapis.com">\n<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>\n', '')
nb = re.sub(r'<link rel="stylesheet" href="https://fonts.googleapis.com[^>]*>', '<link rel="stylesheet" href="fonts/fonts.css">', nb, count=1)
nb = rep(nb, "l.href='https://fonts.googleapis.com/css2?family='+F[3]+'&display=swap';", "l.href='fonts/fonts.css';")
nb = rep(nb, "l.href='https://fonts.googleapis.com/css2?family='+f[3]+'&display=swap';", "l.href='fonts/fonts.css';")
nb = rep(nb, "function checkAlarms(){\n", "function checkAlarms(){\n  if(window.RitmNative)return;\n")
nb = rep(nb, "function checkReminders(){\n", "function checkReminders(){\n  if(window.RitmNative)return;\n")
nb = rep(nb, "async function lockWake(){try{",
         "async function lockWake(){if(window.RitmNative){RitmNative.keepScreenOn(true);wake={release(){RitmNative.keepScreenOn(false)}};return}try{")
nb = rep(nb, '<p class="hint al-note">Будильник звонит, пока планер открыт. На ночь включи «Ночные часы»: экран не погаснет, и сигнал точно сработает. Держи телефон на зарядке.</p>',
         '<p class="hint al-note">Будильник зазвонит, даже если Ритм закрыт, а телефон заблокирован.</p>')
nb = rep(nb, '<div id="alarmView">', '<div id="alarmView"><div id="permWarn"></div>')
nb = rep(nb, "downloads=LocalDownloads;", "downloads=window.RitmNative?LocalDownloadsNative:LocalDownloads;")
nb = rep(nb, "</style>", """.perm-ok{color:var(--calm);font-weight:700;font-size:14px;white-space:nowrap}
.perm-warn{background:var(--hot-soft);color:var(--hot);border-radius:var(--r);padding:12px 14px;margin-bottom:14px;display:flex;gap:10px;align-items:center;justify-content:space-between;flex-wrap:wrap;font-weight:600;font-size:14px}
.perm-warn button{background:var(--hot);color:#fff;border-radius:10px;padding:8px 12px;font-weight:700}
</style>""")

NATIVE_JS = open(os.path.join(ROOT, "native.js"), encoding="utf-8").read()
nb = rep(nb, "/* ---------- boot ---------- */", NATIVE_JS + "\n/* ---------- boot ---------- */")
nb = nb.replace("Сохраняется на этом устройстве", "Сохраняется на телефоне")

NHEAD = f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
{title.strip()}
<style>:root{{color-scheme:light}}body{{margin:0;font:14px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;-webkit-tap-highlight-color:transparent;overscroll-behavior:none}}img{{max-width:100%}}[hidden]{{display:none!important}}</style>
</head>
<body>
"""
os.makedirs(os.path.join(ANDROID, "fonts"), exist_ok=True)
shutil.copy(os.path.join(DIST, "icon-192.png"), os.path.join(ANDROID, "icon-192.png"))
open(os.path.join(ANDROID, "index.html"), "w", encoding="utf-8").write(NHEAD + nb.rstrip() + "\n</body>\n</html>\n")

# ---- bundle fonts (cyrillic + latin) so the app looks the same offline
FAMS = {"unbounded": [500, 700], "manrope": [400, 500, 600, 700], "jetbrains-mono": [500, 700], "comfortaa": [700],
        "russo-one": [400], "playfair-display": [700], "caveat": [700], "pacifico": [400], "press-start-2p": [400]}
css_out = []
for fam, weights in FAMS.items():
    pkg = glob.glob(os.path.join(FONTS_SRC, f"fontsource-{fam}-*", "package"))[0]
    for w in weights:
        css = open(os.path.join(pkg, f"{w}.css"), encoding="utf-8").read()
        for subset, face in re.findall(r"/\* ([\w-]+) \*/\s*(@font-face\s*\{.*?\})", css, re.S):
            if not re.search(r"-(cyrillic|latin)(-ext)?-\d+-normal$", subset):
                continue
            m = re.search(r"url\(\./files/([^)]+\.woff2)\)", face)
            if not m:
                continue
            fn = m.group(1)
            shutil.copy(os.path.join(pkg, "files", fn), os.path.join(ANDROID, "fonts", fn))
            face = re.sub(r"src:[^;]+;", f"src: url(./{fn}) format('woff2');", face)
            css_out.append(face)
open(os.path.join(ANDROID, "fonts", "fonts.css"), "w", encoding="utf-8").write("\n".join(css_out) + "\n")
print("android page built,", len(css_out), "font faces")
