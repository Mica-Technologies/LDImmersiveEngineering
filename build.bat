@echo off
rem
rem Runs the Gradle wrapper with a Java 8 JDK selected automatically.
rem
rem Why this exists: this machine setup deliberately keeps no `java` on PATH and no
rem global JAVA_HOME (see dev-configurations/jdk-gradle-setup), and org.gradle.java.home
rem cannot help - the wrapper needs a JVM to start *before* it reads any properties.
rem The result was that gradlew failed outright from a clean shell, so all builds
rem happened in the IDE, which is what kept rewriting the Gradle wrapper.
rem
rem Usage: build.bat [gradle args...]     e.g. build.bat clean build
setlocal

rem 1. An explicit override always wins.
if defined JAVA8_HOME (
    if exist "%JAVA8_HOME%\bin\java.exe" (
        set "JAVA_HOME=%JAVA8_HOME%"
        goto :found
    )
)

rem 2. The ~/.jdks convention used across these repos. Newest match wins.
for /f "delims=" %%D in ('dir /b /ad /o-n "%USERPROFILE%\.jdks\*1.8*" 2^>nul') do (
    if exist "%USERPROFILE%\.jdks\%%D\bin\java.exe" (
        set "JAVA_HOME=%USERPROFILE%\.jdks\%%D"
        goto :found
    )
)

echo No Java 8 JDK found. 1>&2
echo. 1>&2
echo ForgeGradle 2.3 ^(Minecraft 1.12.2^) only builds on JDK 8. Install one via 1>&2
echo IntelliJ ^(Project Structure ^> SDKs ^> Download JDK ^> Azul Zulu 8^), which 1>&2
echo places it in %%USERPROFILE%%\.jdks, or set JAVA8_HOME to an existing install. 1>&2
exit /b 1

:found
echo Using JDK: %JAVA_HOME%
call "%~dp0gradlew.bat" %*
exit /b %ERRORLEVEL%
