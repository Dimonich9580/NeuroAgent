# buildozer.spec — KAI9000 APK-пульт
# Сборка на Linux-хосте: JDK 17, Android SDK/NDK (buildozer качает сам), ~15 GB диска.
# Перед первой сборкой: python apk/sync_core.py  (копирует kai9000/ и cluster/ в apk/)

[app]

title = KAI9000
package.name = kai9000
package.domain = org.Dimonich
version = 1.0.0

source.dir = .
source.include_exts = py,json,png,jpg,atlas,ttf
source.exclude_dirs = bin,.buildozer,__pycache__
source.exclude_patterns = *.pyc,*.pyo

requirements = python3,kivy,httpx,pydantic,pydantic-settings,redis

orientation = portrait
fullscreen = 0

# Foreground-сервис с агентом. Класс — наш подкласс p4a-базы.
services = kai9000:service.py:org.Dimonich.service.PythonService:foreground

android.permissions = INTERNET,ACCESS_NETWORK_STATE,FOREGROUND_SERVICE,WAKE_LOCK,POST_NOTIFICATIONS
android.api = 33
android.minapi = 26
android.ndk = 25b
android.archs = arm64-v8a
android.private_storage = True
android.allow_backup = False

# p4a develop нужен для pydantic-settings (рецепт появился позже стабильных)
p4a.branch = develop

[buildozer]

log_level = 2
warn_on_root = 1
