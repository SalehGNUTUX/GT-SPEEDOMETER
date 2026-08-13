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
#    --yes           لا تسأل تأكيدًا (للتشغيل الآليّ)
#    --no-push       ابنِ واعتمد محلّيًّا بلا دفع
#    --no-release    ادفع بلا إنشاء إصدارٍ على GitHub
#    --dry-run       تجربةٌ جافّة
#    --help          هذه الرسالة
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
ARG_CODE=""; ARG_NAME=""; ARG_TYPE=""; ARG_VARIANT=""
ASSUME_YES=0; DO_PUSH=1; DO_RELEASE=1; DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --code)    ARG_CODE="${2:-}"; shift 2 ;;
    --name)    ARG_NAME="${2:-}"; shift 2 ;;
    --type)    ARG_TYPE="${2:-}"; shift 2 ;;
    --variant) ARG_VARIANT="${2:-}"; shift 2 ;;
    --yes|-y)  ASSUME_YES=1; shift ;;
    --no-push) DO_PUSH=0; DO_RELEASE=0; shift ;;
    --no-release) DO_RELEASE=0; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help|-h) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "خيار غير معروف: $1  (جرّب --help)" ;;
  esac
done

run() {
  if (( DRY_RUN )); then
    printf '%s\n' "${C_DIM}[تجربة جافّة] $*${C_RESET}"
  else
    "$@"
  fi
}

confirm() {
  local prompt="$1"
  # لا تكتبها `(( X )) && return 0`: تحت `set -e` تُنهي السكريبت حين تكون X صفرًا
  if (( ASSUME_YES )); then return 0; fi
  local reply
  read -r -p "${prompt} [n/y] " reply
  [[ "$reply" =~ ^([yY]|نعم)$ ]]
}

ask() {                       # ask <النصّ> <الافتراضيّ> <اسم المتغيّر>
  local prompt="$1" default="$2" __var="$3" reply
  if (( ASSUME_YES )) && [[ -n "$default" ]]; then
    printf -v "$__var" '%s' "$default"; return
  fi
  if [[ -n "$default" ]]; then
    read -r -p "${prompt} [${default}]: " reply
    reply="${reply:-$default}"
  else
    read -r -p "${prompt}: " reply
  fi
  printf -v "$__var" '%s' "$reply"
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

# ---------- الإصدار الحاليّ ----------
CUR_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" | head -1)"
CUR_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)"
[[ -n "$CUR_CODE" && -n "$CUR_NAME" ]] || die "تعذّرت قراءة versionCode/versionName من $GRADLE_FILE."

step "الإصدار الحاليّ"
printf '  versionCode = %s\n  versionName = %s\n' "$CUR_CODE" "$CUR_NAME"

# ---------- الأسئلة ----------
step "الإصدار الجديد"

NEW_CODE="$ARG_CODE"
[[ -n "$NEW_CODE" ]] || ask "رقم الإصدار (versionCode)" "$((CUR_CODE + 1))" NEW_CODE
[[ "$NEW_CODE" =~ ^[0-9]+$ ]] || die "رقم الإصدار يجب أن يكون عددًا صحيحًا: '$NEW_CODE'"
(( NEW_CODE > CUR_CODE )) || die "رقم الإصدار ($NEW_CODE) يجب أن يفوق الحاليّ ($CUR_CODE)."

REL_TYPE="$ARG_TYPE"
if [[ -z "$REL_TYPE" ]]; then
  echo "  نوع الإصدار:  1) تجريبيّ (beta)   2) نهائيّ (final)"
  ask "اختر" "1" _t
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

