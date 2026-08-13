#!/usr/bin/env bash
# ============================================================================
#  GT-SPEEDOMETER — سكريبت الإصدار الشامل
#
#  يسأل عن رقم الإصدار واسمه ونوعه، ثمّ يبني، ثمّ — بعد إقرارك — يدفع التغييرات
#  ويُنشئ الإصدار على GitHub ويرفع الحزمة إليه.
#
#  الاستعمال:
#    ./scripts/release.sh                 تفاعليّ بالكامل
#    ./scripts/release.sh --dry-run       يعرض ما سيفعله بلا أن يفعل شيئًا
#    ./scripts/release.sh --code 5 --name 0.5.0-beta --type beta --variant debug --yes
#
#  الخيارات:
#    --code N        رقم الإصدار (versionCode)
#    --name S        اسم الإصدار (versionName) مثل 0.4.0-beta
#    --type beta|final     تجريبيّ أم نهائيّ
#    --variant debug|release|both   سمة البناء
#    --repackage     أعِد تحزيم الإصدار الحاليّ نفسه بلا رفع رقمه
#    --push-only     ادفع الحالة الراهنة بلا بناءٍ ولا وسمٍ ولا إصدار
#    (وفي الوضع التفاعليّ خياران آخران: إدخالٌ يدويّ، ودفعٌ فقط)
#    --replace       استبدل وسمًا وإصدارًا موجودَين بلا سؤال
#    --branch B      الفرع الهدف (الافتراضيّ: الفرع الافتراضيّ للمستودع)
#    --yes           لا تسأل تأكيدًا (للتشغيل الآليّ)
#    --no-push       ابنِ واعتمد محلّيًّا بلا دفع
#    --no-release    ادفع بلا إنشاء إصدارٍ على GitHub
#    --dry-run       تجربةٌ جافّة
#    --help          هذه الرسالة
#
#  الدفع دائمًا إلى الفرع الافتراضيّ مباشرةً (git push origin HEAD:main)، فلا يُفتح
#  طلب مساهمة ولو كنتَ على فرعٍ آخر.
#
#  المتطلَّبات: JDK 17 · git · (اختياريّ) gh أو GITHUB_TOKEN لإنشاء الإصدار
# ============================================================================

set -euo pipefail

# ---------- ألوان ورسائل ----------
if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
  C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_INFO=$'\033[36m'
else
  C_RESET=''; C_BOLD=''; C_DIM=''; C_OK=''; C_WARN=''; C_ERR=''; C_INFO=''
fi

say()  { printf '%s\n' "${C_INFO}::${C_RESET} $*"; }
ok()   { printf '%s\n' "${C_OK}✓${C_RESET} $*"; }
warn() { printf '%s\n' "${C_WARN}!${C_RESET} $*" >&2; }
die()  { printf '%s\n' "${C_ERR}✗${C_RESET} $*" >&2; exit 1; }
step() { printf '\n%s\n' "${C_BOLD}▸ $*${C_RESET}"; }

# ---------- الوسائط ----------
ARG_CODE=""; ARG_NAME=""; ARG_TYPE=""; ARG_VARIANT=""; ARG_BRANCH=""
ASSUME_YES=0; DO_PUSH=1; DO_RELEASE=1; DRY_RUN=0; REPACKAGE=0; REPLACE=0; PUSH_ONLY=0
REL_TYPE=""; VARIANT=""; TAG=""; TAG_MSG=""; DIST=""; PUB_BUILD=0
TAG_EXISTS_LOCAL=0; TAG_EXISTS_REMOTE=0; ARTIFACTS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --code)    ARG_CODE="${2:-}"; shift 2 ;;
    --name)    ARG_NAME="${2:-}"; shift 2 ;;
    --type)    ARG_TYPE="${2:-}"; shift 2 ;;
    --variant) ARG_VARIANT="${2:-}"; shift 2 ;;
    --branch)  ARG_BRANCH="${2:-}"; shift 2 ;;
    --repackage) REPACKAGE=1; shift ;;
    --push-only) PUSH_ONLY=1; shift ;;
    --replace) REPLACE=1; shift ;;
    --yes|-y)  ASSUME_YES=1; shift ;;
    --no-push) DO_PUSH=0; DO_RELEASE=0; shift ;;
    --no-release) DO_RELEASE=0; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help|-h) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "خيار غير معروف: $1  (جرّب --help)" ;;
  esac
done

NOTES_FILE="$(mktemp)"
trap 'rm -f "$NOTES_FILE"' EXIT

run() {
  if (( DRY_RUN )); then
    printf '%s\n' "${C_DIM}[تجربة جافّة] $*${C_RESET}"
  else
    "$@"
  fi
}

# ---------------------------------------------------------------------------
#  الإدخال في طرفيّةٍ ثنائيّة الاتّجاه
#
#  خلط العربيّة بالأرقام والأقواس في سطرٍ واحد يجعل الطرفيّة تُعيد ترتيبه، فتظهر
#  الخيارات متداخلةً وتنعكس الأقواس. القاعدة هنا: نصٌّ عربيّ في سطره، وخيارٌ واحد
#  في كلّ سطر، ومحثّ الإدخال بمحارف لاتينيّة خالصة — فاتّجاهه لا يلتبس.
# ---------------------------------------------------------------------------

confirm() {
  local prompt="$1"
  # لا تكتبها `(( X )) && return 0`: تحت `set -e` تُنهي السكريبت حين تكون X صفرًا
  if (( ASSUME_YES )); then return 0; fi
  printf '\n  %s\n' "$prompt"
  local reply
  read -r -p "  > y/n [n]: " reply
  [[ "$reply" =~ ^([yY]|نعم)$ ]]
}

