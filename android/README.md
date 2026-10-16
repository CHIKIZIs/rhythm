# Ритм для Android

Android-приложение: внутри тот же Ритм (`app/src/main/assets/www/index.html`), плюс будильник через AlarmManager,
экран будильника поверх блокировки, напоминания-уведомления и три виджета.

Сборка: `./gradlew assembleRelease` (Android SDK 35, JDK 17+). Подпись берётся из `signing.properties` и `ritm.keystore`;
этот же ключ нужен для всех следующих версий, иначе Android не даст обновить приложение поверх старого.
Страница для приложения собирается скриптом `build_android.py` из веб-версии.
