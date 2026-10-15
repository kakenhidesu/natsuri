@rem
@rem natsuri - A launcher and distribution manager for natsu.
@rem
@rem This file must stay pure ASCII: it switches the console code page while cmd.exe is
@rem still reading it, and non-ASCII bytes would then be re-decoded with the new page.
@rem
@echo off
setlocal EnableExtensions

set "APP_HOME=%~dp0"

@rem Strip surrounding quotes first. A quoted JAVA_HOME (set JAVA_HOME="C:\Program Files\...")
@rem is one of the most common Windows misconfigurations; leaving the quote in makes the
@rem parenthesised if/else below a cmd *syntax error*, so the script would die before printing
@rem anything useful. gradlew.bat carries the same line for the same reason.
if defined NATSURI_JAVA set "NATSURI_JAVA=%NATSURI_JAVA:"=%"
if defined JAVA_HOME set "JAVA_HOME=%JAVA_HOME:"=%"

@rem java lookup: NATSURI_JAVA > JAVA_HOME > PATH.
@rem This is only the JVM that runs natsuri itself. Which JVM runs natsu is decided by
@rem natsuri.json (java.path) and the release manifest (javaMin) -- see "natsuri doctor".
@rem
@rem Both variables accept either a JDK/JRE directory or the java executable itself, matching
@rem what the Unix script does and what natsuri.json's java.path accepts.
@rem The subroutine reports failure through NATSURI_JAVA_BAD rather than "exit /b 1": inside a
@rem "call :label" that only leaves the subroutine, so the script would print the diagnostic and
@rem then cheerfully run anyway.
set "JAVA_EXE="
set "NATSURI_JAVA_BAD="
if defined NATSURI_JAVA call :resolve_java "%NATSURI_JAVA%" NATSURI_JAVA
if defined NATSURI_JAVA_BAD exit /b 1
if not defined JAVA_EXE if defined JAVA_HOME call :resolve_java "%JAVA_HOME%" JAVA_HOME
if defined NATSURI_JAVA_BAD exit /b 1
if not defined JAVA_EXE set "JAVA_EXE=java.exe"

@rem natsu prints Chinese logs and its own distribution launches it with
@rem -Dsun.stdout.encoding=UTF-8. On a console whose code page is not UTF-8 (cp936 on Chinese
@rem Windows) that output renders as mojibake, so switch to 65001 for the duration of the run
@rem and put the original page back afterwards. Set NATSURI_NO_CHCP=1 to skip this entirely.
@rem
@rem The current page is captured through a :remember subroutine rather than inline: a whole
@rem parenthesised block is parsed at once, so a "%VAR%" written inside the same block would
@rem expand to the value from *before* the for loop ran -- i.e. to nothing, and the page would
@rem never be restored.
set "NATSURI_OLD_CP="
if not defined NATSURI_NO_CHCP (
    for /f "tokens=2 delims=:" %%a in ('chcp 2^>nul') do call :remember %%a
    chcp 65001 >nul 2>&1
)

@rem %* keeps the original argument tail verbatim, which is what makes
@rem   natsuri run -- --no-console
@rem reach natsuri unmangled.
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%APP_HOME%natsuri.jar" %*
set "NATSURI_EXIT=%ERRORLEVEL%"

if defined NATSURI_OLD_CP chcp %NATSURI_OLD_CP% >nul 2>&1

@rem %NATSURI_EXIT% is expanded while this line is parsed, i.e. before endlocal takes effect.
endlocal & exit /b %NATSURI_EXIT%

:remember
@rem %~1 drops the leading space that "chcp" leaves after the colon.
set "NATSURI_OLD_CP=%~1"
goto :eof

:resolve_java
@rem %1 = a directory or a java executable, %2 = the variable name (for the error message).
@rem Sets JAVA_EXE when it finds one; prints a diagnostic and exits when the value is bogus.
if exist "%~1\bin\java.exe" (
    set "JAVA_EXE=%~1\bin\java.exe"
    goto :eof
)
if exist "%~1\java.exe" (
    set "JAVA_EXE=%~1\java.exe"
    goto :eof
)
if exist "%~1" (
    set "JAVA_EXE=%~1"
    goto :eof
)
echo natsuri: %~2 points at "%~1", which is not a java executable, a bin directory or a JDK root. 1>&2
echo natsuri: fix it, or unset it to fall back to PATH. 1>&2
set "NATSURI_JAVA_BAD=1"
goto :eof