ask() {                       # ask <النصّ> <الافتراضيّ> <اسم المتغيّر>
  local prompt="$1" default="$2" __var="$3" reply
  if (( ASSUME_YES )) && [[ -n "$default" ]]; then
    printf -v "$__var" '%s' "$default"; return
  fi
  printf '\n  %s\n' "$prompt"
  if [[ -n "$default" ]]; then
    read -r -p "  > [${default}]: " reply
    reply="${reply:-$default}"
  else
    read -r -p "  > " reply
  fi
  printf -v "$__var" '%s' "$reply"
}

menu() {                      # menu <العنوان> <الافتراضيّ> <اسم المتغيّر> <خيار…>
  local title="$1" default="$2" __var="$3"; shift 3
  printf '\n  %s\n' "$title"
  local i=1 opt
  for opt in "$@"; do
    printf '    %d) %s\n' "$i" "$opt"
    i=$((i + 1))
  done
  if (( ASSUME_YES )); then
    printf -v "$__var" '%s' "$default"
    printf '  > [%s]\n' "$default"
    return
  fi
  local reply
  read -r -p "  > [${default}]: " reply
  printf -v "$__var" '%s' "${reply:-$default}"
}

# ---------------------------------------------------------------------------
#  الدفع
#
#  الفرع المتأخّر عن البعيد يُرفض دفعُه. الأفضل أن نكتشف ذلك ونعالجه هنا، لا أن
#  نترك المستعمل أمام رسالة git بعد أن صار في يده وسمٌ معلَّق.
# ---------------------------------------------------------------------------
push_branch() {
  git fetch --quiet origin "$TARGET_BRANCH" 2>/dev/null || true
  local behind
  behind="$(git rev-list --count "HEAD..origin/${TARGET_BRANCH}" 2>/dev/null || echo 0)"
  if [[ "$behind" != "0" ]]; then
    warn "شجرتك متأخّرة عن origin/${TARGET_BRANCH} بـ ${behind} اعتمادًا، فسيُرفض الدفع."
    local choice
    menu "ماذا أفعل؟" "1" choice \
      "ادمج أوّلًا — git pull --rebase origin ${TARGET_BRANCH}" \
      "ادفع رغم ذلك — سيفشل غالبًا" \
      "ألغِ"
    case "$choice" in
      1)
        if ! run git pull --rebase origin "$TARGET_BRANCH"; then
          die "تعثّر الدمج. حُلّ التعارض ثمّ: ./release.sh --push-only"
        fi
        ok "دُمج origin/${TARGET_BRANCH}"
        ;;
      2) warn "متابعةٌ بلا دمج." ;;
      *) die "أُلغي قبل الدفع." ;;
    esac
  fi
  # HEAD:الهدف — تحديثٌ مباشر للفرع الافتراضيّ، فلا يُقترح طلب مساهمة
  run git push origin "HEAD:${TARGET_BRANCH}"
  ok "دُفع HEAD إلى origin/${TARGET_BRANCH}"
}

# ---------------------------------------------------------------------------
#  البناء وجمع الحزم والنشر — دوالّ مشتركة بين مسار الإصدار الكامل ومسار الدفع
# ---------------------------------------------------------------------------

build_apks() {
  local tasks=()
  if [[ "$VARIANT" == "debug"   || "$VARIANT" == "both" ]]; then tasks+=(":app:assembleDebug"); fi
  if [[ "$VARIANT" == "release" || "$VARIANT" == "both" ]]; then tasks+=(":app:assembleRelease"); fi
  say "./gradlew ${tasks[*]}"
  if (( DRY_RUN )); then
    printf '%s\n' "${C_DIM}[تجربة جافّة] ./gradlew --console=plain ${tasks[*]}${C_RESET}"
    return
  fi
  # المخرجات تُبَثّ وتُحفظ معًا: سجلّ Gradle عند الفشل مئاتُ الأسطر، وسطرا العطب
  # الحقيقيّان يضيعان في وسطها. نلتقطهما ونعيد عرضهما في الآخر.
  local log; log="$(mktemp)"
  if ./gradlew --console=plain "${tasks[@]}" 2>&1 | tee "$log"; then
    rm -f "$log"
    return
  fi

  printf '\n'
  warn "فشل البناء. أُعيدت ملفّات المشروع إلى ما كانت: ${SYNC_FILES[*]:-}"
  local errs
  errs="$(grep -E '^e: |^  *> .*[Ee]rror|error:' "$log" \
          | sed -E 's|file:///[^ ]*/app/src|app/src|' | head -12)"
  if [[ -n "$errs" ]]; then
    printf '\n%s\n' "${C_BOLD}أوّل ما اشتكى منه المصرّف:${C_RESET}"
    printf '%s\n' "$errs"
  fi
  # عطبٌ شائع يستحقّ تشخيصًا صريحًا لا سطرَ خطأٍ مبهمًا
  if grep -q 'Redeclaration' "$log"; then
    printf '\n'
    warn "«Redeclaration» تعني تعريفًا مكرّرًا، وسببه في الغالب ملفٌّ قديم بقي بعد"
    warn "نسخ شجرةٍ جديدة **فوق** القديمة — والنسخ لا يحذف ما استُغني عنه."
    warn "قارن شجرتك بالحزمة واحذف الزائد، مثلًا:"
    warn "  unzip -Z1 <الحزمة>.zip | sed -n 's|^[^/]*/||p' | grep '\.kt$' | sort > /tmp/keep.txt"
    warn "  find app/src/main/java -name '*.kt' | sort > /tmp/have.txt"
    warn "  comm -13 /tmp/keep.txt /tmp/have.txt"
  fi
  rm -f "$log"
  if declare -F restore_files >/dev/null; then restore_files; fi
  die "أُلغي عند البناء."
}

