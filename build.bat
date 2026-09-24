@echo off
rem Builds all modules and runs the tests (unit, rewrite mode and registry mode).
rem Extra arguments are passed to Gradle, e.g.  build.bat --info  or  build.bat -x test
rem The agent jar ends up in stack-augmentor-agent\build\libs.

setlocal
cd /d "%~dp0"

call "%~dp0gradlew.bat" build %*
set EXIT_CODE=%ERRORLEVEL%

if %EXIT_CODE% neq 0 (
    echo.
    echo Build FAILED. Test reports: stack-augmentor-*\build\reports\tests
) else (
    echo.
    echo Build succeeded. Agent jar: stack-augmentor-agent\build\libs\
)

endlocal & exit /b %EXIT_CODE%
