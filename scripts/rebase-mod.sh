#!/usr/bin/env bash
# 一键把 mod 跟到上游最新 tag：变基 → 改版本号 → 推 mod-build → 等 CI → 校验 → 发布。
#
# 用法：
#   source /root/gh.env && ./scripts/rebase-mod.sh            # 跟到上游最新 tag
#   source /root/gh.env && ./scripts/rebase-mod.sh v1.9.2     # 指定 tag
#   DRY_RUN=1 ./scripts/rebase-mod.sh                         # 只打印计划，不动仓库/不推送
#
# 前置：本机有上游 remote 的 token 能力（推送用 GH_TOKEN），Android SDK（校验 APK 的 aapt2）。
# 产物：http://154.64.231.117:8091/i/k7m2q/CPPlayer-<版本>-mod.apk，并自动删掉上一个版本的包。
set -euo pipefail

UPSTREAM=${UPSTREAM:-https://github.com/Aurora-Nasa-1/CPPlayer-KMP.git}
FORK_REPO=${FORK_REPO:-Ch667/CPPlayer-KMP}
BRANCH=${BRANCH:-mod-build}
PUBLISH_DIR=${PUBLISH_DIR:-/www/wwwroot/dl/i/k7m2q}
SITE=${SITE:-http://154.64.231.117:8091/i/k7m2q}
SDK=${ANDROID_HOME:-/root/sdk}
TARGET_TAG=${1:-}
DRY_RUN=${DRY_RUN:-0}

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m!! %s\033[0m\n' "$*" >&2; exit 1; }

[ -f gradlew ] || die "请在 CPPlayer-KMP 仓库根目录运行"
if [ "$DRY_RUN" != "1" ]; then
  : "${GH_TOKEN:?需要 GH_TOKEN（source /root/gh.env）}"
fi

# ---------------------------------------------------------------- 1. 目标版本
say "拉取上游 tag"
git fetch -q --no-tags "$UPSTREAM" 'refs/tags/*:refs/tags/*' 2>/dev/null || git fetch -q --tags "$UPSTREAM"
LATEST=$(git tag -l 'v[0-9]*' --sort=-v:refname | head -1)
TARGET=${TARGET_TAG:-$LATEST}
VERSION=${TARGET#v}
git rev-parse -q --verify "refs/tags/$TARGET" >/dev/null || die "找不到 tag $TARGET"

BASE=$(git describe --tags --match 'v[0-9]*' --abbrev=0 HEAD)
echo "上游最新: $LATEST"
echo "当前基线: $BASE"
echo "目标版本: $TARGET -> $VERSION"

if [ "$BASE" = "$TARGET" ]; then
  say "已经在 $TARGET 上，无需变基"
  exit 0
fi

# ---------------------------------------------------------------- 2. 变基
say "变基 $BASE -> $TARGET"
if [ "$DRY_RUN" = "1" ]; then
  echo "DRY_RUN: git rebase --onto $TARGET $BASE"
  git log --oneline "$BASE..HEAD" | sed 's/^/  将重放: /'
  exit 0
fi
git rebase --onto "$TARGET" "$BASE" || die "变基有冲突，手工解决后执行 git rebase --continue 再重跑本脚本"

# ---------------------------------------------------------------- 3. 版本号
say "写版本号 $VERSION"
sed -i "s/^app.versionName=.*/app.versionName=$VERSION/" gradle.properties
sed -i "s/-Papp.versionName=[0-9][0-9.]*/-Papp.versionName=$VERSION/" .github/workflows/build-mod.yml
sed -i "s/name: CPPlayer-[0-9][0-9.]*-mod/name: CPPlayer-$VERSION-mod/" .github/workflows/build-mod.yml
grep -q "Papp.versionName=$VERSION" .github/workflows/build-mod.yml || die "workflow 版本号没写进去"
git add -A
git commit -q -m "build: $VERSION 版本号" || true

# ---------------------------------------------------------------- 4. 推送
say "推送到 $BRANCH"
REMOTE_SHA=$(git ls-remote "https://x-access-token:${GH_TOKEN}@github.com/${FORK_REPO}.git" "refs/heads/$BRANCH" | cut -f1)
SHA=$(git rev-parse HEAD)
git push -q --force-with-lease="$BRANCH:${REMOTE_SHA}" \
  "https://x-access-token:${GH_TOKEN}@github.com/${FORK_REPO}.git" "HEAD:$BRANCH"
echo "已推送 $SHA"

# ---------------------------------------------------------------- 5. 等 CI
say "等待 GitHub Actions（通常 8~12 分钟）"
API="https://api.github.com/repos/${FORK_REPO}/actions"
auth=(-H "Authorization: Bearer ${GH_TOKEN}" -H "Accept: application/vnd.github+json")
RUN_ID=""
for _ in $(seq 1 60); do
  RUN_ID=$(curl -s -m 20 "${auth[@]}" "$API/runs?head_sha=$SHA&per_page=1" |
    python3 -c 'import sys,json;r=(json.load(sys.stdin).get("workflow_runs") or [{}])[0];print(r.get("id",""))')
  [ -n "$RUN_ID" ] && break
  sleep 5
done
[ -n "$RUN_ID" ] || die "没找到对应的 CI run"

STATUS=""
for _ in $(seq 1 120); do
  read -r STATUS CONCLUSION <<<"$(curl -s -m 20 "${auth[@]}" "$API/runs/$RUN_ID" |
    python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("status",""), d.get("conclusion") or "-")')"
  printf '\r  run %s: %s %s   ' "$RUN_ID" "$STATUS" "$CONCLUSION"
  [ "$STATUS" = "completed" ] && break
  sleep 15
done
echo
[ "$CONCLUSION" = "success" ] || die "CI 失败（run $RUN_ID），看 https://github.com/${FORK_REPO}/actions/runs/$RUN_ID"

# ---------------------------------------------------------------- 6. 下载 & 校验
say "下载产物"
ART=$(curl -s -m 20 "${auth[@]}" "$API/runs/$RUN_ID/artifacts" |
  python3 -c 'import sys,json;a=json.load(sys.stdin).get("artifacts") or [];print(a[0]["archive_download_url"] if a else "")')
[ -n "$ART" ] || die "没有 artifact"
TMP=$(mktemp -d)
curl -sL -m 600 -H "Authorization: Bearer ${GH_TOKEN}" "$ART" -o "$TMP/a.zip"
unzip -q -o "$TMP/a.zip" -d "$TMP/x"
APK=$(find "$TMP/x" -name '*.apk' | head -1)
[ -n "$APK" ] || die "artifact 里没有 apk"

BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
if [ -n "${BT:-}" ] && [ -x "$BT/aapt2" ]; then
  JAVA_HOME=${JAVA_HOME:-/root/jdk} PATH="${JAVA_HOME}/bin:$PATH" \
    "$BT/aapt2" dump badging "$APK" | head -1 | grep -q "versionName='$VERSION'" \
    || die "APK 里 versionName 不是 $VERSION"
  echo "versionName 校验通过"
fi

# ---------------------------------------------------------------- 7. 发布
say "发布到 $SITE"
OUT="$PUBLISH_DIR/CPPlayer-${VERSION}-mod.apk"
cp "$APK" "$OUT"
chmod 644 "$OUT"
# 清掉旧版本包（只保留当前这个 + 其它非 apk 文件）
find "$PUBLISH_DIR" -maxdepth 1 -name 'CPPlayer-*-mod*.apk' ! -name "CPPlayer-${VERSION}-mod.apk" -delete
SHA256=$(sha256sum "$OUT" | cut -d' ' -f1)
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$SITE/CPPlayer-${VERSION}-mod.apk")
[ "$CODE" = "200" ] || die "公网下载校验失败（HTTP $CODE）"

say "完成"
cat <<EOF
 版本   : $VERSION（commit $SHA，CI run $RUN_ID）
 下载   : $SITE/CPPlayer-${VERSION}-mod.apk
 sha256 : $SHA256
EOF