# ينسخ ناتج Gradle إلى dist/ باسمٍ يحمل الإصدار، ويحسب مجاميعه
collect_artifacts() {
  DIST="dist/${NEW_NAME}"
  run mkdir -p "$DIST"
  # `*.apk` متجاهَلة في .gitignore، أمّا ملفّات sha256 فلا — ولولا هذا لدخلت الاعتماد
  if [[ -f .gitignore ]] && ! grep -qxF 'dist/' .gitignore; then
    if (( ! DRY_RUN )); then printf '\n# حزم الإصدار المبنيّة محلّيًّا\ndist/\n' >> .gitignore; fi
    say "أُضيف dist/ إلى .gitignore"
  fi
  ARTIFACTS=()
  local v src out
  for v in debug release; do
    [[ "$VARIANT" == "$v" || "$VARIANT" == "both" ]] || continue
    src="app/build/outputs/apk/${v}/app-${v}.apk"
    [[ -f "$src" ]] || src="app/build/outputs/apk/${v}/app-${v}-unsigned.apk"
    if (( DRY_RUN )); then
      ARTIFACTS+=("${DIST}/GT-SPEEDOMETER-${NEW_NAME}-${v}.apk"); continue
    fi
    [[ -f "$src" ]] || die "لم أجد حزمة ${v} في app/build/outputs/apk/${v}/"
    out="${DIST}/GT-SPEEDOMETER-${NEW_NAME}-${v}.apk"
    cp "$src" "$out"
    ( cd "$DIST" && sha256sum "$(basename "$out")" > "$(basename "$out").sha256" )
    ARTIFACTS+=("$out" "${out}.sha256")
    ok "$(basename "$out")  —  $(du -h "$out" | cut -f1)"
  done
}

