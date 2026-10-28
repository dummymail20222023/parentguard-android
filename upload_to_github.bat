@echo off
cd /d "%~dp0"
SET REPO_URL=https://github.com/dummymail20222023/parentguard-android.git
SET ACTIONS_URL=https://github.com/dummymail20222023/parentguard-android/actions

echo ============================================================
echo  ParentGuard Android - Automated GitHub Push and APK Builder
echo  Folder: %CD%
echo  Target: %REPO_URL%
echo ============================================================

git init
git branch -M main
git add -A
git commit -m "Fix Gradle 8.10.2 workflow and module directories for APK build"

git remote remove origin 2>NUL
git remote add origin %REPO_URL%

echo.
echo Pushing fixed code to GitHub...
git push -u origin main --force

echo.
echo Done! Opening GitHub Actions in your browser...
start "" "%ACTIONS_URL%"
pause
