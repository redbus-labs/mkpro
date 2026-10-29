rem // mkpro-worker - RabbitMQ Task Worker Mode
@echo off
setlocal
set "JDK_JAVA_OPTIONS=--add-modules jdk.incubator.vector --enable-preview -Dfile.encoding=UTF-8"

:: Get the directory where this script resides
set "SCRIPT_DIR=%~dp0"

:: Define the path to the shaded JAR
set "JAR_PATH=%SCRIPT_DIR%target\mkpro-4.9.0.jar"

:: Check if the JAR exists
if not exist "%JAR_PATH%" (
    echo Error: mkpro JAR not found at %JAR_PATH%
    echo Please run 'mvn package -DskipTests' first.
    exit /b 1
)

:: Start mkpro in RabbitMQ worker mode
java --add-modules jdk.incubator.vector --enable-preview -Dmkpro.db.name=%~n0 -jar "%JAR_PATH%" -r 1 --enable-registry --worker %*

endlocal