# يلتقط حزمًا بُنيت في تشغيلٍ سابق، فلا يُعاد بناء ما هو جاهز
find_built_apks() {
  ARTIFACTS=()
  local d="dist/${NEW_NAME}" f
  [[ -d "$d" ]] || return 1
  for f in "$d"/*.apk; do
    [[ -f "$f" ]] || continue
    ARTIFACTS+=("$f")
    [[ -f "${f}.sha256" ]] && ARTIFACTS+=("${f}.sha256")
  done
  (( ${#ARTIFACTS[@]} > 0 ))
}

# ملاحظات الإصدار من قسم CHANGELOG الموافق للاسم
load_notes() {
  : "${NOTES_FILE:=$(mktemp)}"
  if [[ -f CHANGELOG.md ]] && grep -q "^## v\?${NEW_NAME}\b" CHANGELOG.md; then
    awk -v v="${NEW_NAME}" '
      $0 ~ "^## v?" v "([^0-9.]|$)" { inside = 1; next }
      inside && /^## / { exit }
      inside { print }
    ' CHANGELOG.md > "$NOTES_FILE"
    ok "مُلاحظات الإصدار من CHANGELOG.md ($(wc -l < "$NOTES_FILE") سطرًا)"
  else
    warn "لا قسم لـ ${NEW_NAME} في CHANGELOG.md — ستكون ملاحظات الإصدار مقتضبة."
    printf '%s\n' "الإصدار ${NEW_NAME}." > "$NOTES_FILE"
  fi
}

# يملأ TAG و TAG_EXISTS_LOCAL/REMOTE من الاسم الحاليّ
detect_tag() {
  TAG="v${NEW_NAME}"
  TAG_EXISTS_LOCAL=0; TAG_EXISTS_REMOTE=0
  if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then TAG_EXISTS_LOCAL=1; fi
  if git ls-remote --tags origin "refs/tags/${TAG}" 2>/dev/null | grep -q .; then TAG_EXISTS_REMOTE=1; fi
}

make_tag() {
  # `-f` لا حذفٌ ثمّ إنشاء: الوسم يُنقل إلى الاعتماد الحاليّ في خطوةٍ واحدة
  if (( TAG_EXISTS_LOCAL )); then
    run git tag -f -a "$TAG" -m "$TAG_MSG"
  else
    run git tag -a "$TAG" -m "$TAG_MSG"
  fi
  ok "$TAG"
}

push_tag() {
  if (( TAG_EXISTS_REMOTE )); then
    run git push -f origin "refs/tags/${TAG}"
  else
    run git push origin "refs/tags/${TAG}"
  fi
  ok "دُفع الوسم ${TAG}"
}

# ينشئ الإصدار أو **يُحدّثه في مكانه** إن كان موجودًا: الحذف والإنشاء من جديد
# يُضيّع رابط الإصدار وعدّاد تنزيلاته وتعليقاته، والمطلوب مطابقةٌ لا استبدال.
publish_release() {
  local title="$TAG" pre_flag exists=0
  if [[ "$REL_TYPE" == "beta" ]]; then pre_flag="--prerelease"; else pre_flag="--latest"; fi

  if (( HAS_GH )); then
    if gh release view "$TAG" >/dev/null 2>&1; then exists=1; fi
    if (( exists )); then
      say "تحديث الإصدار ${TAG} في مكانه"
      run gh release edit "$TAG" --title "$title" --notes-file "$NOTES_FILE" $pre_flag
      if (( ${#ARTIFACTS[@]} > 0 )); then
        run gh release upload "$TAG" "${ARTIFACTS[@]}" --clobber
      fi
    else
      say "gh release create ${TAG} ${pre_flag}"
      run gh release create "$TAG" --title "$title" --notes-file "$NOTES_FILE" $pre_flag \
        "${ARTIFACTS[@]+"${ARTIFACTS[@]}"}"
    fi
    ok "الإصدار ${TAG} جاهز"
    return
  fi

  # مسارٌ بديل بلا gh: واجهة GitHub مباشرةً
  local slug pre body resp rid upload f
  slug="$(git remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"
  pre=false; [[ "$REL_TYPE" == "beta" ]] && pre=true
  (( DRY_RUN )) && { say "إنشاء/تحديث الإصدار ${TAG} على ${slug}"; return; }

  body="$(python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' < "$NOTES_FILE")"
  resp="$(curl -sS -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    "https://api.github.com/repos/${slug}/releases/tags/${TAG}")"
  rid="$(printf '%s' "$resp" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)"

  if [[ -n "$rid" ]]; then
    resp="$(curl -sS -X PATCH -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      "https://api.github.com/repos/${slug}/releases/${rid}" \
      -d "{\"name\":\"${title}\",\"body\":${body},\"prerelease\":${pre}}")"
    say "حُدّث الإصدار ${TAG}"
  else
    resp="$(curl -sS -X POST -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      "https://api.github.com/repos/${slug}/releases" \
      -d "{\"tag_name\":\"${TAG}\",\"name\":\"${title}\",\"body\":${body},\"prerelease\":${pre}}")"
    rid="$(printf '%s' "$resp" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)"
    [[ -n "$rid" ]] || { printf '%s\n' "$resp" >&2; die "تعذّر إنشاء الإصدار."; }
  fi

  upload="$(printf '%s' "$resp" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("upload_url","").split("{")[0])')"
  for f in "${ARTIFACTS[@]+"${ARTIFACTS[@]}"}"; do
    # حذف مرفقٍ بالاسم نفسه أوّلًا، وإلّا رفضت GitHub الرفع المكرّر
    curl -sS -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      "https://api.github.com/repos/${slug}/releases/${rid}/assets" \
      | python3 -c "
import json,sys
name='$(basename "$f")'
for a in json.load(sys.stdin):
    if a['name']==name: print(a['id'])
" | while read -r aid; do
      curl -sS -X DELETE -H "Authorization: Bearer ${GITHUB_TOKEN}" \
        "https://api.github.com/repos/${slug}/releases/assets/${aid}" >/dev/null
    done
    say "رفع $(basename "$f")"
    curl -sS -X POST -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Content-Type: application/octet-stream" \
      --data-binary @"$f" "${upload}?name=$(basename "$f")" >/dev/null
  done
  ok "الإصدار ${TAG} جاهز"
}

# ---------- جذر المستودع ----------
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && git rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || die "لست داخل مستودع git."
cd "$REPO_ROOT"

GRADLE_FILE="app/build.gradle.kts"
[[ -f "$GRADLE_FILE" ]] || die "لم أجد $GRADLE_FILE — هل هذا جذر GT-SPEEDOMETER؟"
[[ -x ./gradlew ]] || chmod +x ./gradlew

printf '%s\n' "${C_BOLD}GT-SPEEDOMETER — إصدار${C_RESET}"
printf '%s\n' "${C_DIM}$REPO_ROOT${C_RESET}"

# ---------- الأدوات ----------
step "فحص الأدوات"
command -v git >/dev/null || die "git غير مثبَّت."
command -v java >/dev/null || die "java غير مثبَّت — يلزم JDK 17."
# لا تأخذ السطر الأوّل بلا تمحيص: JAVA_TOOL_OPTIONS تطبع سطر "Picked up…" قبله
JAVA_VER_LINE="$(java -version 2>&1 | grep -m1 -E 'version "' || true)"
JAVA_MAJOR="$(printf '%s' "$JAVA_VER_LINE" | sed -E 's/.*version "([0-9]+)\.?([0-9]*).*/\1 \2/')"
read -r _J1 _J2 <<< "$JAVA_MAJOR"
# صيغة 1.8.0_x القديمة: الرقم الحقيقيّ هو الثاني
if [[ "$_J1" == "1" && -n "$_J2" ]]; then JAVA_MAJOR="$_J2"; else JAVA_MAJOR="$_J1"; fi
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || die "تعذّر تحديد إصدار Java من: $JAVA_VER_LINE"
(( JAVA_MAJOR >= 17 )) || die "يلزم JDK 17 فأعلى (الموجود: $JAVA_MAJOR)."
ok "JDK $JAVA_MAJOR"

HAS_GH=0
if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then HAS_GH=1; fi
if (( HAS_GH )); then
  ok "gh مصادَق عليه"
elif [[ -n "${GITHUB_TOKEN:-}" ]]; then
  ok "GITHUB_TOKEN موجود (سيُستعمل curl)"
else
  warn "لا gh مصادَق عليه ولا GITHUB_TOKEN — لن يُنشأ إصدارٌ على GitHub."
  DO_RELEASE=0
fi

# ---------- الفرع الهدف ----------
default_branch() {
  local b=""
  b="$(git symbolic-ref -q --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##' || true)"
  if [[ -z "$b" ]]; then
    b="$(git remote show origin 2>/dev/null | sed -n 's/.*HEAD branch: //p' | head -1 || true)"
  fi
  if [[ -z "$b" ]]; then b="main"; fi
  printf '%s' "$b"
}

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
TARGET_BRANCH="${ARG_BRANCH:-$(default_branch)}"
if [[ "$BRANCH" != "$TARGET_BRANCH" ]]; then
  # الدفع إلى فرعٍ غير الافتراضيّ هو ما يجعل GitHub يعرض «افتح طلب مساهمة».
  # هنا ندفع HEAD إلى الفرع الافتراضيّ مباشرةً: تحديثٌ للمستودع لا اقتراحٌ عليه.
  warn "أنت على '${BRANCH}' والهدف '${TARGET_BRANCH}': سيُدفع HEAD:${TARGET_BRANCH} مباشرةً."
fi

# ---------- الإصدار الحاليّ ----------
CUR_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" | head -1)"
CUR_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)"
[[ -n "$CUR_CODE" && -n "$CUR_NAME" ]] || die "تعذّرت قراءة versionCode/versionName من $GRADLE_FILE."

