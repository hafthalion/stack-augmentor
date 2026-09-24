@echo off
rem Runs the demo (examples\demo) with the stack augmentor agent attached, the same way an
rem application would: java -javaagent:<agent jar>=config=<properties> -cp <classpath> <main class>
rem
rem Builds everything first (including the tests), plus the demo's lib folder.
rem Extra arguments are passed to the JVM, e.g.  run.bat -Xshare:off

setlocal
cd /d "%~dp0"

set AGENT_JAR=stack-augmentor-agent\build\libs\stack-augmentor-agent-0.1.0-SNAPSHOT.jar
set CONFIG=examples\demo\stack-augmentor.properties
set CLASSPATH=examples\demo\build\install\demo\lib\*

call "%~dp0gradlew.bat" build :examples:demo:installDist
if %ERRORLEVEL% neq 0 (
    echo Build FAILED. Test reports: stack-augmentor-*\build\reports\tests
    endlocal & exit /b 1
)

echo.
echo Running demo with %AGENT_JAR%
echo.
java %* -javaagent:%AGENT_JAR%=config=%CONFIG% -cp "%CLASSPATH%" com.hafnium.Main
rem The demo ends with an uncaught exception on purpose, so a non-zero exit code is expected.
endlocal & exit /b 0
