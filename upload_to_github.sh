#!/usr/bin/env bash
set -e

REPO_URL="https://github.com/dummymail20222023/parentguard-android.git"
ACTIONS_URL="https://github.com/dummymail20222023/parentguard-android/actions"

echo "============================================================"
echo " ParentGuard Android — Automated GitHub Push & APK Builder"
echo " Target Repository: $REPO_URL"
echo "============================================================"

if [ ! -d ".git" ]; then
    git init
fi

git branch -M main
git add .
git commit -m "Build ParentGuard Child Agent & Parent Monitor APKs" || echo "Changes already committed"

if git remote | grep -q "^origin$"; then
    git remote set-url origin "$REPO_URL"
else
    git remote add origin "$REPO_URL"
fi

echo "Pushing source code and GitHub Actions workflow to $REPO_URL ..."
git push -u origin main --force

echo ""
echo "SUCCESS! Your code is now on GitHub and the APK build has started."
echo "Open this link in 2-3 minutes to download both .apk files:"
echo "  -> $ACTIONS_URL"