step "الإصدار الحاليّ"
printf '  versionCode = %s\n  versionName = %s\n' "$CUR_CODE" "$CUR_NAME"

# ---------- الأسئلة ----------
step "الإصدار الجديد"

# إعادة تحزيم الإصدار نفسه حاجةٌ مشروعة: حزمةٌ ضاعت، أو إصلاحٌ في البناء لا في
# الشفرة، أو إصدارٌ رُفع رقمه سهوًا. فالوضع خيارٌ لا استثناء.
MANUAL=0
if (( ! REPACKAGE )) && [[ -z "$ARG_CODE" && -z "$ARG_NAME" ]]; then
  menu "الوضع:" "1" _m \
    "إصدارٌ جديد — يقترح الرقم التالي" \
    "إعادة تحزيم الحاليّ — ${CUR_NAME}" \
    "إدخالٌ يدويّ — أُدخل الرقم والاسم كما أشاء" \
    "دفعٌ فقط — اعتمد الحالة الراهنة وادفعها بلا بناءٍ ولا إصدار"
  case "$_m" in
    1|new|جديد) REPACKAGE=0 ;;
    2|repackage|إعادة) REPACKAGE=1 ;;
    3|manual|يدوي|يدويّ) MANUAL=1 ;;
    4|push|دفع) PUSH_ONLY=1 ;;
    *) die "اختيارٌ غير مفهوم: $_m" ;;
  esac
fi

# ---------------------------------------------------------------------------
#  مسار «دفعٌ فقط»
#
#  مستقلٌّ ومبكّر عمدًا: لا يمسّ رقم إصدارٍ ولا يبني ولا يَسِم ولا يُنشئ إصدارًا.
#  حاجته تظهر كثيرًا — تعديلٌ في التوثيق، أو دفعٌ تعثّر فبقيت الشجرة معتمَدةً بلا رفع.
# ---------------------------------------------------------------------------
if (( PUSH_ONLY )); then
  step "دفعٌ فقط"
  printf '  %s\n' "الفرع: ${BRANCH} إلى origin/${TARGET_BRANCH}"
  if [[ -n "$(git status --porcelain)" ]]; then
    git status --short
    ask "رسالة الاعتماد" "تحديث ملفّات المشروع" PUSH_MSG
    run git add -A
    run git commit -m "$PUSH_MSG"
    ok "اعتُمد"
  else
    say "لا تغييرات غير معتمدة."
  fi

  AHEAD="$(git rev-list --count "origin/${TARGET_BRANCH}..HEAD" 2>/dev/null || echo 0)"
  if [[ "$AHEAD" == "0" ]] && [[ -z "$(git status --porcelain)" ]]; then
    ok "لا جديد يُدفع."
    exit 0
  fi
  confirm "أأدفع الآن إلى origin/${TARGET_BRANCH}؟" || die "أُلغي."
  push_branch

  # الحزمة لا تدخل المستودع: `dist/` و`*.apk` متجاهَلتان عمدًا — موضع الحزمة هو
  # الإصدار لا شجرة الشفرة. فالنشر خطوةٌ مستقلّة تُختار هنا.
  menu "ماذا بعد الدفع؟" "1" _p \
    "اكتفِ بالدفع" \
    "انشر الإصدار ${CUR_NAME} من حزمةٍ مبنيّة سابقًا" \
    "ابنِ الحزمة ثمّ انشر الإصدار ${CUR_NAME}"
  case "$_p" in
    1|no|لا) ok "تمّ."; exit 0 ;;
    2) PUB_BUILD=0 ;;
    3) PUB_BUILD=1 ;;
    *) die "اختيارٌ غير مفهوم: $_p" ;;
  esac

  NEW_NAME="$CUR_NAME"; NEW_CODE="$CUR_CODE"
  REL_TYPE="${ARG_TYPE:-}"
  if [[ -z "$REL_TYPE" ]]; then
    if [[ "$NEW_NAME" == *-* ]]; then REL_TYPE="beta"; else REL_TYPE="final"; fi
  fi
  detect_tag

  if (( PUB_BUILD )); then
    VARIANT="$ARG_VARIANT"
    if [[ -z "$VARIANT" ]]; then
      menu "سمة البناء:" "1" _v2 \
        "debug — موقّعة بمفتاح التطوير، تُثبَّت مباشرةً" \
        "release — تحتاج keystore.properties للتوقيع" \
        "كلتاهما"
      case "$_v2" in
        1|debug) VARIANT="debug" ;;
        2|release) VARIANT="release" ;;
        3|both) VARIANT="both" ;;
        *) die "اختيارٌ غير مفهوم: $_v2" ;;
      esac
    fi
    step "البناء"
    build_apks
    collect_artifacts
  else
    step "الحزم"
    if find_built_apks; then
      for _a in "${ARTIFACTS[@]}"; do
        case "$_a" in *.apk) ok "$(basename "$_a")  —  $(du -h "$_a" 2>/dev/null | cut -f1)" ;; esac
      done
    else
      warn "لا حزمة في dist/${NEW_NAME}/ — أعد التشغيل واختر «ابنِ الحزمة ثمّ انشر»."
      confirm "أأنشر الإصدار بلا حزمة؟" || die "أُلغي."
      ARTIFACTS=()
    fi
  fi

  load_notes
  TAG_MSG="${NEW_NAME}"
  if [[ -s "$NOTES_FILE" ]]; then
    TAG_MSG="$(printf '%s\n\n%s' "${NEW_NAME}" "$(head -20 "$NOTES_FILE")")"
  fi

  step "الوسم"
  make_tag
  push_tag

  step "إصدار GitHub"
  publish_release
  ok "تمّ."
  exit 0
