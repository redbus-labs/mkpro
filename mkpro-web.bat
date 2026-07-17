@echo off
setlocal

:: Get the directory where this script resides
set "SCRIPT_DIR=%~dp0"

:: Define the path to the shaded JAR
set "JAR_PATH=%SCRIPT_DIR%target\mkpro-4.1.0.jar"

:: Check if the JAR exists
if not exist "%JAR_PATH%" (
    echo Error: mkpro JAR not found at %JAR_PATH%
    echo Please run 'mvn package -DskipTests' first.
    exit /b 1
)

:: Run with web UI enabled (default port 8080, WS on 8081)
echo Starting mkpro with Web UI at http://localhost:8080
java -Dmkpro.db.name=%~n0 -jar "%JAR_PATH%" --web %*

endlocal
