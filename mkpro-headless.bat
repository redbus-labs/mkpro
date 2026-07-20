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

:: Run in headless mode: Web UI + Scheduler + MapDB runner (no interactive prompts)
echo Starting mkpro in headless mode
echo   Runner:    MAP_DB (persistent)
echo   Web UI:    http://localhost:8080
echo   Knowledge: http://localhost:8080/knowledge
echo   DB Browser: http://localhost:8080/db
java -Dmkpro.db.name=%~n0 -jar "%JAR_PATH%" --runner MAP_DB --web --scheduler %*

endlocal