fi

if (( REPACKAGE )); then
  NEW_CODE="${ARG_CODE:-$CUR_CODE}"
  NEW_NAME="${ARG_NAME:-$CUR_NAME}"
  say "إعادة تحزيم ${NEW_NAME} (${NEW_CODE})"
elif (( MANUAL )); then
  # الوضع اليدويّ: ما يُدخَل يُعتمد. `build.gradle.kts` قد يحمل رقمًا رُفع سهوًا،
  # فلا يصحّ أن يصير سقفًا يمنع تصحيحه. نحذّر ولا نمنع.
  ask "رقم الإصدار (versionCode) — الحاليّ ${CUR_CODE}" "$CUR_CODE" NEW_CODE
  ask "اسم الإصدار (versionName) — الحاليّ ${CUR_NAME}" "$CUR_NAME" NEW_NAME
else
  NEW_CODE="$ARG_CODE"
  [[ -n "$NEW_CODE" ]] || ask "رقم الإصدار (versionCode)" "$((CUR_CODE + 1))" NEW_CODE
fi
[[ "$NEW_CODE" =~ ^[0-9]+$ ]] || die "رقم الإصدار يجب أن يكون عددًا صحيحًا: '$NEW_CODE'"
if (( NEW_CODE < CUR_CODE )); then
  warn "رقم الإصدار (${NEW_CODE}) دون الحاليّ (${CUR_CODE})."
  warn "أندرويد يرفض تثبيت حزمةٍ رقمُها أدنى ممّا على الجهاز إلّا بإلغاء التثبيت أوّلًا."
  if (( ! MANUAL )); then
    confirm "أأمضي؟" || die "أُلغي."
  fi
fi

REL_TYPE="${ARG_TYPE:-$REL_TYPE}"
if [[ -z "$REL_TYPE" ]] && (( REPACKAGE || MANUAL )); then
  # الاسم يحمل لاحقةً ⇒ تجريبيّ. لا معنى لسؤالٍ جوابه في الاسم؛ و--type يَجُبّه.
  if [[ "$NEW_NAME" == *-* ]]; then REL_TYPE="beta"; else REL_TYPE="final"; fi
  say "النوع مشتقٌّ من الاسم '${NEW_NAME}': ${REL_TYPE}"
fi
if [[ -z "$REL_TYPE" ]]; then
  menu "نوع الإصدار:" "1" _t \
    "تجريبيّ — beta" \
    "نهائيّ — final"
  case "$_t" in
    1|beta|تجريبي|تجريبيّ)  REL_TYPE="beta" ;;
    2|final|نهائي|نهائيّ)   REL_TYPE="final" ;;
    *) die "اختيارٌ غير مفهوم: $_t" ;;
  esac
fi
[[ "$REL_TYPE" == "beta" || "$REL_TYPE" == "final" ]] || die "نوع الإصدار: beta أو final."

# اقتراح اسمٍ يوافق النوع، على نسق المشروع (0.3.0-beta)
BASE_VER="$(printf '%s' "$CUR_NAME" | sed -E 's/-.*$//')"
IFS='.' read -r _MA _MI _PA <<< "$BASE_VER"
SUGGEST="${_MA}.$((_MI + 1)).0"
if [[ "$REL_TYPE" == "beta" ]]; then SUGGEST="${SUGGEST}-beta"; fi

if (( ! REPACKAGE && ! MANUAL )); then
  NEW_NAME="$ARG_NAME"
  [[ -n "$NEW_NAME" ]] || ask "اسم الإصدار (versionName)" "$SUGGEST" NEW_NAME
fi
[[ -n "$NEW_NAME" ]] || die "اسم الإصدار فارغ."

