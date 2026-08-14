#!/usr/bin/env bash
# ============================================================================
#  GT-SPEEDOMETER — فاحص الشجرة
#
#  يُشغَّل من جذر المشروع بلا وسائط. يفحص الشجرة عن الشذوذ الذي يكسر البناء أو
#  يُلوّث المستودع، ويعرض تقريرًا. و`--fix` يحذف ما يأمن حذفه بعد تأكيدك.
#
#      ./CLEANUP.sh            فحصٌ فقط
#      ./CLEANUP.sh --fix      يحذف الزائد بعد تأكيد
#      ./CLEANUP.sh --fix -y   بلا تأكيد
#
#  الفحوص السبعة:
#    1. تعريفاتٌ مكرّرة  — صنفٌ واحد في ملفّين، وهو عطب «Redeclaration» الذي يوقف
#                          المصرّف. يُكتشف بلا Gradle وفي ثانية.
#    2. ملفّات Kotlin زائدة أو ناقصة مقابل بيان الإصدار المضمَّن أدناه.
#    3. موارد نصّية مفقودة — كلّ `R.string.x` مستعمَل له مقابلٌ في strings.xml.
#    4. أسرارٌ غير متجاهَلة — مخزن التوقيع وكلماته يجب ألّا تدخل git أبدًا.
#    5. مخرجات بناءٍ متتبَّعة — حزم أو مجلّدات build دخلت الاعتماد.
#    6. ملفّات دخيلة في الجذر — رقعات وملاحظات ومخلّفات فكّ ضغط.
#    7. موارد ميّتة — صور وأيقونات لا يشير إليها شيء.
# ============================================================================

set -uo pipefail

if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
  C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'
else
  C_RESET=''; C_BOLD=''; C_DIM=''; C_OK=''; C_WARN=''; C_ERR=''
fi
ok()    { printf '%s\n' "  ${C_OK}✓${C_RESET} $*"; }
bad()   { printf '%s\n' "  ${C_ERR}✗${C_RESET} $*"; }
warn()  { printf '%s\n' "  ${C_WARN}!${C_RESET} $*"; }
head2() { printf '\n%s\n' "${C_BOLD}▸ $*${C_RESET}"; }

FIX=0; YES=0
for a in "$@"; do
  case "$a" in
    --fix) FIX=1 ;;
    -y|--yes) YES=1 ;;
    -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "خيار غير معروف: $a" >&2; exit 1 ;;
  esac
done

SRC="app/src/main/java"
[[ -d "$SRC" ]] || { echo "شغّله من جذر المشروع (لم أجد $SRC)" >&2; exit 1; }

PKG_ROOT="$SRC/net/gnutux/speedometer"
PROBLEMS=0
DELETABLE=()

# ---------------------------------------------------------------------------
#  بيان الإصدار 0.5.0-beta: ملفّات Kotlin المعتمَدة، بمسارٍ نسبيّ إلى حزمة التطبيق
# ---------------------------------------------------------------------------
MANIFEST="MainActivity.kt
SpeedoApp.kt
core/TripEngine.kt
core/camera/CameraSession.kt
core/camera/HudMetrics.kt
core/camera/VideoOverlayPainter.kt
core/location/GnssInfo.kt
core/location/LocationEngine.kt
core/location/SpeedFilter.kt
core/location/SpeedSample.kt
core/map/OfflineMaps.kt
core/media/MediaRepository.kt
core/profile/VehicleProfile.kt
core/settings/AppSettings.kt
core/settings/ThemeMode.kt
core/trip/GpxReader.kt
core/trip/GpxWriter.kt
core/trip/TripRecorder.kt
core/trip/TripState.kt
service/SpeedIcon.kt
service/SpeedTileService.kt
service/TripService.kt
ui/Format.kt
ui/SpeedoViewModel.kt
ui/components/GpsStatusBar.kt
ui/components/RouteMap.kt
ui/components/SpeedGauge.kt
ui/components/StatTile.kt
ui/components/TripControls.kt
ui/components/VehicleSelector.kt
ui/screens/CameraScreen.kt
ui/screens/DigitalScreen.kt
ui/screens/MediaScreen.kt
ui/screens/PipScreen.kt
ui/screens/SettingsScreen.kt
ui/screens/SpeedometerScreen.kt
ui/screens/TripsScreen.kt
ui/theme/Theme.kt"
printf '%s\n' "${C_BOLD}GT-SPEEDOMETER — فحص الشجرة${C_RESET}"
printf '%s\n' "${C_DIM}$(pwd)${C_RESET}"

