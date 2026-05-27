@echo off
echo ============================================================
echo  CPM Triggers Addon - Windows Setup Helper
echo ============================================================
echo.
echo This script will download the Gradle wrapper jar and then
echo generate the IntelliJ IDEA run configurations.
echo.
echo Requirements:
echo   - Java 17 must be installed
echo   - Internet connection required
echo.

REM Check for Java
java -version >NUL 2>&1
if %ERRORLEVEL% neq 0 (
    echo ERROR: Java is not installed or not in PATH.
    echo Please install Java 17 from https://adoptium.net/
    pause
    exit /b 1
)

echo [1/3] Downloading Gradle wrapper jar...
REM Use PowerShell to download the gradle-wrapper.jar
powershell -Command "Invoke-WebRequest -Uri 'https://github.com/nicholaswilde/gradle-wrapper/raw/main/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'" 2>NUL
if %ERRORLEVEL% neq 0 (
    REM Try alternate source
    powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('https://raw.githubusercontent.com/gradle/gradle/v8.1.1/gradle/wrapper/gradle-wrapper.jar', 'gradle\wrapper\gradle-wrapper.jar')}"
)

if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo.
    echo ERROR: Could not download gradle-wrapper.jar automatically.
    echo.
    echo Please download it manually:
    echo  1. Go to: https://github.com/gradle/gradle/releases/tag/v8.1.1
    echo  2. Download the source zip
    echo  3. Extract gradle/wrapper/gradle-wrapper.jar into this project's
    echo     gradle/wrapper/ folder
    echo.
    pause
    exit /b 1
)

echo [2/3] Gradle wrapper jar ready!
echo.
echo [3/3] Generating IntelliJ IDEA run configurations...
.\gradlew.bat genIntellijRuns

if %ERRORLEVEL% equ 0 (
    echo.
    echo ============================================================
    echo  SUCCESS! Now open this folder in IntelliJ IDEA.
    echo  Use Run ^> runClient to launch Minecraft.
    echo ============================================================
) else (
    echo.
    echo Setup encountered an error. Check the output above.
    echo Common fixes:
    echo   - Make sure Java 17 is installed ^(not Java 8 or 21^)
    echo   - Check your internet connection
    echo   - Update version numbers in gradle.properties
)
pause
