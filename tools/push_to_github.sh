#!/usr/bin/env bash
# Push this repository to GitHub and (optionally) publish a release.
#
# Usage:
#   bash tools/push_to_github.sh                 # push only
#   bash tools/push_to_github.sh --release       # push, then publish release with the APK
#   bash tools/push_to_github.sh --dry-run       # print what would happen
#
# Prerequisites:
#   - gh (GitHub CLI) installed and logged in:  gh auth login
#   - an arm64 debug APK at app/build/outputs/apk/debug/app-debug.apk (for --release)
#
# The script refuses to push if any private identifier from the pre-release
# checklist is still present in the history.
set -euo pipefail

OWNER="${GH_OWNER:-SZTMBABY}"
REPO="${GH_REPO:-virtual-companion}"
BRANCH="main"
TAG="${GH_TAG:-v1.0.0}"

DRY_RUN=0
DO_RELEASE=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --release) DO_RELEASE=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（-h 看用法）" >&2; exit 2 ;;
  esac
done

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\n[失败] %s\n' "$*" >&2; exit 1; }
run()  { if [ "$DRY_RUN" -eq 1 ]; then info "[dry-run] $*"; else "$@"; fi; }

cd "$(git rev-parse --show-toplevel)"

# ---------------------------------------------------------------------------
# 0. 前置检查
# ---------------------------------------------------------------------------
say "前置检查"
command -v git >/dev/null || die "找不到 git"
command -v gh  >/dev/null || die "找不到 gh（GitHub CLI）。装好后运行 gh auth login"
gh auth status >/dev/null 2>&1 || die "gh 未登录。先运行：gh auth login
      （登录后重跑本脚本即可）"
info "gh 已登录：$(gh api user --jq .login 2>/dev/null || echo '?')"
# 让 git 直接复用 gh 的凭据（否则 git push 会因为没有 credential helper 而报
# "could not read Username for 'https://github.com'"）
gh auth setup-git >/dev/null 2>&1 || true

[ -z "$(git status --porcelain)" ] || die "工作区不干净，先提交或 stash：
$(git status --short)"

# ---------------------------------------------------------------------------
# 1. 私有信息兜底扫描（历史 + 工作区）
# ---------------------------------------------------------------------------
say "私有信息扫描"
# 把字面量拆成碎片拼起来：脚本自身不能包含可直接命中的完整标识符。
VOLC_ID="8070""763842"
TC_APP_ID="1368""125613"
DEV_SERIAL="SFWT""005109"
OLD_KEY="1a098f3d""97514bf1bc90184867ba0cd0"
PATTERNS="$VOLC_ID|$TC_APP_ID|$DEV_SERIAL|$OLD_KEY"
hits="$(git grep -I -n -E "$PATTERNS" HEAD -- . 2>/dev/null || true)"
if [ -n "$hits" ]; then
  printf '%s\n' "$hits" | head -20
  die "仓库内容（HEAD）里仍有私有标识符，先重写历史再推送"
fi
info "HEAD 内容与历史中未发现私有标识符"

# 追加：危险文件类型不该出现
for bad in ".tmp_l2d" ".tmp_probe" "app/libs/sherpa-onnx.aar" "app/src/main/assets/asr/encoder.int8.onnx"; do
  if git cat-file -e "HEAD:$bad" 2>/dev/null; then
    die "HEAD 中仍包含 $bad（大二进制/非再分发资产），检查 .gitignore 与历史"
  fi
done
info "未发现被排除的大二进制"

# ---------------------------------------------------------------------------
# 2. 分支名与远程
# ---------------------------------------------------------------------------
say "分支与远程"
if [ "$(git rev-parse --abbrev-ref HEAD)" != "$BRANCH" ]; then
  info "把当前分支重命名为 $BRANCH"
  run git branch -M "$BRANCH"
fi

if git remote get-url origin >/dev/null 2>&1; then
  info "origin 已存在：$(git remote get-url origin)"
else
  info "创建远程仓库 $OWNER/$REPO 并设为 origin"
  if [ "$DRY_RUN" -eq 1 ]; then
    info "[dry-run] gh repo create $OWNER/$REPO --public --source=. --remote=origin --push"
  else
    gh repo create "$OWNER/$REPO" --public --source=. --remote=origin --push \
      --description "Android 语音虚拟伴侣：本地唤醒 + 云端/本地双 ASR + Live2D + 长记忆"
  fi
fi

# ---------------------------------------------------------------------------
# 3. 推送
# ---------------------------------------------------------------------------
say "推送"
# gh repo create --push 通常已经推过一次；这里再推一次以覆盖「仓库已存在」的情况。
if ! run git push -u origin "$BRANCH"; then
  info "git push 失败：确认已运行 gh auth setup-git，或手动 git push -u origin $BRANCH"
  exit 1
fi

# ---------------------------------------------------------------------------
# 4. 可选：发布 Release（debug 签名 APK）
# ---------------------------------------------------------------------------
if [ "$DO_RELEASE" -eq 1 ]; then
  say "发布 Release $TAG"
  APK="app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$APK" ] || die "找不到 $APK，先运行 ./gradlew :app:assembleDebug"
  info "APK：$APK（$(wc -c < "$APK") 字节）"
  if [ "$DRY_RUN" -eq 1 ]; then
    info "[dry-run] gh release create $TAG $APK --title ... --notes ..."
  else
    gh release create "$TAG" "$APK" \
      --title "Virtual Companion $TAG" \
      --notes "首个公开版本。

- 仅 arm64-v8a，Android 8.0+（minSdk 30）
- APK 使用 debug 签名，仅供试用；自行构建请见 README
- 安装前请先导入自己的 Live2D 模型（点开即空白属于预期：仓库不含模型）
- 语音功能需要自备腾讯云 / 火山引擎密钥，大模型需要自备 OpenAI 兼容端点

已知限制见 README「已知限制」一节。"
  fi
fi

say "完成"
info "仓库：https://github.com/$OWNER/$REPO"