NEW_NAME="$ARG_NAME"
[[ -n "$NEW_NAME" ]] || ask "اسم الإصدار (versionName)" "$SUGGEST" NEW_NAME
[[ "$NEW_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]] \
  || die "اسم الإصدار يجب أن يكون على نسق 1.2.3 أو 1.2.3-beta: '$NEW_NAME'"

if [[ "$REL_TYPE" == "final" && "$NEW_NAME" == *-* ]]; then
  warn "إصدارٌ نهائيّ باسمٍ يحمل لاحقة ('$NEW_NAME')."
  confirm "أأمضي؟" || die "أُلغي."
fi
if [[ "$REL_TYPE" == "beta" && "$NEW_NAME" != *-* ]]; then
  warn "إصدارٌ تجريبيّ باسمٍ بلا لاحقة ('$NEW_NAME')."
  confirm "أأمضي؟" || die "أُلغي."
fi

TAG="v${NEW_NAME}"
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  die "الوسم ${TAG} موجودٌ سلفًا."
fi

# ---------- سمة البناء ----------
VARIANT="$ARG_VARIANT"
if [[ -z "$VARIANT" ]]; then
  DEFAULT_VARIANT="debug"
  if [[ "$REL_TYPE" == "final" ]]; then DEFAULT_VARIANT="release"; fi
  echo "  سمة البناء:  1) debug   2) release   3) كلتاهما"
  ask "اختر" "$([[ $DEFAULT_VARIANT == debug ]] && echo 1 || echo 2)" _v
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
NOTES_FILE="$(mktemp)"
trap 'rm -f "$NOTES_FILE"' EXIT
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

# ---------- ملخّص ----------
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
step "الملخّص"
cat <<EOF
  الإصدار      : ${CUR_NAME} (${CUR_CODE})  ←  ${NEW_NAME} (${NEW_CODE})
  النوع        : $([[ "$REL_TYPE" == beta ]] && echo 'تجريبيّ — prerelease' || echo 'نهائيّ — latest')
  سمة البناء   : ${VARIANT}
  الوسم        : ${TAG}
  الفرع        : ${BRANCH}
  الدفع        : $([[ $DO_PUSH == 1 ]] && echo 'نعم' || echo 'لا')
  إنشاء الإصدار: $([[ $DO_RELEASE == 1 ]] && echo 'نعم' || echo 'لا')
EOF
if (( DRY_RUN )); then warn "تجربةٌ جافّة: لن يُكتب ولا يُدفع شيء."; fi
confirm "أأمضي؟" || die "أُلغي."

# ---------- كتابة الإصدار ----------
step "تحديث $GRADLE_FILE"
# النسخة الاحتياطيّة خارج المستودع عمدًا: `git add -A` كان سيعتمدها لو جاورت الأصل،
# والاستعادة منها أدقّ من `git checkout --` الذي يمحو أيّ تعديلٍ سابقٍ غير معتمد
GRADLE_BAK="$(mktemp)"
trap 'rm -f "$NOTES_FILE" "$GRADLE_BAK"' EXIT
if (( ! DRY_RUN )); then
  cp "$GRADLE_FILE" "$GRADLE_BAK"
  sed -i -E "s/(versionCode\s*=\s*)[0-9]+/\1${NEW_CODE}/" "$GRADLE_FILE"
  sed -i -E "s/(versionName\s*=\s*\")[^\"]+(\")/\1${NEW_NAME}\2/" "$GRADLE_FILE"
  if ! grep -qE "versionCode\s*=\s*${NEW_CODE}\b" "$GRADLE_FILE" \
     || ! grep -qF "versionName = \"${NEW_NAME}\"" "$GRADLE_FILE"; then
    cp "$GRADLE_BAK" "$GRADLE_FILE"
    die "فشل تحديث الإصدار في $GRADLE_FILE."
  fi
fi
ok "versionCode=${NEW_CODE} · versionName=${NEW_NAME}"

# ---------- البناء ----------
step "البناء"
TASKS=()
if [[ "$VARIANT" == "debug"   || "$VARIANT" == "both" ]]; then TASKS+=(":app:assembleDebug"); fi
if [[ "$VARIANT" == "release" || "$VARIANT" == "both" ]]; then TASKS+=(":app:assembleRelease"); fi
say "./gradlew ${TASKS[*]}"
if ! run ./gradlew --console=plain "${TASKS[@]}"; then
  warn "فشل البناء. أُعيد $GRADLE_FILE إلى ما كان."
  if (( ! DRY_RUN )); then cp "$GRADLE_BAK" "$GRADLE_FILE"; fi
  die "أُلغي عند البناء."
fi

DIST="dist/${NEW_NAME}"
run mkdir -p "$DIST"
# `*.apk` متجاهَلة في .gitignore، أمّا ملفّات sha256 فلا — ولولا هذا لدخلت الاعتماد
if [[ -f .gitignore ]] && ! grep -qxF 'dist/' .gitignore; then
  if (( ! DRY_RUN )); then printf '\n# حزم الإصدار المبنيّة محلّيًّا\ndist/\n' >> .gitignore; fi
  say "أُضيف dist/ إلى .gitignore"
fi
ARTIFACTS=()
for v in debug release; do
  [[ "$VARIANT" == "$v" || "$VARIANT" == "both" ]] || continue
  SRC="app/build/outputs/apk/${v}/app-${v}.apk"
  [[ -f "$SRC" ]] || SRC="app/build/outputs/apk/${v}/app-${v}-unsigned.apk"
  if (( DRY_RUN )); then
    ARTIFACTS+=("${DIST}/GT-SPEEDOMETER-${NEW_NAME}-${v}.apk"); continue
  fi
  [[ -f "$SRC" ]] || die "لم أجد حزمة ${v} في app/build/outputs/apk/${v}/"
  OUT="${DIST}/GT-SPEEDOMETER-${NEW_NAME}-${v}.apk"
  cp "$SRC" "$OUT"
  ( cd "$DIST" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256" )
  ARTIFACTS+=("$OUT" "${OUT}.sha256")
  ok "$(basename "$OUT")  —  $(du -h "$OUT" | cut -f1)"
done

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
  DEFAULT_MSG="رفع الإصدار التجريبيّ إلى ${NEW_NAME}"
  if [[ "$REL_TYPE" == "final" ]]; then DEFAULT_MSG="إصدار ${NEW_NAME}"; fi
  ask "رسالة الاعتماد" "$DEFAULT_MSG" COMMIT_MSG
  run git add -A
  # قاعدة المستودع: الرسائل بالعربيّة، وباسم SalehGNUTUX وحده بلا Co-Authored-By
  run git commit -m "$COMMIT_MSG"
  ok "اعتُمد"
fi

step "الوسم"
TAG_MSG="${NEW_NAME}"
if [[ -s "$NOTES_FILE" ]]; then
  TAG_MSG="$(printf '%s\n\n%s' "${NEW_NAME}" "$(head -20 "$NOTES_FILE")")"
fi
run git tag -a "$TAG" -m "$TAG_MSG"
ok "$TAG"

if (( ! DO_PUSH )); then
  warn "--no-push: توقّفنا هنا. للدفع لاحقًا:  git push origin ${BRANCH} && git push origin ${TAG}"
  exit 0
fi

step "الدفع"
# الفرع المتأخّر عن البعيد يُرفض دفعُه، ومعرفة ذلك قبل الوسم أهون من بعده
if git rev-parse --abbrev-ref "@{upstream}" >/dev/null 2>&1; then
  git fetch --quiet origin "$BRANCH" 2>/dev/null || true
  BEHIND="$(git rev-list --count "HEAD..origin/${BRANCH}" 2>/dev/null || echo 0)"
  if [[ "$BEHIND" != "0" ]]; then
    warn "فرعك متأخّر عن origin/${BRANCH} بـ ${BEHIND} اعتمادًا — سيُرفض الدفع."
    warn "ادمج أوّلًا:  git pull --rebase origin ${BRANCH}"
    confirm "أأحاول الدفع رغم ذلك؟" || die "أُلغي قبل الدفع. الوسم ${TAG} موجودٌ محلّيًّا."
  fi
fi
run git push origin "$BRANCH"
run git push origin "$TAG"
ok "دُفع ${BRANCH} و ${TAG}"

# ---------- إصدار GitHub ----------
if (( ! DO_RELEASE )); then
  warn "لم يُنشأ إصدارٌ على GitHub (بطلبك أو لغياب الاعتماد)."
  exit 0
fi

step "إصدار GitHub"
TITLE="${TAG}"
if [[ "$REL_TYPE" == "beta" ]]; then PRERELEASE_FLAG="--prerelease"; else PRERELEASE_FLAG="--latest"; fi

if (( HAS_GH )); then
  say "gh release create ${TAG} ${PRERELEASE_FLAG}"
  run gh release create "$TAG" \
      --title "$TITLE" \
      --notes-file "$NOTES_FILE" \
      $PRERELEASE_FLAG \
      "${ARTIFACTS[@]+"${ARTIFACTS[@]}"}"
else
  # مسارٌ بديل بلا gh: واجهة GitHub مباشرةً
  SLUG="$(git remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"
  PRE=false
  if [[ "$REL_TYPE" == "beta" ]]; then PRE=true; fi
  say "إنشاء الإصدار عبر واجهة GitHub لـ ${SLUG}"
  if (( ! DRY_RUN )); then
    BODY="$(python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' < "$NOTES_FILE")"
    RESP="$(curl -sS -X POST \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      "https://api.github.com/repos/${SLUG}/releases" \
      -d "{\"tag_name\":\"${TAG}\",\"name\":\"${TITLE}\",\"body\":${BODY},\"prerelease\":${PRE},\"make_latest\":\"$([[ $PRE == true ]] && echo false || echo true)\"}")"
    UPLOAD_URL="$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("upload_url","").split("{")[0])')"
    [[ -n "$UPLOAD_URL" ]] || { printf '%s\n' "$RESP" >&2; die "تعذّر إنشاء الإصدار."; }
    for f in "${ARTIFACTS[@]+"${ARTIFACTS[@]}"}"; do
      say "رفع $(basename "$f")"
      curl -sS -X POST \
        -H "Authorization: Bearer ${GITHUB_TOKEN}" \
        -H "Content-Type: application/octet-stream" \
        --data-binary @"$f" \
        "${UPLOAD_URL}?name=$(basename "$f")" >/dev/null
    done
  fi
fi

ok "تمّ."
printf '\n%s\n' "${C_BOLD}${TAG}${C_RESET} — $([[ "$REL_TYPE" == beta ]] && echo 'تجريبيّ' || echo 'نهائيّ')"
printf '%s\n' "الحزم في ${DIST}/"
if command -v gh >/dev/null; then
  printf '%s\n' "$(gh release view "$TAG" --json url -q .url 2>/dev/null || true)"
fi
