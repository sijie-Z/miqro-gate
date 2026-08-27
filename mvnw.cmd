@echo off
@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM Maven Wrapper launcher for MiQroKey Gateway (Windows).
@REM The POM lives in backend\; the wrapper runs from the repo root.

@setlocal enabledelayedexpansion
set "APP_HOME=%~dp0"
set "MAVEN_PROJECTBASEDIR=%APP_HOME%backend"
set "WRAPPER_JAR=%APP_HOME%.mvn\wrapper\maven-wrapper.jar"
set "WRAPPER_PROPERTIES=%APP_HOME%.mvn\wrapper\maven-wrapper.properties"

@REM Read expected wrapper JAR checksum from properties
set "EXPECTED_WRAPPER_SHA256="
if exist "!WRAPPER_PROPERTIES!" (
    for /f "usebackq tokens=1,2 delims==" %%a in ("!WRAPPER_PROPERTIES!") do (
        if "%%a"=="wrapperSha256Sum" set "EXPECTED_WRAPPER_SHA256=%%b"
    )
)

@REM Verify wrapper JAR checksum against the committed value
set "ACTUAL_WRAPPER_SHA256="
if exist "!WRAPPER_JAR!" (
    for /f "skip=1 tokens=*" %%h in ('certutil -hashfile "!WRAPPER_JAR!" SHA256') do (
        if not defined ACTUAL_WRAPPER_SHA256 set "ACTUAL_WRAPPER_SHA256=%%h"
    )
)

if not defined ACTUAL_WRAPPER_SHA256 goto :skip_verify
if not defined EXPECTED_WRAPPER_SHA256 goto :skip_verify
if /i "!ACTUAL_WRAPPER_SHA256!"=="!EXPECTED_WRAPPER_SHA256!" goto :skip_verify
echo ERROR: Maven Wrapper JAR checksum mismatch.
echo Expected: !EXPECTED_WRAPPER_SHA256!
echo Actual:   !ACTUAL_WRAPPER_SHA256!
echo Remove !WRAPPER_JAR! and re-run to download a fresh copy.
exit /b 1

:skip_verify
set "WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain"

set "MAVEN_OPTS=%MAVEN_OPTS% -Dfile.encoding=UTF-8"
@REM Allow JVM config overrides
if exist "%APP_HOME%.mvn\jvm.config" (
    for /F "usebackq delims=" %%a in ("%APP_HOME%.mvn\jvm.config") do set "MAVEN_OPTS=!MAVEN_OPTS! %%a"
)

@REM Execute Maven via the wrapper
java %MAVEN_OPTS% ^
  -classpath "%WRAPPER_JAR%" ^
  "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" ^
  %MAVEN_DEBUG_OPTS% ^
  %WRAPPER_LAUNCHER% ^
  -f "%MAVEN_PROJECTBASEDIR%\pom.xml" ^
  %*
set "MAVEN_EXIT_CODE=%ERRORLEVEL%"
@endlocal & exit /b %MAVEN_EXIT_CODE%