if (( MANUAL )); then
  # يصير وسم git، فالممنوع ما يمنعه git وحده
  if [[ "$NEW_NAME" =~ [[:space:]~^:?*\\\[] ]]; then
    die "اسم الإصدار يحمل محرفًا لا يقبله وسم git: '$NEW_NAME'"
  fi
  if [[ ! "$NEW_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
    warn "الاسم '$NEW_NAME' خارج نسق المشروع (1.2.3 أو 1.2.3-beta) — سيُعتمد كما هو."
  fi
else
  [[ "$NEW_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]] \
    || die "اسم الإصدار يجب أن يكون على نسق 1.2.3 أو 1.2.3-beta: '$NEW_NAME'"
fi

if [[ "$REL_TYPE" == "final" && "$NEW_NAME" == *-* ]]; then
  warn "إصدارٌ نهائيّ باسمٍ يحمل لاحقة ('$NEW_NAME')."
  if (( ! MANUAL )); then confirm "أأمضي؟" || die "أُلغي."; fi
fi
if [[ "$REL_TYPE" == "beta" && "$NEW_NAME" != *-* ]]; then
  warn "إصدارٌ تجريبيّ باسمٍ بلا لاحقة ('$NEW_NAME')."
  if (( ! MANUAL )); then confirm "أأمضي؟" || die "أُلغي."; fi
fi

detect_tag

if (( TAG_EXISTS_LOCAL || TAG_EXISTS_REMOTE )); then
  warn "الوسم ${TAG} موجودٌ سلفًا$( (( TAG_EXISTS_REMOTE )) && printf ' (وعلى origin)' )."
  if (( ! REPLACE )); then
    confirm "أأستبدله هو وإصداره على GitHub؟" || die "أُلغي. اختر اسمًا آخر أو مرّر --replace."
  fi
  REPLACE=1
fi

# ---------- سمة البناء ----------
VARIANT="$ARG_VARIANT"
if [[ -z "$VARIANT" ]]; then
  DEFAULT_VARIANT="debug"
  if [[ "$REL_TYPE" == "final" ]]; then DEFAULT_VARIANT="release"; fi
  menu "سمة البناء:" "$([[ $DEFAULT_VARIANT == debug ]] && echo 1 || echo 2)" _v \
    "debug — موقّعة بمفتاح التطوير، تُثبَّت مباشرةً" \
    "release — تحتاج keystore.properties للتوقيع" \
    "كلتاهما"
  case "$_v" in
    1|debug)   VARIANT="debug" ;;
    2|release) VARIANT="release" ;;
    3|both)    VARIANT="both" ;;
    *) die "اختيارٌ غير مفهوم: $_v" ;;
  esac
fi
[[ "$VARIANT" =~ ^(debug|release|both)$ ]] || die "سمة البناء: debug أو release أو both."

if [[ "$VARIANT" != "debug" && ! -f keystore.properties ]]; then
  warn "لا ملفّ keystore.properties: حزمة release ستخرج **بلا توقيع** ولن تُثبَّت مباشرةً."
  confirm "أأمضي؟" || die "أُلغي."
fi

# ---------- سجلّ التغييرات ----------
load_notes

# ---------- ملخّص ----------
step "الملخّص"
cat <<EOF
  الإصدار      : من ${CUR_NAME} (${CUR_CODE}) إلى ${NEW_NAME} (${NEW_CODE})
  النوع        : $([[ "$REL_TYPE" == beta ]] && echo 'تجريبيّ — prerelease' || echo 'نهائيّ — latest')
  سمة البناء   : ${VARIANT}
  الوسم        : ${TAG}$( (( REPLACE )) && printf ' (استبدال)' )
  الفرع        : ${BRANCH} إلى origin/${TARGET_BRANCH} — دفعٌ مباشر
  الدفع        : $([[ $DO_PUSH == 1 ]] && echo 'نعم' || echo 'لا')
  إنشاء الإصدار: $([[ $DO_RELEASE == 1 ]] && echo 'نعم' || echo 'لا')
EOF
if (( DRY_RUN )); then warn "تجربةٌ جافّة: لن يُكتب ولا يُدفع شيء."; fi
confirm "أأمضي؟" || die "أُلغي."

# ---------- كتابة الإصدار ----------
step "تحديث $GRADLE_FILE"
# النسخ الاحتياطيّة خارج المستودع عمدًا: `git add -A` كان سيعتمدها لو جاورت الأصل،
# والاستعادة منها أدقّ من `git checkout --` الذي يمحو أيّ تعديلٍ سابقٍ غير معتمد.
# وتشمل كلّ ملفٍّ نمسّه: فشلُ البناء يجب أن يُرجع الشجرة كما كانت، لا ملفًّا واحدًا.
BAK_DIR="$(mktemp -d)"
trap 'rm -f "$NOTES_FILE"; rm -rf "$BAK_DIR"' EXIT
SYNC_FILES=("$GRADLE_FILE")
for f in README.md CHANGELOG.md; do
  if [[ -f "$f" ]]; then SYNC_FILES+=("$f"); fi
done
backup_files() {
  local f
  for f in "${SYNC_FILES[@]}"; do
    mkdir -p "${BAK_DIR}/$(dirname "$f")"
    cp "$f" "${BAK_DIR}/${f}"
  done
}
restore_files() {
  local f
  for f in "${SYNC_FILES[@]}"; do
    if [[ -f "${BAK_DIR}/${f}" ]]; then cp "${BAK_DIR}/${f}" "$f"; fi
  done
}
if (( ! DRY_RUN )); then backup_files; fi
if (( ! DRY_RUN )); then
  sed -i -E "s/(versionCode\s*=\s*)[0-9]+/\1${NEW_CODE}/" "$GRADLE_FILE"
  sed -i -E "s/(versionName\s*=\s*\")[^\"]+(\")/\1${NEW_NAME}\2/" "$GRADLE_FILE"
  if ! grep -qE "versionCode\s*=\s*${NEW_CODE}\b" "$GRADLE_FILE" \
     || ! grep -qF "versionName = \"${NEW_NAME}\"" "$GRADLE_FILE"; then
    restore_files
    die "فشل تحديث الإصدار في $GRADLE_FILE."
  fi
fi
ok "versionCode=${NEW_CODE} · versionName=${NEW_NAME}"

# رفعُ الإصدار في ملفّ البناء وحده يترك README و CHANGELOG يكذبان على القارئ —
# وهو ما وقع في 0.4.0. تُزامَن هنا كلّها، ثمّ يُعتمد كلّ ما في الشجرة.
step "مزامنة ملفّات المشروع"
if [[ -f README.md ]] && grep -qE 'الحالة: \*\*v?[0-9]' README.md; then
  if (( ! DRY_RUN )); then
    sed -i -E "s/(الحالة: \*\*)v?[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?(\*\*)/\1v${NEW_NAME}\3/" README.md
  fi
  ok "README.md صار v${NEW_NAME}"
else
  warn "لم أجد سطر «الحالة: **v…**» في README.md — راجعه بنفسك."
fi

if [[ -f CHANGELOG.md ]] && ! grep -q "^## v\?${NEW_NAME}\b" CHANGELOG.md; then
  warn "لا قسم لـ ${NEW_NAME} في CHANGELOG.md."
  if confirm "أأضيف قسمًا فارغًا أكتبه أنت لاحقًا؟"; then
    if (( ! DRY_RUN )); then
      STUB="$(mktemp)"
      {
        head -3 CHANGELOG.md
        printf '\n## v%s\n\n### أُضيف\n\n-\n\n### أُصلح\n\n-\n' "$NEW_NAME"
        tail -n +4 CHANGELOG.md
      } > "$STUB"
      mv "$STUB" CHANGELOG.md
      # الملاحظات تُعاد قراءتها من القسم الجديد
      awk -v v="${NEW_NAME}" '
        $0 ~ "^## v?" v "([^0-9.]|$)" { inside = 1; next }
        inside && /^## / { exit }
        inside { print }
      ' CHANGELOG.md > "$NOTES_FILE"
    fi
    ok "أُضيف قسم ${NEW_NAME} إلى CHANGELOG.md"
  fi
fi

# ---------- البناء ----------
step "البناء"
build_apks
collect_artifacts

# ---------- بوّابة الاختبار الميدانيّ ----------
# قاعدة المستودع: لا رفع ولا نشر قبل أن يختبر المستخدم محلّيًّا ويُقرّ.
if (( DO_PUSH )); then
  step "الاختبار قبل النشر"
  printf '%s\n' "الحزمة في: ${C_BOLD}${DIST}/${C_RESET}"
  printf '%s\n' "ثبّتها واختبرها الآن. مثال: adb install -r ${ARTIFACTS[0]:-<apk>}"
  if ! confirm "أاختبرتَ الحزمة على الجهاز وأقررتَها للنشر؟"; then
    warn "لم يُعتمد شيء ولم يُدفع. رقم الإصدار الجديد باقٍ في ${GRADLE_FILE}؛"
    warn "لإرجاعه:  git checkout -- ${GRADLE_FILE}"
    exit 0
  fi
fi

# ---------- الاعتماد ----------
step "الاعتماد على git"
if [[ -z "$(git status --porcelain)" ]]; then
  warn "لا تغييرات لاعتمادها."
else
  git status --short
  if (( REPACKAGE )); then
    DEFAULT_MSG="إعادة تحزيم ${NEW_NAME}"
  elif [[ "$REL_TYPE" == "final" ]]; then
    DEFAULT_MSG="إصدار ${NEW_NAME}"
  else
    DEFAULT_MSG="رفع الإصدار التجريبيّ إلى ${NEW_NAME}"
  fi
  ask "رسالة الاعتماد" "$DEFAULT_MSG" COMMIT_MSG
  run git add -A
  # قاعدة المستودع: الرسائل بالعربيّة، وباسم SalehGNUTUX وحده بلا Co-Authored-By
  run git commit -m "$COMMIT_MSG"
  ok "اعتُمد"
fi

TAG_MSG="${NEW_NAME}"
if [[ -s "$NOTES_FILE" ]]; then
  TAG_MSG="$(printf '%s\n\n%s' "${NEW_NAME}" "$(head -20 "$NOTES_FILE")")"
fi

if (( ! DO_PUSH )); then
  step "الوسم"
  make_tag
  warn "--no-push: توقّفنا هنا. للدفع لاحقًا:"
  warn "  git push origin HEAD:${TARGET_BRANCH} && git push origin ${TAG}"
  exit 0
fi

# الدفع أوّلًا ثمّ الوسم. كان الترتيب معكوسًا، فإذا تعثّر الدفع — لتأخّر الفرع مثلًا —
# بقي في اليد وسمٌ يشير إلى اعتمادٍ لم يصل، ولو دُمج بعدها انتقل الاعتماد وبقي
# الوسم يشير إلى يتيم. الوسم الآن لا يُخلق إلّا على شيءٍ صار على origin.
step "الدفع"
push_branch

step "الوسم"
make_tag
push_tag

if (( ! DO_RELEASE )); then
  warn "لم يُنشأ إصدارٌ على GitHub (بطلبك أو لغياب الاعتماد)."
  exit 0
fi

step "إصدار GitHub"
publish_release

ok "تمّ."
printf '\n%s\n' "${C_BOLD}${TAG}${C_RESET} — $([[ "$REL_TYPE" == beta ]] && echo 'تجريبيّ' || echo 'نهائيّ')"
printf '%s\n' "الحزم في ${DIST}/"
if command -v gh >/dev/null; then
  printf '%s\n' "$(gh release view "$TAG" --json url -q .url 2>/dev/null || true)"
fi
