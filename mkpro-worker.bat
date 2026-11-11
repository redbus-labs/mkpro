rem // mkpro-worker - RabbitMQ Task Worker Mode
@echo off
setlocal
set "JDK_JAVA_OPTIONS=--add-modules jdk.incubator.vector --enable-preview -Dfile.encoding=UTF-8"

:: Get the directory where this script resides
set "SCRIPT_DIR=%~dp0"

:: Define the path to the shaded JAR
set "JAR_PATH=%SCRIPT_DIR%target\mkpro-5.0.0.jar"

:: Check if the JAR exists
if not exist "%JAR_PATH%" (
    echo Error: mkpro JAR not found at %JAR_PATH%
    echo Please run 'mvn package -DskipTests' first.
    exit /b 1
)

:: Start mkpro in RabbitMQ worker mode
copy /Y "%JAR_PATH%" "%SCRIPT_DIR%target\mkpro-runtime.jar" >nul
java --add-modules jdk.incubator.vector --enable-preview -Dmkpro.db.name=%~n0 -jar "%SCRIPT_DIR%target\mkpro-runtime.jar" -r 1 --enable-registry --worker %*

endlocal
