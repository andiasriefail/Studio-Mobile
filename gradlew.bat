@echo off
set DIRNAME=%~dp0
set CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar
if not exist "%CLASSPATH%" (
  echo ERROR: gradle\wrapper\gradle-wrapper.jar is missing.
  echo Generate it using Gradle 8.9: gradle wrapper --gradle-version 8.9 --distribution-type bin
  exit /b 1
)
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
