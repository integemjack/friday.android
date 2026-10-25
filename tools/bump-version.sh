#!/usr/bin/env bash
# 版本叠加与发版判断（GitHub Actions 的 bump-version 步骤用；本地也可直接跑）。
#
# 规则（与 github-actions-release 技能一致）：
#   - 没有 v* tag        → 按当前 app/build.gradle.kts 的版本直接发（首次发版）
#   - tag == 文件版本     → patch +1、versionCode +1，写回文件（CI 会 commit [skip ci] 后打 tag）
#   - 文件版本 > tag      → 手动升了版本：按文件版本发；versionCode 若没大于 tag 时的就 +1 写回
#   - 目标 tag 已存在     → 不发布（重跑保护）→ RELEASE=0
#
# 输出（stdout 一行一个 KEY=VALUE；在 GitHub Actions 里同时写 $GITHUB_OUTPUT）：
#   VERSION=…      本次要发的版本（x.y.z）
#   VERSION_CODE=… 对应 versionCode
#   NEEDS_COMMIT=1/0  是否需要把写回的 build.gradle.kts 提交回去
#   RELEASE=1/0       是否要打 tag 并发布
#   REASON=…          人话说明
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE_FILE=app/build.gradle.kts

read_version_from() { # $1 = 文件路径（或 tag:app/build.gradle.kts）
  sed -n 's/^val APP_VERSION_NAME = "\([^"]*\)".*/\1/p' "$1" | head -1
}
read_code_from() {
  sed -n 's/^val APP_VERSION_CODE = \([0-9]*\).*/\1/p' "$1" | head -1
}

FILE_VERSION="$(read_version_from "$GRADLE_FILE")"
FILE_CODE="$(read_code_from "$GRADLE_FILE")"
[ -n "$FILE_VERSION" ] || { echo "REASON=读不到 APP_VERSION_NAME（$GRADLE_FILE）" ; exit 1; }
[ -n "$FILE_CODE" ] || { echo "REASON=读不到 APP_VERSION_CODE（$GRADLE_FILE）"; exit 1; }

LATEST_TAG="$(git tag -l 'v*' --sort=-v:refname | head -1 || true)"

emit() { # emit KEY VALUE
  echo "$1=$2"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$1=$2" >> "$GITHUB_OUTPUT"; fi
}

VERSION=""
VERSION_CODE=0
NEEDS_COMMIT=0
REASON=""

bump_patch() { # x.y.z -> x.y.(z+1)
  local v="$1"
  local major minor patch
  IFS='.' read -r major minor patch <<< "$v"
  patch=$((patch + 1))
  echo "$major.$minor.$patch"
}

write_back() { # $1=version $2=code —— 只改两行版本常量
  sed -i "s/^val APP_VERSION_NAME = \"[^\"]*\"/val APP_VERSION_NAME = \"$1\"/" "$GRADLE_FILE"
  sed -i "s/^val APP_VERSION_CODE = [0-9]*/val APP_VERSION_CODE = $2/" "$GRADLE_FILE"
}

if [ -z "$LATEST_TAG" ]; then
  VERSION="$FILE_VERSION"
  VERSION_CODE="$FILE_CODE"
  REASON="首次发版：按文件当前版本 $FILE_VERSION 打 tag"
else
  TAG_VERSION="${LATEST_TAG#v}"
  TAG_CODE="$(git show "$LATEST_TAG:$GRADLE_FILE" 2>/dev/null | sed -n 's/^val APP_VERSION_CODE = \([0-9]*\).*/\1/p' | head -1 || true)"
  [ -n "$TAG_CODE" ] || TAG_CODE="$FILE_CODE"

  if [ "$TAG_VERSION" = "$FILE_VERSION" ]; then
    VERSION="$(bump_patch "$FILE_VERSION")"
    VERSION_CODE=$((FILE_CODE + 1))
    NEEDS_COMMIT=1
    REASON="版本没动（$FILE_VERSION == tag $LATEST_TAG）：patch +1 → $VERSION"
  else
    # 文件版本领先（手动升）或落后（改回去了）都以文件为准
    VERSION="$FILE_VERSION"
    if [ "$FILE_CODE" -gt "$TAG_CODE" ]; then
      VERSION_CODE="$FILE_CODE"
      REASON="手动升版本：$TAG_VERSION → $VERSION（versionCode $FILE_CODE 已大于 tag 的 $TAG_CODE）"
    else
      VERSION_CODE=$((TAG_CODE + 1))
      NEEDS_COMMIT=1
      REASON="手动升版本：$TAG_VERSION → $VERSION，versionCode 也提到 $VERSION_CODE"
    fi
  fi

  # 重跑保护：目标 tag 已存在就不重复发
  if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null; then
    emit VERSION "$VERSION"
    emit VERSION_CODE "$VERSION_CODE"
    emit NEEDS_COMMIT 0
    emit RELEASE 0
    emit REASON "tag v$VERSION 已存在，跳过（可能本次 run 已发过）"
    # 把写回的改动还原，避免 CI 提交
    git checkout -- "$GRADLE_FILE" 2>/dev/null || true
    exit 0
  fi
fi

if [ "$NEEDS_COMMIT" = "1" ]; then
  write_back "$VERSION" "$VERSION_CODE"
fi

emit VERSION "$VERSION"
emit VERSION_CODE "$VERSION_CODE"
emit NEEDS_COMMIT "$NEEDS_COMMIT"
emit RELEASE 1
emit REASON "$REASON"
