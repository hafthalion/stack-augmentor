@echo off
rem Builds the project and runs one of the examples, the same way an application would:
rem   run.bat [example] [JVM options]     example: agent, live or build-time
rem Without an example name, it asks which one to run.
rem
rem   agent       examples\java-agent: the Java agent instruments classes, ids from annotations and from
rem               stack-augmentor.toml, and a simple cause chain printed both ways:
rem                 java -javaagent:<agent jar>=config=<stack-augmentor.toml> -cp <classpath> <main class>
rem   live        examples\live-agent, twice: with the agent alone, and in the agent's live-stack mode with the
rem               native library as well, which shows ids on every frame:
rem                 java -agentpath:<native library> -javaagent:<agent jar>=config=<stack-augmentor.toml> -cp <classpath> <main class>
rem               Without MinGW gcc on the PATH there is no native library, and only the first runs.
rem   build-time  examples\build-time: classes instrumented when they were built, no agent:
rem                 java -cp <classpath> <main class>
rem
rem Builds everything first (including the tests), plus the example's lib folder.
rem Options after the example name are passed to the JVM, e.g.  run.bat live -Xshare:off

setlocal
cd /d "%~dp0"

set AGENT_JAR=stack-augmentor-agent\build\libs\stack-augmentor-agent-0.1.0-SNAPSHOT.jar
set NATIVE_LIB=%~dp0stack-augmentor-native\build\native\stackaugmentor.dll

set EXAMPLE=%~1
set JVM_ARGS=
rem Only JVM options, e.g.  run.bat -Xshare:off : ask for the example, pass them all on.
if "%EXAMPLE:~0,1%"=="-" set EXAMPLE=
if "%EXAMPLE%"=="" set JVM_ARGS=%*
if /i "%EXAMPLE%"=="agent" goto named
if /i "%EXAMPLE%"=="live" goto named
if /i "%EXAMPLE%"=="build-time" goto named
if not "%EXAMPLE%"=="" (
    echo Unknown example: %EXAMPLE%. Use agent, live or build-time.
    endlocal & exit /b 1
)

echo Which example?
echo   1. agent       Java agent, with a cause chain
echo   2. live        Java agent alone, then in the live-stack mode
echo   3. build-time  build-time instrumentation, no agent
choice /c 123 /n /m "Example [1-3]: "
if errorlevel 3 (set EXAMPLE=build-time) else if errorlevel 2 (set EXAMPLE=live) else (set EXAMPLE=agent)
goto build

:named
rem Everything after the example name goes to the JVM.
for /f "tokens=1,*" %%a in ("%*") do set JVM_ARGS=%%b

:build
if /i "%EXAMPLE%"=="agent" (
    set PROJECT=java-agent
    set MAIN=com.hafnium.examples.agent.Main
)
if /i "%EXAMPLE%"=="live" (
    set PROJECT=live-agent
    set MAIN=com.hafnium.examples.live.Main
)
if /i "%EXAMPLE%"=="build-time" (
    set PROJECT=build-time
    set MAIN=com.hafnium.examples.buildtime.Main
)
set CONFIG=examples\%PROJECT%\stack-augmentor.toml
set CLASSPATH=examples\%PROJECT%\build\install\%PROJECT%\lib\*

call "%~dp0gradlew.bat" build :examples:%PROJECT%:installDist
if %ERRORLEVEL% neq 0 (
    echo Build FAILED. Test reports: stack-augmentor-*\build\reports\tests
    endlocal & exit /b 1
)

if /i "%EXAMPLE%"=="agent" goto agent
if /i "%EXAMPLE%"=="build-time" goto buildTime

echo.
echo === 1. Agent alone, instrumenting classes: %AGENT_JAR%
echo.
java %JVM_ARGS% -javaagent:%AGENT_JAR%=config=%CONFIG% -cp "%CLASSPATH%" %MAIN%
if %ERRORLEVEL% neq 0 (
    endlocal & exit /b 1
)

echo.
if not exist "%NATIVE_LIB%" goto noNativeLibrary
echo === 2. Live-stack mode: %AGENT_JAR% with %NATIVE_LIB%
echo.
java %JVM_ARGS% "-agentpath:%NATIVE_LIB%" -javaagent:%AGENT_JAR%=config=%CONFIG% -cp "%CLASSPATH%" %MAIN%
if %ERRORLEVEL% neq 0 (
    endlocal & exit /b 1
)
endlocal & exit /b 0

:noNativeLibrary
echo === 2. Live-stack mode skipped: no %NATIVE_LIB%, which needs MinGW gcc on the PATH
endlocal & exit /b 0

:agent
echo.
echo === Java agent: %AGENT_JAR%
echo.
java %JVM_ARGS% -javaagent:%AGENT_JAR%=config=%CONFIG% -cp "%CLASSPATH%" %MAIN%
if %ERRORLEVEL% neq 0 (
    endlocal & exit /b 1
)
endlocal & exit /b 0

:buildTime
echo.
echo === Build-time instrumentation, no agent
echo.
java %JVM_ARGS% -cp "%CLASSPATH%" %MAIN%
if %ERRORLEVEL% neq 0 (
    endlocal & exit /b 1
)
endlocal & exit /b 0