# ===========================================================================
# 1. تعريفاتٌ مكرّرة
#
#    هذا هو الفحص الذي يستحقّ السكريبت كلّه. لا يعتمد على بيانٍ ولا على حزمة:
#    يقرأ التعريفات العليا من كلّ ملفّ ويبحث عن اسمٍ كاملٍ في ملفّين. وهو نفس
#    ما يشتكي منه المصرّف بـ «Redeclaration»، لكنّه يظهر هنا في ثانيةٍ لا بعد
#    دقيقتين من الترجمة، وبسببه ظاهرًا لا بأخطاءٍ متتالية في ملفّاتٍ بريئة.
# ===========================================================================
head2 "تعريفات مكرّرة"
DUPES="$(
  find "$SRC" -name '*.kt' -print0 | while IFS= read -r -d '' f; do
    awk -v file="$f" '
      /^package /            { pkg = $2; sub(/;$/, "", pkg); next }
      /^[a-z ]*(class|interface|object)[ \t]/ {
        for (i = 1; i <= NF; i++) {
          if ($i == "class" || $i == "interface" || $i == "object") {
            name = $(i + 1)
            gsub(/[<(:{].*$/, "", name)
            if (name != "" && name ~ /^[A-Za-z_]/) print pkg "." name "\t" file
            break
          }
        }
      }
    ' "$f"
  done | sort | awk -F'\t' '{ if ($1 == prev) { print prevline; print $0 } prev = $1; prevline = $0 }' | sort -u
)"
if [[ -z "$DUPES" ]]; then
  ok "لا تعريف مكرّرًا"
else
  PROBLEMS=$((PROBLEMS + 1))
  bad "أسماءٌ معرَّفة في أكثر من ملفّ — المصرّف سيقف عندها:"
  printf '%s\n' "$DUPES" | awk -F'\t' '{ printf "      %-44s %s\n", $1, $2 }'
fi

# ===========================================================================
# 2. ملفّات Kotlin زائدة أو ناقصة
# ===========================================================================
head2 "مطابقة بيان الإصدار"
if [[ -d "$PKG_ROOT" ]]; then
  printf '%s\n' "$MANIFEST" | grep -v '^$' | sort > /tmp/.gt_keep
  (cd "$PKG_ROOT" && find . -name '*.kt' | sed 's|^\./||' | sort) > /tmp/.gt_have
  EXTRA="$(comm -13 /tmp/.gt_keep /tmp/.gt_have)"
  MISSING="$(comm -23 /tmp/.gt_keep /tmp/.gt_have)"
  if [[ -n "$EXTRA" ]]; then
    PROBLEMS=$((PROBLEMS + 1))
    bad "ملفّاتٌ عندك ليست من الإصدار (بقايا نسخةٍ أقدم):"
    while IFS= read -r r; do
      [[ -z "$r" ]] && continue
      printf '      %s\n' "$r"
      DELETABLE+=("$PKG_ROOT/$r")
    done <<< "$EXTRA"
  fi
  if [[ -n "$MISSING" ]]; then
    PROBLEMS=$((PROBLEMS + 1))
    bad "ملفّاتٌ ناقصة — انسخها من الحزمة:"
    printf '      %s\n' $MISSING
  fi
  if [[ -z "$EXTRA" && -z "$MISSING" ]]; then
    ok "$(printf '%s\n' "$MANIFEST" | grep -c .) ملفًّا كما ينبغي"
  fi
else
  warn "لم أجد $PKG_ROOT"
fi

# ===========================================================================
# 3. موارد نصّية مفقودة
# ===========================================================================
head2 "موارد النصوص"
STRINGS="app/src/main/res/values/strings.xml"
if [[ -f "$STRINGS" ]]; then
  grep -oE '<string name="[^"]+"' "$STRINGS" | sed 's/.*name="//; s/"//' | sort -u > /tmp/.gt_str
  grep -rhoE 'R\.string\.[A-Za-z0-9_]+' "$SRC" | sed 's/R\.string\.//' | sort -u > /tmp/.gt_used
  LOST="$(comm -13 /tmp/.gt_str /tmp/.gt_used)"
  DUPS="$(grep -oE '<string name="[^"]+"' "$STRINGS" | sort | uniq -d)"
  if [[ -n "$LOST" ]]; then
    PROBLEMS=$((PROBLEMS + 1))
    bad "مستعمَلة في الشفرة وغير معرَّفة:"; printf '      R.string.%s\n' $LOST
  fi
  if [[ -n "$DUPS" ]]; then
    PROBLEMS=$((PROBLEMS + 1))
    bad "معرَّفة مرّتين في strings.xml:"; printf '      %s\n' "$DUPS"
  fi
  if [[ -z "$LOST" && -z "$DUPS" ]]; then
    ok "$(wc -l < /tmp/.gt_str) نصًّا، وكلّ مستعمَلٍ له مقابل"
  fi
else
  warn "لم أجد $STRINGS"
fi

# ===========================================================================
# 4. أسرارٌ غير متجاهَلة
#
#    فقدُ مخزن التوقيع يعني عجزًا أبديًّا عن تحديث ما نُشر؛ وتسريبه أسوأ.
# ===========================================================================
head2 "أسرار التوقيع"
if git rev-parse --git-dir >/dev/null 2>&1; then
  LEAKED="$(git ls-files | grep -E 'keystore\.properties$|\.jks$|\.keystore$|^local\.properties$' | grep -v 'debug\.keystore' || true)"
  if [[ -n "$LEAKED" ]]; then
    PROBLEMS=$((PROBLEMS + 1))
    bad "متتبَّعة في git — أخرِجها فورًا بـ git rm --cached:"
    printf '      %s\n' $LEAKED
  else
    ok "لا سرّ متتبَّعًا"
  fi
  # لا تقل «متجاهَل» عن ملفٍّ أثبتنا للتوّ أنّه متتبَّع
  for f in keystore.properties *.jks; do
    [[ -e "$f" ]] || continue
    git ls-files --error-unmatch "$f" >/dev/null 2>&1 && continue
    printf '%s\n' "      ${C_DIM}موجودٌ محلّيًّا ومتجاهَل — احتفظ بنسخةٍ خارج الجهاز: $f${C_RESET}"
  done
else
  warn "ليس مستودع git — تخطّيتُ فحص التتبّع"
fi

# ===========================================================================
# 5. مخرجات بناءٍ متتبَّعة
# ===========================================================================
head2 "مخرجات البناء"
if git rev-parse --git-dir >/dev/null 2>&1; then
  BUILT="$(git ls-files | grep -E '\.apk$|\.aab$|^dist/|/build/|^\.gradle/' || true)"
  if [[ -n "$BUILT" ]]; then
    PROBLEMS=$((PROBLEMS + 1))
    bad "مخرجاتٌ دخلت الاعتماد:"; printf '      %s\n' $BUILT
  else
    ok "لا مخرج بناءٍ متتبَّعًا"
  fi
else
  warn "ليس مستودع git"
fi

# ===========================================================================
# 6. ملفّات دخيلة في الجذر
# ===========================================================================
NOTES=0
head2 "جذر المشروع"
KNOWN_ROOT=" app art gradle gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties README.md CHANGELOG.md CLAUDE.md LICENSE .gitignore .git scripts release.sh CLEANUP.sh signing-fingerprints.txt keystore.properties local.properties dist .gradle .idea build .claude "
STRAY=()
while IFS= read -r n; do
  case "$n" in *.jks|*.keystore) continue ;; esac
  [[ "$KNOWN_ROOT" == *" $n "* ]] || STRAY+=("$n")
done < <(find . -maxdepth 1 -mindepth 1 -printf '%f\n' | sort)
if (( ${#STRAY[@]} )); then
  NOTES=$((NOTES + 1))
  warn "دخيلٌ على الجذر (راجِعها بنفسك؛ لا تُحذف آليًّا):"
  printf '      %s\n' "${STRAY[@]}"
else
  ok "الجذر نظيف"
fi

# ===========================================================================
# 7. موارد ميّتة
# ===========================================================================
head2 "موارد لا يشير إليها شيء"
DEAD=()
RES="app/src/main/res"
if [[ -d "$RES" ]]; then
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    n="$(basename "$f")"; n="${n%.*}"
    kind="$(basename "$(dirname "$f")")"; kind="${kind%%-*}"
    case "$n" in ic_launcher*) [[ "$kind" == mipmap ]] && continue ;; esac
    if ! grep -rqF "$kind/$n" "$RES" "app/src/main/AndroidManifest.xml" 2>/dev/null \
       && ! grep -rqE "R\.$kind\.$n\b" "$SRC" 2>/dev/null; then
      DEAD+=("$f")
    fi
  done < <(find "$RES/drawable" -type f 2>/dev/null)
fi
if (( ${#DEAD[@]} )); then
  warn "غير مُشارٍ إليها:"
  printf '      %s\n' "${DEAD[@]}"
  DELETABLE+=("${DEAD[@]}")
else
  ok "لا مورد ميّتًا"
fi

# ===========================================================================
#  الخلاصة والحذف
# ===========================================================================
printf '\n'
if (( PROBLEMS == 0 )) && (( ${#DELETABLE[@]} == 0 )); then
  if (( NOTES )); then
    printf '%s\n' "${C_OK}${C_BOLD}لا شيء يكسر البناء${C_RESET} — وبقيت ${NOTES} ملاحظة أعلاه."
  else
    printf '%s\n' "${C_OK}${C_BOLD}الشجرة سليمة.${C_RESET}"
  fi
  exit 0
fi

if (( ${#DELETABLE[@]} == 0 )); then
  printf '%s\n' "${C_WARN}${C_BOLD}${PROBLEMS} مسألة تحتاج يدك — لا شيء يُحذف آليًّا.${C_RESET}"
  exit 1
fi

printf '%s\n' "${C_BOLD}يمكن حذفها آليًّا (${#DELETABLE[@]}):${C_RESET}"
printf '  %s\n' "${DELETABLE[@]}"

if (( ! FIX )); then
  printf '\n%s\n' "للحذف:  ./CLEANUP.sh --fix"
  exit 1
fi
if (( ! YES )); then
  printf '\n  أأحذفها؟\n'
  read -r -p "  > y/n [n]: " a
  [[ "$a" =~ ^([yY]|نعم)$ ]] || { echo "أُلغي."; exit 1; }
fi
rm -f "${DELETABLE[@]}"
printf '%s\n' "${C_OK}حُذف ${#DELETABLE[@]} ملفًّا. أعد التشغيل للتحقّق.${C_RESET}"
