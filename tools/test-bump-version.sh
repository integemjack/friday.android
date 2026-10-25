#!/usr/bin/env bash
# bump-version.sh 的行为测试：真实 git 仓库跑四个场景。
# 用法：bash tools/test-bump-version.sh（Windows 上：& 'C:\Program Files\Git\bin\bash.exe' tools/test-bump-version.sh）
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/.."
BUMP="$ROOT/tools/bump-version.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail() { echo "✗ $1" >&2; exit 1; }

new_repo() { # $1=version $2=code
  rm -rf "$WORK/repo"
  mkdir -p "$WORK/repo/app" "$WORK/repo/tools"
  cp "$BUMP" "$WORK/repo/tools/bump-version.sh"
  {
    echo 'plugins { alias(libs.plugins.android.application) }'
    echo ''
    echo "val APP_VERSION_NAME = \"$1\""
    echo "val APP_VERSION_CODE = $2"
  } > "$WORK/repo/app/build.gradle.kts"
  git -C "$WORK/repo" init -q
  git -C "$WORK/repo" config user.email test@test
  git -C "$WORK/repo" config user.name test
  git -C "$WORK/repo" add -A
  git -C "$WORK/repo" commit -qm init
}

run_bump() { (cd "$WORK/repo" && bash tools/bump-version.sh); }

echo "== 场景 1：无 tag，按文件版本首次发版 =="
new_repo 1.0.0 1
OUT="$(run_bump)"
echo "$OUT" | grep -q '^VERSION=1.0.0$'      || fail "VERSION 应为 1.0.0"
echo "$OUT" | grep -q '^VERSION_CODE=1$'     || fail "VERSION_CODE 应为 1"
echo "$OUT" | grep -q '^NEEDS_COMMIT=0$'     || fail "NEEDS_COMMIT 应为 0"
echo "$OUT" | grep -q '^RELEASE=1$'          || fail "RELEASE 应为 1"
grep -q 'APP_VERSION_NAME = "1.0.0"' "$WORK/repo/app/build.gradle.kts" || fail "文件不应被改"
echo "  ✓"

echo "== 场景 2：tag 与文件版本相同 → patch+1 并写回 =="
new_repo 1.0.0 1
git -C "$WORK/repo" tag v1.0.0
OUT="$(run_bump)"
echo "$OUT" | grep -q '^VERSION=1.0.1$'      || fail "VERSION 应为 1.0.1"
echo "$OUT" | grep -q '^VERSION_CODE=2$'     || fail "VERSION_CODE 应为 2"
echo "$OUT" | grep -q '^NEEDS_COMMIT=1$'     || fail "NEEDS_COMMIT 应为 1"
echo "$OUT" | grep -q '^RELEASE=1$'          || fail "RELEASE 应为 1"
grep -q 'APP_VERSION_NAME = "1.0.1"' "$WORK/repo/app/build.gradle.kts" || fail "文件应写回 1.0.1"
grep -q 'APP_VERSION_CODE = 2'    "$WORK/repo/app/build.gradle.kts" || fail "文件应写回 code 2"
echo "  ✓"

echo "== 场景 3：手动升 1.1.0（code 也手动 +1）→ 按文件版本发，不再写回 =="
new_repo 1.1.0 5
git -C "$WORK/repo" tag v1.0.1
git -C "$WORK/repo" tag -d v1.0.1 >/dev/null
# 造 tag 时的文件版本：先提交 1.0.1 再改到 1.1.0
git -C "$WORK/repo" checkout -q -b tmp
sed -i 's/APP_VERSION_NAME = "1.1.0"/APP_VERSION_NAME = "1.0.1"/; s/APP_VERSION_CODE = 5/APP_VERSION_CODE = 2/' "$WORK/repo/app/build.gradle.kts"
git -C "$WORK/repo" add -A && git -C "$WORK/repo" commit -qm old
git -C "$WORK/repo" tag v1.0.1
sed -i 's/APP_VERSION_NAME = "1.0.1"/APP_VERSION_NAME = "1.1.0"/; s/APP_VERSION_CODE = 2/APP_VERSION_CODE = 5/' "$WORK/repo/app/build.gradle.kts"
OUT="$(run_bump)"
echo "$OUT" | grep -q '^VERSION=1.1.0$'      || fail "VERSION 应为 1.1.0"
echo "$OUT" | grep -q '^VERSION_CODE=5$'     || fail "VERSION_CODE 应为 5"
echo "$OUT" | grep -q '^NEEDS_COMMIT=0$'     || fail "NEEDS_COMMIT 应为 0（手动 code 已够大）"
echo "$OUT" | grep -q '^RELEASE=1$'          || fail "RELEASE 应为 1"
echo "  ✓"

echo "== 场景 4：手动升版本但忘了 code → code 自动提到 tag 的 +1 并写回 =="
sed -i 's/APP_VERSION_CODE = 5/APP_VERSION_CODE = 2/' "$WORK/repo/app/build.gradle.kts"
OUT="$(run_bump)"
echo "$OUT" | grep -q '^VERSION_CODE=3$'     || fail "VERSION_CODE 应为 3（tag 的 2 +1）"
echo "$OUT" | grep -q '^NEEDS_COMMIT=1$'     || fail "NEEDS_COMMIT 应为 1"
grep -q 'APP_VERSION_CODE = 3' "$WORK/repo/app/build.gradle.kts" || fail "文件应写回 code 3"
echo "  ✓"

echo "== 场景 5：目标 tag 已存在（重跑保护）→ 不发布 =="
new_repo 1.0.0 1
git -C "$WORK/repo" tag v1.0.0
# 先跑一次拿到要 bump 到的版本，把那个 tag 建出来，再跑
OUT="$(run_bump)"
NEXT="$(echo "$OUT" | sed -n 's/^VERSION=//p')"
git -C "$WORK/repo" checkout -q -- app/build.gradle.kts  # 还原第一次的写回
git -C "$WORK/repo" tag "v$NEXT"
OUT="$(run_bump)"
echo "$OUT" | grep -q '^RELEASE=0$'          || fail "RELEASE 应为 0"
grep -q 'APP_VERSION_NAME = "1.0.0"' "$WORK/repo/app/build.gradle.kts" || fail "重跑不应留下写回的文件"
echo "  ✓"

echo
echo "全部场景通过 ✔"
