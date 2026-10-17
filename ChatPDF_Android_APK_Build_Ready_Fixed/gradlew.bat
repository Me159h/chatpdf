@echo off
set APP_HOME=%~dp0
if not exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  echo Gradle wrapper JAR is missing. Use Android Studio with a local Gradle installation or GitHub Actions.
  exit /b 1
)
java -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
