@echo off
rem Play Magic against Claude on Windows. macOS and Linux users run play.sh instead.
rem
rem   play.bat
rem       Planner on Opus 5 at medium effort, interrupts on Sonnet 5 at low effort.
rem   set CLAUDE_PLAN_EFFORT=high
rem   play.bat
rem       Maximum cunning, slower turns.
rem
rem In Forge: gear icon for Preferences, then "AI Personality", then Claude. Start any
rem match against the AI. Claude's table talk appears in the game log panel, and the
rem decision ledger JSONL lands in the claude-logs directory. The full path is printed
rem to this console when the ledger opens.
rem
rem First-time setup: Forge reads its assets from the working directory, so run\res has
rem to exist before the first launch. Make it a junction from this folder:
rem     cd run
rem     mklink /J res ..\forge-gui\res
rem A junction needs no administrator rights. Copying forge\forge-gui\res to run\res
rem also works and costs about half a gigabyte of disk.
rem
rem Every environment knob, every log path, and the res-directory setup: docs\launch.md

setlocal
pushd "%~dp0"

set "JAR=%~dp0forge-gui-desktop\target\forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar"

rem LLM_OPTS carries extra -Dllm.* flags for other providers. When set, no Anthropic key is required.
if not defined LLM_OPTS if not defined ANTHROPIC_API_KEY (
  echo ANTHROPIC_API_KEY is not set. The LLM cannot take a seat without it.
  echo For this console only:  set ANTHROPIC_API_KEY=sk-ant-...
  echo For every console:      setx ANTHROPIC_API_KEY sk-ant-...
  echo A setx value only reaches consoles opened after you run it.
  echo Using another provider? Set LLM_OPTS with your -Dllm.* flags instead.
  goto :fail
)

java -version 1>nul 2>nul || (
  echo Java was not found on PATH. Forge needs Java 17 or newer.
  goto :failjava
)

for /f tokens^=2^ delims^=.-_^+^" %%j in ('java -fullversion 2^>^&1') do set "jver=%%j"
if not defined jver (
  echo Could not read the Java version. Forge needs Java 17 or newer.
  goto :failjava
)
if %jver% LEQ 16 (
  echo Java %jver% is too old. Forge needs Java 17 or newer.
  goto :failjava
)

rem Tests a load-bearing subdirectory, so a hand-made empty run\res fails here too.
if not exist "run\res\cardsfolder" (
  echo missing or incomplete run\res
  echo Forge loads its cards and art from a res directory beside the working directory.
  echo Create it as a junction, from this folder:
  echo     cd run
  echo     mklink /J res ..\forge-gui\res
  echo Or copy forge\forge-gui\res to run\res.
  goto :fail
)

if not exist "%JAR%" (
  echo missing %JAR%
  echo Build it, from this folder:
  echo     cd forge
  echo     mvn -DskipTests package
  goto :fail
)

if not defined CLAUDE_FORCE set "CLAUDE_FORCE=true"
if not defined CLAUDE_PLAN_MODEL set "CLAUDE_PLAN_MODEL=claude-opus-5"
if not defined CLAUDE_PLAN_EFFORT set "CLAUDE_PLAN_EFFORT=medium"
if not defined CLAUDE_FAST_MODEL set "CLAUDE_FAST_MODEL=claude-sonnet-5"
if not defined CLAUDE_FAST_EFFORT set "CLAUDE_FAST_EFFORT=low"
if not defined CLAUDE_PERSONA set "CLAUDE_PERSONA=sydney"
if not defined CLAUDE_NAME set "CLAUDE_NAME=Sydney"
if not defined CLAUDE_THINKING set "CLAUDE_THINKING=3000"
if not defined CLAUDE_TIMEOUT set "CLAUDE_TIMEOUT=45"

cd run

rem No apple.awt.* flags here. Those are macOS window hints and play.sh keeps them
rem behind a darwin guard.
rem The claude.* property names are the long-lived ones. A parallel change adds llm.*
rem aliases that fall back to these, so nothing below has to move when that lands.
java -Xmx4g "-Dfile.encoding=UTF-8" ^
  "-Dclaude.force=%CLAUDE_FORCE%" ^
  "-Dclaude.model.plan=%CLAUDE_PLAN_MODEL%" ^
  "-Dclaude.effort.plan=%CLAUDE_PLAN_EFFORT%" ^
  "-Dclaude.model.fast=%CLAUDE_FAST_MODEL%" ^
  "-Dclaude.effort.fast=%CLAUDE_FAST_EFFORT%" ^
  "-Dclaude.persona=%CLAUDE_PERSONA%" ^
  "-Dclaude.name=%CLAUDE_NAME%" ^
  "-Dclaude.thinking.budget=%CLAUDE_THINKING%" ^
  "-Dclaude.timeout.seconds=%CLAUDE_TIMEOUT%" ^
  %LLM_OPTS% ^
  -jar "%JAR%"

set "rc=%ERRORLEVEL%"
popd
endlocal & exit /b %rc%

:fail
popd
endlocal & exit /b 1

:failjava
popd
endlocal & exit /b 2
