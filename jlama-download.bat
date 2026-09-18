@echo off
setlocal enabledelayedexpansion
set "JDK_JAVA_OPTIONS=--add-modules jdk.incubator.vector --enable-preview"

:: Jlama Model Downloader
:: Downloads a HuggingFace model for use with mkpro's Jlama provider

set "SCRIPT_DIR=%~dp0"
set "JAR_PATH=%SCRIPT_DIR%target\mkpro-4.5.0.jar"
set "MODELS_DIR=%USERPROFILE%\Documents\mkpro\jlama-models"

if "%~1"=="" (
    echo.
    echo   Jlama Model Downloader
    echo   ----------------------
    echo.
    echo   Usage: jlama-download.bat owner/model-name
    echo.
    echo   Recommended models:
    echo     tjake/Llama-3.2-1B-Instruct-JQ4        ~700 MB
    echo     tjake/Qwen2.5-1.5B-Instruct-JQ4        ~900 MB
    echo     tjake/Llama-3.2-3B-Instruct-JQ4        ~1.8 GB
    echo     tjake/Meta-Llama-3.1-8B-Instruct-JQ4   ~4.5 GB
    echo     tjake/Qwen2.5-7B-Instruct-JQ4          ~4.2 GB
    echo     tjake/Mistral-7B-Instruct-v0.3-JQ4     ~4.1 GB
    echo.
    echo   Models saved to: %MODELS_DIR%
    echo.
    exit /b 1
)

if not exist "%JAR_PATH%" (
    echo Error: mkpro JAR not found at %JAR_PATH%
    echo Please run 'mvn package -DskipTests' first.
    exit /b 1
)

echo.
echo   Downloading: %~1
echo   Destination: %MODELS_DIR%
echo.

if not exist "%MODELS_DIR%" mkdir "%MODELS_DIR%"

java -cp "%JAR_PATH%" com.mkpro.models.JlamaModelDownloader "%~1" "%MODELS_DIR%"

endlocal
