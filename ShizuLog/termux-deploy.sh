#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="opfans432/ShizuLog"
REMOTE="https://github.com/${REPO}.git"
WORKFLOW="build-release.yml"
TAG="v1.0.0"

cd "$(dirname "$0")"

echo "== ShizuLog Termux 部署 =="
echo "仓库: https://github.com/${REPO}"

echo "[1/7] 安装 Git / GitHub CLI..."
pkg update -y
pkg install -y git gh

echo "[2/7] 登录 GitHub..."
if ! gh auth status --hostname github.com >/dev/null 2>&1; then
  echo "接下来会进行 GitHub 网页授权，请按 Termux 提示完成。"
  gh auth login --hostname github.com --git-protocol https --web
fi
gh auth setup-git

echo "[3/7] 配置 Git 提交署名..."
git config user.name "ChatGPT"
git config user.email "chatgpt@users.noreply.github.com"

if [ ! -d .git ]; then
  git init -b main
fi
git branch -M main
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REMOTE"
else
  git remote add origin "$REMOTE"
fi

echo "[4/7] 提交 ShizuLog v1.0.0..."
git add -A
if ! git diff --cached --quiet; then
  git commit -m "Initial release: ShizuLog v1.0.0"
else
  echo "没有新的本地改动需要提交。"
fi

echo "[5/7] 推送到 GitHub main..."
git push -u origin main

# 尽量保证 Actions 的 GITHUB_TOKEN 有发布 Release 所需的 contents:write。
gh api -X PUT "repos/${REPO}/actions/permissions/workflow" \
  -f default_workflow_permissions=write \
  -F can_approve_pull_request_reviews=false >/dev/null 2>&1 || true

echo "[6/7] 等待 GitHub Actions 开始构建..."
RUN_ID=""
for _ in $(seq 1 30); do
  RUN_ID="$(gh run list -R "$REPO" --workflow "$WORKFLOW" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
  if [ -n "$RUN_ID" ] && [ "$RUN_ID" != "null" ]; then
    break
  fi
  sleep 3
done

if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "null" ]; then
  echo "没有检测到 Actions 运行。请打开仓库 Actions 页面检查工作流是否启用。"
  exit 2
fi

echo "Actions Run ID: $RUN_ID"
if ! gh run watch "$RUN_ID" -R "$REPO" --exit-status; then
  echo
  echo "构建失败，下面输出失败步骤日志："
  gh run view "$RUN_ID" -R "$REPO" --log-failed || true
  exit 3
fi

echo "[7/7] 下载 Release APK..."
if [ -d "$HOME/storage/downloads" ]; then
  DEST="$HOME/storage/downloads/ShizuLog-release"
else
  DEST="$HOME/ShizuLog-release"
fi
mkdir -p "$DEST"

# 工作流成功后应创建 v1.0.0 Release。
for _ in $(seq 1 20); do
  if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
    break
  fi
  sleep 3
done

gh release download "$TAG" -R "$REPO" -p '*.apk' -p '*.sha256' -D "$DEST" --clobber

echo
echo "========================================"
echo "部署完成。"
echo "GitHub: https://github.com/${REPO}"
echo "Release: https://github.com/${REPO}/releases/tag/${TAG}"
echo "APK 已下载到: $DEST"
echo "========================================"
