@echo off
rem Double-click to play. Builds the desktop jar the first time, then runs it.
setlocal
cd /d "%~dp0"

rem Find Java: JAVA_HOME if set, otherwise Android Studio's bundled copy, otherwise whatever is on PATH.
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java"
if "%JAVA%"=="java" if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    set "JAVA=%ProgramFiles%\Android\Android Studio\jbr\bin\java"
)
if "%JAVA%"=="java" if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
    set "JAVA=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java"
)

if not exist "lwjgl3\build\libs\Kasserine-desktop.jar" (
    echo Building Kasserine for the first time. This takes a minute or two...
    call gradlew.bat lwjgl3:dist
    if errorlevel 1 (
        echo.
        echo Build failed. See "Running on desktop" in README.md.
        pause
        exit /b 1
    )
)

"%JAVA%" -jar "lwjgl3\build\libs\Kasserine-desktop.jar"
if errorlevel 1 pause
