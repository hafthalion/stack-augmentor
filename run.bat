@echo off
rem Runs the live-stack demo (examples\live-agent) twice, the same way an application would:
rem   1. with the agent alone, which instruments classes:
rem      java -javaagent:<agent jar>=config=<stack-augmentor.toml> -cp <classpath> <main class>
rem   2. in the agent's live-stack mode, with the native library as well:
rem      java -agentpath:<native library> -javaagent:<agent jar>=config=<stack-augmentor.toml> -cp <classpath> <main class>
rem Only the second shows ids on every frame. Without MinGW gcc on the PATH there is no native library,
rem and only the first runs.
rem
rem Builds everything first (including the tests), plus the demo's lib folder.
rem Extra arguments are passed to the JVM, e.g.  run.bat -Xshare:off

setlocal
cd /d "%~dp0"

set AGENT_JAR=stack-augmentor-agent\build\libs\stack-augmentor-agent-0.1.0-SNAPSHOT.jar
set NATIVE_LIB=%~dp0stack-augmentor-native\build\native\stackaugmentor.dll
set CONFIG=examples\live-agent\stack-augmentor.toml
set CLASSPATH=examples\live-agent\build\install\live-agent\lib\*
set MAIN=com.hafnium.examples.live.Main

call "%~dp0gradlew.bat" build :examples:live-agent:installDist
if %ERRORLEVEL% neq 0 (
    echo Build FAILED. Test reports: stack-augmentor-*\build\reports\tests
    endlocal & exit /b 1
)

echo.
echo === 1. Agent alone, instrumenting classes: %AGENT_JAR%
echo.
java %* -javaagent:%AGENT_JAR%=config=%CONFIG% -cp "%CLASSPATH%" %MAIN%
if %ERRORLEVEL% neq 0 (
    endlocal & exit /b 1
)

echo.
if not exist "%NATIVE_LIB%" goto noNativeLibrary
echo === 2. Live-stack mode: %AGENT_JAR% with %NATIVE_LIB%
echo.
java %* "-agentpath:%NATIVE_LIB%" -javaagent:%AGENT_JAR%=config=%CONFIG% -cp "%CLASSPATH%" %MAIN%
if %ERRORLEVEL% neq 0 (
    endlocal & exit /b 1
)
endlocal & exit /b 0

:noNativeLibrary
echo === 2. Live-stack mode skipped: no %NATIVE_LIB%, which needs MinGW gcc on the PATH
endlocal & exit /b 0
