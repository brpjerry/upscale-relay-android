@ECHO OFF
SETLOCAL
SET APP_HOME=%~dp0
SET JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
IF NOT EXIST "%JAR%" (
  ECHO Missing Gradle wrapper JAR. Run bootstrap-wrapper.ps1 first. 1>&2
  EXIT /B 1
)
IF DEFINED JAVA_HOME (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_EXE=java.exe
)
"%JAVA_EXE%" -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
EXIT /B %ERRORLEVEL%

