@echo off
rem Launches the tsundoku AVD with a visible window.
rem Why -gpu guest: on this machine (-gpu host / swiftshader_indirect / off) hang right after
rem "emuglConfig_init" with qemu at ~1 CPU-second and no adb device. Guest-side software
rem rendering is slower but boots and renders reliably.
rem 720x1600 + 280dpi = 411dp wide, same as pixel_6 native. Density is stored in the AVD data
rem image, so this only needs redoing after -wipe-data or a fresh AVD:
rem   adb shell wm density 280
setlocal
set "ANDROID_SDK=E:\code\Android\sdk"
set "ANDROID_HOME=%ANDROID_SDK%"
set "ANDROID_SDK_ROOT=%ANDROID_SDK%"
set "ANDROID_AVD_HOME=E:\code\Android\avd"
set "ANDROID_USER_HOME=E:\code\Android\user-home"
set "TMP=E:\code\Android\tmp"
set "TEMP=E:\code\Android\tmp"
if not exist "%TMP%" mkdir "%TMP%"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"
start "" "%ANDROID_SDK%\emulator\emulator.exe" -avd tsundoku -gpu guest -no-snapshot -no-boot-anim -no-audio -no-metrics -skin 720x1600
endlocal
