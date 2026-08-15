#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
مولّد صفحة GT-SPEEDOMETER.

يُشغَّل من **جذر المشروع** بلا وسائط فيكتب `index.html` فيه:

    python3 site/build.py

الناتج ملفٌّ واحد مكتفٍ بذاته: الأيقونة مضمَّنة فيه بترميز base64، ولا يطلب من
الشبكة خطًّا ولا نصًّا برمجيًّا ولا صورة. وهذا مقصود — الصفحة تُفتح من القرص كما
تُفتح من الخادم، ومن يحفظها يحفظها كاملة.

## إضافة إصدار — لا شيء
جدول التنزيل يُقرأ من `CHANGELOG.md` آليًّا: كلّ عنوانٍ `## vX.Y.Z` يصير سطرًا،
و«أبرز ما فيه» هي العناوين العريضة من بنود «### أُضيف». فاكتب سجلّ التغييرات
كعادتك وشغّل هذا الملفّ، ولا قائمةَ ثانية تُحدَّث.

ورقمُ الإصدار في `app/build.gradle.kts` يُقابَل بأحدث ما في السجلّ، فإن اختلفا
نبّه المولّد ولم يتوقّف — كي لا تُنشر صفحةٌ تعلن إصدارًا غير الذي في الشجرة.

أمّا قسم **الخصائص** فيبقى محرَّرًا باليد في [FEATURES]: سجلّ التغييرات يقول ما
**تغيّر** في إصدار، والصفحة تقول ما **في التطبيق** اليوم — وهما سؤالان مختلفان.

## الأيقونة
تُؤخذ من `art/gt-speedometer-icon.png` إن كانت Pillow مثبَّتة: تُقتطع خلفيّتها
البيضاء وتُصغَّر. وإن لم تكن مثبَّتة استُعملت `ic_launcher_round.png` من موارد
التطبيق كما هي — فالصفحة تُبنى على أيّ جهازٍ بلا تثبيت شيء، والفرق بينهما
إطارٌ أبيض حول الأيقونة لا أكثر.
"""

from __future__ import annotations

import base64
import io
import re
import sys
from pathlib import Path

# ===========================================================================
#  ما يُحرَّر باليد
# ===========================================================================

REPO = "https://github.com/SalehGNUTUX/GT-SPEEDOMETER"
GNUTUX = "https://salehgnutux.github.io/gnutux/"
BLOG = "https://gnutuxblog.wordpress.com/"
DEVELOPER = "https://github.com/SalehGNUTUX"

# ---------------------------------------------------------------------------
#  الإصدارات — تُقرأ من CHANGELOG.md آليًّا، ولا تُكتب هنا
#
#  مصدرُ حقيقةٍ واحد: ما دمتَ تكتب سجلّ التغييرات مع كلّ إصدار (وأنت تكتبه)،
#  فلا حاجة إلى قائمةٍ ثانية تُنسى فتكذب الصفحةُ على القارئ. تُؤخذ العناوين
#  العريضة من بنود «### أُضيف» — وهي بالضبط «أبرز ما فيه».
#
#  والقائمة أدناه احتياطٌ لا أصل: تُستعمل إن غاب CHANGELOG.md أو تعذّر تحليله.
# ---------------------------------------------------------------------------

CHANGELOG = Path("CHANGELOG.md")

#: أقصى عددٍ من العناوين يُعرض في عمود «أبرز ما فيه»؛ ما زاد لا يُقرأ في سطرين
HIGHLIGHTS = 4

#: الأقسام التي تُؤخذ منها العناوين، بترتيب الأفضليّة
HIGHLIGHT_SECTIONS = ("أُضيف", "أُصلح")

FALLBACK_RELEASES: list[tuple[str, str]] = [
    ("0.9.0", "الكشّاف · تبديل العدسة أثناء التسجيل · التصوير بالكاميرتين في ملفٍّ واحد"),
    ("0.8.0", "خريطة OsmAnd وهو مغلق · تقريبٌ وتنقّل · إيقافٌ مؤقّت · تحديدٌ متعدّد · دعم Android Go"),
    ("0.7.0", "خريطة الرحلة من خرائط OsmAnd المحلّيّة · مسارٌ بلا خريطة أساس · سرعةٌ قصوى وتنبيهٌ صوتيّ"),
    ("0.6.0", "متوسّط السرعة في الطبقة · منطقةٌ آمنة للمحروق · الوسائط قسمان"),
    ("0.5.0", "الخريطة المحلّيّة أوّلًا · طول المقطع من شاشة الكاميرا"),
    ("0.4.0", "التسجيل لا ينقطع · نافذة عائمة · إعداداتٌ شاملة · أربعة أوضاع للسمة"),
    ("0.3.0", "حرق العدّاد داخل الفيديو · سجلّ الرحلات وخريطة المسار"),
    ("0.2.0", "السرعة في شريط الحالة · مربّع الإعدادات السريعة · قسم الوسائط"),
]

_HEADING = re.compile(r"^##\s+v?(\d+\.\d+\.\d+)")
_SUBHEADING = re.compile(r"^###\s+(.+?)\s*$")
#: بندٌ يبدأ بعنوانٍ عريض: «- **العنوان** بقيّة الكلام» أو «- **العنوان.** …»
_BULLET_LEAD = re.compile(r"^-\s+\*\*(.+?)\*\*")


def parse_changelog() -> list[tuple[str, str]]:
    """(النسخة، أبرز ما فيها) لكلّ إصدارٍ في سجلّ التغييرات، الأحدث أوّلًا."""
    if not CHANGELOG.exists():
        return []

    #: {النسخة: {القسم: [عناوين]}}
    found: list[tuple[str, dict[str, list[str]]]] = []
    version: str | None = None
    section = ""
    for line in CHANGELOG.read_text(encoding="utf-8").splitlines():
        head = _HEADING.match(line)
        if head:
            version = head.group(1)
            section = ""
            found.append((version, {}))
            continue
        if version is None:
            continue
        sub = _SUBHEADING.match(line)
        if sub:
            section = sub.group(1)
            continue
        lead = _BULLET_LEAD.match(line)
        if lead and section:
            # النقطة في آخر العنوان العريض جزءٌ من الجملة لا من الاسم
            found[-1][1].setdefault(section, []).append(lead.group(1).rstrip(" .،:"))

    out: list[tuple[str, str]] = []
    for ver, sections in found:
        heads: list[str] = []
        for name in HIGHLIGHT_SECTIONS:
            heads.extend(sections.get(name, []))
            if len(heads) >= HIGHLIGHTS:
                break
        if heads:
            out.append((ver, " · ".join(heads[:HIGHLIGHTS])))
    return out


def releases() -> list[tuple[str, str]]:
    parsed = parse_changelog()
    if parsed:
        return parsed
    print("• تعذّرت قراءة CHANGELOG.md — استُعملت القائمة الاحتياطيّة في build.py",
          file=sys.stderr)
    return FALLBACK_RELEASES


# (عنوان المجموعة، معرّفها، [(عنوان البطاقة، شرحها)])
FEATURES: list[tuple[str, str, list[tuple[str, str]]]] = [
    ("القياس", "speed", [
        ("قرص عدّاد يتلوّن بالمنطقة",
         "عاديّ ثمّ تحذير ثمّ خطر. اللون يُلمح قبل أن يُقرأ الرقم، وهذا ما يحتاجه من يقود."),
        ("شاشة رقميّة برقمٍ ضخم",
         "تُقرأ بلمحةٍ من فوق المقود."),
        ("ملفّات مركبات أربعة",
         "سيّارة · درّاجة ناريّة · هوائيّة · مشي. لكلٍّ مدى قرصٍ وعتبةُ تحذيرٍ وشدّةُ تنعيمٍ وعتبةُ توقّف."),
        ("حالة تموضعٍ صادقة",
         "عدد الأقمار والدقّة و<strong>المعدّل المتحقّق فعلًا</strong> بالهرتز — لا معدّلًا مطلوبًا يُدّعى."),
        ("سرعةٌ قصوى يحدّدها السائق",
         "يُضبط عليها مدى القرص، وتُرسم عندها علامةٌ حمراء، وما بعدها بلون الخطر."),
        ("تنبيهٌ صوتيّ متدرّج",
         "صفيرةٌ عند التجاوز، فإن دام صار متكرّرًا. يعمل والتطبيق في الخلفيّة والشاشة مطفأة."),
    ]),
    ("الكاميرا", "cam", [
        ("عدّادٌ فوق كاميرا حيّة",
         "الميزة التي يقوم عليها التطبيق: ترى الطريق والرقم معًا."),
        ("حرق الطبقة داخل الملفّ",
         "توگل اختياريّ. المسافة وأقصى سرعة والمتوسّط والمدّة والقرص، محروقةً في الفيديو نفسه."),
        ("منطقةٌ آمنة للطبقة المحروقة",
         "الملفّ ‎16:9‎ والشاشة ‎20:9‎، والمشغّلات تقتطع ‎%10‎ من كلّ جانب. الطبقة تُرسم داخل ما ينجو."),
        ("تقسيم اختياريّ للمقاطع",
         "متّصل أو كلّ 3 · 5 · 7 · 10 دقائق، يُضبط من شاشة الكاميرا مباشرةً."),
        ("إيقافٌ مؤقّت للتسجيل",
         "الفيديو وحده يقف والرحلة تمضي، فلا تُفسد وقفةٌ عند إشارةٍ إحصاءاتِ رحلتك."),
        ("وضع تصويرٍ ليليّ ونهاريّ",
         "تلقائيّ يتبع ساعتَي المظهر، أو يدويّ. لا يقطع تسجيلًا جاريًا."),
        ("لقطة شاشةٍ بالطبقة",
         "صورةٌ واحدة تجمع المشهد والعدّاد."),
        ("التسجيل لا ينقطع",
         "لا بمغادرة التطبيق، ولا بانطفاء الشاشة، ولا بمكالمةٍ واردة، ولا بتبديل الأقسام."),
    ]),
    ("المسار والخرائط", "map", [
        ("سجلّ رحلاتٍ كامل",
         "مسافةٌ ومدّةٌ وأقصى سرعةٍ ومتوسّط (على زمن الحركة وحده)، مع خريطة المسار."),
        ("GPX 1.1 صيغةً أصليّة",
         "لا مصدَّرة. يفتحها OsmAnd و OpenTracks و JOSM، ويحمل الملفّ اسمَ الفيديو المرافق وإزاحته الزمنيّة."),
        ("خرائط محلّيّة دون اتّصال",
         "أرشيفات ‎.mbtiles‎ و‎.sqlite‎ و‎.gemf‎ وأشجار البلاطات، مع قارئٍ يفهم صيغتَي OsmAnd و Locus."),
        ("جسرٌ إلى OsmAnd",
         "خرائط ‎.obf‎ المتجهيّة لا يرسمها أحدٌ غير OsmAnd — فنسأله أن يرسم مسارك على خرائطه ويسلّمنا الصورة."),
        ("مسارٌ بلا خريطة أساس",
         "حين لا بلاطات ولا OsmAnd ولا اتّصال: المسار بعلامتَي طرفيه وشبكةٍ ومقياس رسم. يعمل دائمًا."),
        ("مبدّل مصدر الخريطة",
         "تلقائيّ · OsmAnd · بلاطات — فأيّهما أفضل سؤالٌ لا جواب واحد له."),
        ("تراجعٌ بعد الحذف",
         "مهلةٌ تُضبط، تردّ رحلةً حُذفت بالخطأ."),
    ]),
    ("خارج التطبيق", "sys", [
        ("السرعة في شريط الحالة",
         "رقمٌ يُرسم وقت التشغيل، يُلمح دون فتح التطبيق."),
        ("مربّع في الإعدادات السريعة",
         "يبدأ الرحلة وينهيها بلمسة."),
        ("نافذة عائمة (PiP)",
         "تُظهر السرعة حين تغادر التطبيق ورحلةٌ أو تسجيلٌ جارٍ."),
        ("خدمة أماميّة",
         "تُبقي القياس حيًّا والتطبيق في الخلفيّة، بإشعارٍ يقول ما يجري."),
    ]),
    ("الواجهة", "ui", [
        ("عربيّة كاملة من اليمين",
         "لا ترجمةً ملصقة: التخطيط والأرقام والاتّجاه ثنائيّ الجهة كلّها مبنيّةٌ عليها."),
        ("أربعة أوضاع للسمة",
         "تلقائيّ بالوقت · حسب النظام · داكن · فاتح، بلوحةٍ فاتحةٍ تُقرأ تحت الشمس."),
        ("وضعٌ غامر في الكاميرا",
         "تملأ المعاينة الشاشة مع بدء الرحلة، ولمسةٌ تُعيد الشريط."),
        ("وسائط بقسمين",
         "صورٌ وفيديوهات، مع تحديدٍ متعدّد للحذف والمشاركة دفعةً واحدة."),
        ("دعم أجهزة Android Go",
         "وضعٌ مخفَّف يُكتشف تلقائيًّا ويقبل التجاوز، وتثبيتُ موقعٍ سريع موسومٌ «تقريبيّ»."),
    ]),
]

WHY = [
    ("الرقم داخل الملفّ لا فوق شاشةٍ تُطفأ",
     "طبقة العدّاد تُحرق داخل ملفّ الفيديو نفسه: المسافة وأقصى سرعةٍ والمتوسّط "
     "والمدّة، مطابقةً لما تراه على الشاشة. ومن أراد فيديو نظيفًا أطفأ التوگل، "
     "فالمسار يُحفظ مستقلًّا بـ GPX على أيّ حال."),
    ("يعمل دون اتّصالٍ حقًّا",
     "القياس والتسجيل وحفظ المسار لا تحتاج شبكةً أصلًا. وحتّى الخريطة: أرشيف "
     "بلاطاتٍ على جهازك، أو خرائط OsmAnd المتجهيّة عبر جسرٍ إليه، أو مخطَّطُ "
     "مسارٍ يعمل بلا أيّ ملفّ."),
    ("يقول لك ما لا يعرفه",
     "يعرض <strong>المعدّل المتحقّق فعلًا</strong> لا المطلوب، ويسمّي الموضع "
     "التقريبيّ «تقريبيًّا» لا «GPS»، ويفرّق بين «حُفظ الملفّ» و«حُفظ لكنّه "
     "انقطع» و«لا ملفّ». أداةُ قياسٍ تُخفي حدودها تكذب على صاحبها."),
]

LIMITS = [
    ("هرتزٌ واحد، لا أكثر",
     "أغلب هواتف المستهلك تسلّم عيّنة موقعٍ واحدة في الثانية مهما طلب التطبيق "
     "أسرع. يعني ذلك خطأً يقارب <code>±0.5s</code> في قياس ‎0-100‎، ولهذا تُباع "
     "وحدات GPS خارجيّة بعشرة هرتز. التطبيق يعرض المعدّل المتحقّق بدل ادّعاء "
     "دقّةٍ لا يملكها."),
    ("خرائط ‎.obf‎ لا يرسمها إلّا OsmAnd",
     "ما تنزّله OsmAnd صيغةٌ متجهيّة لا يرسمها محرّك البلاطات، ولا يُقرأ مجلّده "
     "على أندرويد ‎11‎ فما فوق. فالتطبيق يسأل OsmAnd نفسه أن يرسم مسارك — ويلزم "
     "لذلك أن تأذن له مرّةً واحدة من إعداداته."),
    ("الحرق ليس مجّانيًّا",
     "حرق الطبقة يمرّ بكلّ إطارٍ على خيط رسوميّات، وبعض الأجهزة لا تدعمه على "
     "مسار الفيديو أصلًا. حينها يرتدّ التطبيق إلى تسجيلٍ نظيفٍ ويُخبرك، ولا يترك "
     "الكاميرا معطّلة."),
]

LINKS = [
    ("مشاريع GNUTUX", GNUTUX, "بقيّة أدوات غنوتوكس الحرّة، عربيّةً ومفتوحة المصدر."),
    ("المستودع", REPO, "الشفرة كاملةً، وسجلّ التغييرات، والمسائل والمساهمات."),
    ("كلّ الإصدارات", f"{REPO}/releases", "الحزم وبصماتها وملاحظات كلّ إصدار."),
    ("مدوّنة GNUTUX", BLOG, "أخبار المشاريع ومقالات عن غنو/لينكس بالعربيّة."),
]

# ===========================================================================
#  الأيقونة
# ===========================================================================

ART_ICON = Path("art/gt-speedometer-icon.png")
FALLBACK_ICON = Path("app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png")
ICON_PX = 192


def _trim_white(image, size: int) -> bytes:
    """يزيل الأبيض المحيط بملءٍ من الأركان ثمّ يقتطع ويصغّر.

    الملء من الأركان لا استبدالُ كلّ أبيضَ في الصورة: في الأيقونة أبيضُ داخليّ
    (التدريج والكتابة) لا يجوز أن يصير شفّافًا.
    """
    from collections import deque

    image = image.convert("RGBA")
    px = image.load()
    w, h = image.size
    seen = bytearray(w * h)
    q: deque[tuple[int, int]] = deque()
    for x in range(w):
        q.append((x, 0))
        q.append((x, h - 1))
    for y in range(h):
        q.append((0, y))
        q.append((w - 1, y))
    while q:
        x, y = q.popleft()
        if not (0 <= x < w and 0 <= y < h) or seen[y * w + x]:
            continue
        seen[y * w + x] = 1
        r, g, b, _ = px[x, y]
        if not (r > 240 and g > 240 and b > 240):
            continue
        px[x, y] = (0, 0, 0, 0)
        q.extend(((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))

    box = image.getbbox()
    if box:
        image = image.crop(box)
    from PIL import Image as _Image
    image = image.resize((size, size), _Image.LANCZOS)
    buf = io.BytesIO()
    image.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def icon_data_uri() -> str:
    """أيقونة الصفحة data-URI. لا تفشل: الاحتياطيّ لا يحتاج أيّ حزمة."""
    if ART_ICON.exists():
        try:
            from PIL import Image
            data = _trim_white(Image.open(ART_ICON), ICON_PX)
            return "data:image/png;base64," + base64.b64encode(data).decode()
        except ImportError:
            print("• Pillow غير مثبَّتة — استُعملت أيقونة الموارد كما هي "
                  "(pip install pillow لأيقونةٍ بلا إطارٍ أبيض)", file=sys.stderr)
        except Exception as exc:  # صورةٌ تالفة أو صيغةٌ غير متوقَّعة
            print(f"• تعذّرت معالجة {ART_ICON}: {exc}", file=sys.stderr)

    if not FALLBACK_ICON.exists():
        sys.exit(f"لم أجد أيقونةً: لا {ART_ICON} ولا {FALLBACK_ICON}. "
                 "شغّل السكريبت من جذر المشروع.")
    return "data:image/png;base64," + base64.b64encode(FALLBACK_ICON.read_bytes()).decode()


# ===========================================================================
#  النسخة
# ===========================================================================

def gradle_version() -> str | None:
    """`versionName` من ملفّ البناء، بلا لاحقة `-beta`."""
    f = Path("app/build.gradle.kts")
    if not f.exists():
        return None
    m = re.search(r'versionName\s*=\s*"([^"]+)"', f.read_text(encoding="utf-8"))
    return re.sub(r"-beta$", "", m.group(1)) if m else None


# ===========================================================================
#  البناء
# ===========================================================================

def esc(s: str) -> str:
    """لا يمسّ الوسوم المقصودة (<strong> و<code>) — النصوص هنا يكتبها المطوّر لا مستخدم."""
    return s


def apk_url(version: str, kind: str) -> str:
    tag = f"v{version}-beta"
    return f"{REPO}/releases/download/{tag}/GT-SPEEDOMETER-{version}-beta-{kind}.apk"


def release_rows(items: list[tuple[str, str]]) -> str:
    out = []
    for i, (version, note) in enumerate(items):
        newest = i == 0
        cls = ' class="latest"' if newest else ""
        badge = '<span class="tag">الأحدث</span>' if newest else ""
        out.append(f"""      <tr{cls}>
        <th scope="row"><span class="v">{version}</span>{badge}</th>
        <td class="note">{esc(note)}</td>
        <td class="dl">
          <a href="{apk_url(version, 'release')}">release</a>
          <a href="{apk_url(version, 'debug')}">debug</a>
          <a href="{REPO}/releases/tag/v{version}-beta" class="ghost">التفاصيل</a>
        </td>
      </tr>""")
    return "\n".join(out)


def feature_html() -> str:
    out = []
    for title, key, items in FEATURES:
        cards = "\n".join(
            f'        <article class="card">\n'
            f'          <h3>{esc(head)}</h3>\n'
            f'          <p>{esc(body)}</p>\n'
            f'        </article>'
            for head, body in items
        )
        out.append(
            f'      <div class="group" id="f-{key}">\n'
            f'        <h3 class="glabel">{title}</h3>\n'
            f'      </div>\n'
            f'      <div class="cards">\n{cards}\n      </div>'
        )
    return "\n".join(out)


def trio(items: list[tuple[str, str]]) -> str:
    return "\n".join(
        f'      <div>\n        <h3>{esc(h)}</h3>\n        <p>{esc(b)}</p>\n      </div>'
        for h, b in items
    )


def link_cards() -> str:
    return "\n".join(
        f'      <a class="link" href="{url}">\n'
        f'        <b>{name}</b>\n'
        f'        <span>{desc}</span>\n'
        f'      </a>'
        for name, url, desc in LINKS
    )


CSS = """
  :root {
    --bg:#070B0E; --surface:#111A20; --surface-high:#18242C;
    --accent:#00E5C7; --accent-dim:#0A6E62;
    --warn:#FFB020; --danger:#FF5A45; --blue:#1E88E5;
    --text:#F2F7F9; --muted:#8DA0AC; --track:#223038;
    --radius:16px; --maxw:1080px;
  }
  *,*::before,*::after { box-sizing:border-box; }
  html { scroll-behavior:smooth; }
  body {
    margin:0; background:var(--bg); color:var(--text);
    font-family:"Noto Naskh Arabic","Amiri","Segoe UI",system-ui,-apple-system,sans-serif;
    font-size:17px; line-height:1.85; -webkit-text-size-adjust:100%;
  }
  a { color:var(--accent); text-decoration:none; }
  a:hover, a:focus-visible { text-decoration:underline; }
  :focus-visible { outline:2px solid var(--accent); outline-offset:3px; border-radius:6px; }
  .wrap { max-width:var(--maxw); margin-inline:auto; padding-inline:22px; }
  h1,h2,h3 { line-height:1.4; margin:0; }
  p { margin:0; }

  header {
    position:sticky; top:0; z-index:20;
    background:rgba(7,11,14,.86); backdrop-filter:blur(12px);
    border-bottom:1px solid var(--track);
  }
  .bar { display:flex; align-items:center; gap:14px; height:62px; }
  .bar img { width:34px; height:34px; border-radius:9px; }
  .bar b { font-size:1.02rem; letter-spacing:.02em; }
  nav { margin-inline-start:auto; display:flex; gap:20px; font-size:.93rem; }
  nav a { color:var(--muted); }
  nav a:hover { color:var(--text); text-decoration:none; }
  @media (max-width:680px) { nav { display:none; } }

  .hero {
    padding:76px 0 64px;
    background:
      radial-gradient(60rem 26rem at 78% -18%, rgba(0,229,199,.13), transparent 62%),
      radial-gradient(46rem 22rem at 8% 8%, rgba(30,136,229,.11), transparent 60%);
  }
  .hero-in { display:flex; align-items:center; gap:52px; flex-wrap:wrap; }
  .hero-text { flex:1 1 340px; min-width:0; }
  .hero img { width:184px; height:184px; flex:0 0 auto;
    filter:drop-shadow(0 18px 42px rgba(0,0,0,.6)); }
  .hero h1 { font-size:clamp(2.1rem,6.4vw,3.3rem); font-weight:800; letter-spacing:-.01em; }
  .lede { margin-top:16px; font-size:1.16rem; color:var(--muted); max-width:46ch; }
  .lede strong { color:var(--text); font-weight:700; }
  .pills { display:flex; flex-wrap:wrap; gap:8px; margin-top:22px; }
  .pill { font-size:.83rem; padding:5px 13px; border-radius:999px;
    background:var(--surface); border:1px solid var(--track); color:var(--muted); }
  .pill.on { color:var(--accent); border-color:var(--accent-dim); }
  .cta { display:flex; flex-wrap:wrap; gap:12px; margin-top:30px; }
  .btn { display:inline-flex; align-items:center; gap:9px;
    padding:14px 26px; border-radius:13px; font-weight:700; font-size:1rem;
    background:var(--accent); color:#04231F; border:1px solid transparent; }
  .btn:hover { text-decoration:none; filter:brightness(1.08); }
  .btn.alt { background:transparent; color:var(--text); border-color:var(--track); }
  .btn.alt:hover { border-color:var(--accent-dim); }
  .beta { margin-top:20px; font-size:.9rem; color:var(--warn);
    display:flex; align-items:center; gap:9px; }
  .beta::before { content:""; width:8px; height:8px; border-radius:50%;
    background:var(--warn); flex:0 0 auto; }

  section { padding:64px 0; border-top:1px solid var(--track); }
  .h2 { font-size:clamp(1.5rem,3.6vw,2rem); font-weight:800; }
  .sub { margin-top:10px; color:var(--muted); max-width:62ch; }

  .why { display:grid; gap:18px; grid-template-columns:repeat(auto-fit,minmax(260px,1fr)); margin-top:32px; }
  .why > div { background:var(--surface); border:1px solid var(--track);
    border-radius:var(--radius); padding:24px; }
  .why h3 { font-size:1.06rem; color:var(--accent); }
  .why p { margin-top:9px; color:var(--muted); font-size:.97rem; }

  .glabel { margin-top:38px; font-size:.86rem; font-weight:700; letter-spacing:.09em;
    color:var(--accent); text-transform:uppercase; }
  .glabel::after { content:""; display:block; height:1px; margin-top:12px;
    background:linear-gradient(to left, var(--accent-dim), transparent); }
  .cards { display:grid; gap:14px; grid-template-columns:repeat(auto-fit,minmax(272px,1fr)); margin-top:16px; }
  .card { background:var(--surface); border:1px solid var(--track);
    border-radius:var(--radius); padding:20px 22px; }
  .card h3 { font-size:1.02rem; font-weight:700; }
  .card p { margin-top:8px; color:var(--muted); font-size:.95rem; line-height:1.75; }
  .card strong { color:var(--text); }

  .tablewrap { margin-top:28px; overflow-x:auto; border:1px solid var(--track); border-radius:var(--radius); }
  table { width:100%; border-collapse:collapse; min-width:600px; }
  th, td { text-align:right; padding:16px 18px; border-bottom:1px solid var(--track); vertical-align:top; }
  thead th { background:var(--surface-high); font-size:.86rem; color:var(--muted); font-weight:700; }
  tbody tr:last-child th, tbody tr:last-child td { border-bottom:0; }
  tbody tr.latest { background:rgba(0,229,199,.05); }
  .v { font-weight:800; font-size:1.05rem; font-variant-numeric:tabular-nums; }
  .tag { display:inline-block; margin-inline-start:9px; padding:2px 9px; border-radius:999px;
    background:var(--accent); color:#04231F; font-size:.72rem; font-weight:700; }
  td.note { color:var(--muted); font-size:.93rem; line-height:1.7; }
  td.dl { white-space:nowrap; }
  td.dl a { display:inline-block; margin-inline-end:8px; padding:6px 13px; border-radius:9px;
    background:var(--surface-high); border:1px solid var(--track);
    font-size:.86rem; font-weight:700; }
  td.dl a:hover { text-decoration:none; border-color:var(--accent-dim); }
  td.dl a.ghost { background:transparent; color:var(--muted); font-weight:400; }

  .caveat { margin-top:22px; padding:18px 20px; border-radius:var(--radius);
    background:var(--surface); border:1px solid var(--track);
    border-inline-start:3px solid var(--warn);
    color:var(--muted); font-size:.94rem; }
  .caveat b { color:var(--text); }

  .links { display:grid; gap:14px; grid-template-columns:repeat(auto-fit,minmax(250px,1fr)); margin-top:30px; }
  .link { display:block; background:var(--surface); border:1px solid var(--track);
    border-radius:var(--radius); padding:22px 24px; color:inherit; }
  .link:hover { text-decoration:none; border-color:var(--accent-dim); }
  .link b { display:block; font-size:1.04rem; color:var(--accent); }
  .link span { display:block; margin-top:7px; color:var(--muted); font-size:.93rem; }

  footer { border-top:1px solid var(--track); padding:44px 0 60px; color:var(--muted); font-size:.92rem; }
  footer p + p { margin-top:10px; }
  code { background:var(--surface-high); padding:2px 7px; border-radius:6px;
    font-family:ui-monospace,"Cascadia Code",Consolas,monospace; font-size:.88em;
    direction:ltr; display:inline-block; }
"""


def build(items: list[tuple[str, str]]) -> str:
    icon = icon_data_uri()
    newest = items[0][0]
    return f"""<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>GT-SPEEDOMETER — عدّاد سرعةٍ فوق كاميرا حيّة</title>
<meta name="description" content="عدّاد سرعةٍ ومسافةٍ ومسارٍ لأندرويد، يعرض عناصره فوق كاميرا حيّة ويسجّل الرحلة فيديو ومسارًا. حرّ بالكامل تحت GPLv3، بلا خدمات جوجل وبلا تتبّع.">
<meta name="theme-color" content="#070B0E">
<meta property="og:type" content="website">
<meta property="og:title" content="GT-SPEEDOMETER">
<meta property="og:description" content="عدّاد سرعةٍ ومسافةٍ ومسارٍ فوق كاميرا حيّة، يسجّل الرحلة فيديو ومسارًا. حرّ تحت GPLv3.">
<meta property="og:locale" content="ar_AR">
<link rel="icon" href="{icon}">
<style>{CSS}</style>
</head>
<body>

<header>
  <div class="wrap bar">
    <img src="{icon}" alt="أيقونة GT-SPEEDOMETER">
    <b>GT-SPEEDOMETER</b>
    <nav>
      <a href="#features">الخصائص</a>
      <a href="#download">التنزيل</a>
      <a href="#honesty">حدودٌ نعلنها</a>
      <a href="#links">روابط</a>
    </nav>
  </div>
</header>

<div class="hero">
  <div class="wrap hero-in">
    <img src="{icon}" alt="أيقونة GT-SPEEDOMETER" width="184" height="184">
    <div class="hero-text">
      <h1>عدّادك فوق الطريق،<br>لا بجانبه</h1>
      <p class="lede">
        عدّاد سرعةٍ ومسافةٍ ومسارٍ لأندرويد يعرض عناصره <strong>فوق كاميرا حيّة</strong>،
        ويسجّل رحلتك <strong>فيديو ومسارًا في آنٍ واحد</strong> لتراجعها بعد النزول.
        حرٌّ بالكامل، بلا خدمات جوجل وبلا تتبّعٍ وبلا إعلان.
      </p>
      <div class="pills">
        <span class="pill on">GPLv3</span>
        <span class="pill">أندرويد 8 فما فوق</span>
        <span class="pill">Kotlin · Jetpack Compose</span>
        <span class="pill">عربيّة كاملة</span>
        <span class="pill">صالح لـ F-Droid</span>
      </div>
      <div class="cta">
        <a class="btn" href="{REPO}/releases/latest">نزّل أحدث نسخة</a>
        <a class="btn alt" href="{REPO}">المستودع على GitHub</a>
      </div>
      <p class="beta">كلّ الإصدارات تجريبيّة (beta) حتّى إشعارٍ آخر.</p>
    </div>
  </div>
</div>

<section id="why">
  <div class="wrap">
    <h2 class="h2">ما الذي يميّزه</h2>
    <p class="sub">
      أغلب عدّادات السرعة تعطيك رقمًا وخريطة. هذا يضيف إليها الكاميرا، ويُبنى
      لمن يقيس فعلًا: للدرّاجة أوّلًا، وللسيّارة ولمن يمشي.
    </p>
    <div class="why">
{trio(WHY)}
    </div>
  </div>
</section>

<section id="features">
  <div class="wrap">
    <h2 class="h2">الخصائص</h2>
    <p class="sub">كلّ ما في التطبيق حتّى الإصدار {newest}.</p>
{feature_html()}
  </div>
</section>

<section id="download">
  <div class="wrap">
    <h2 class="h2">التنزيل</h2>
    <p class="sub">
      لكلّ إصدارٍ حزمتان: <code>release</code> موقّعةٌ بمفتاح المشروع وهي التي
      تُثبَّت عادةً، و<code>debug</code> للتشخيص. ومع كلّ حزمةٍ ملفّ
      <code>.sha256</code> للتحقّق من سلامتها.
    </p>
    <div class="tablewrap">
      <table>
        <thead>
          <tr><th scope="col">الإصدار</th><th scope="col">أبرز ما فيه</th><th scope="col">الحزم</th></tr>
        </thead>
        <tbody>
{release_rows(items)}
        </tbody>
      </table>
    </div>
    <div class="caveat">
      <b>عند أوّل تثبيت:</b> فعّل «تثبيت من مصادر غير معروفة» لمدير الملفّات أو
      المتصفّح. وإن كنتَ مثبِّتًا حزمة <code>debug</code> سابقًا فأزِلها قبل تثبيت
      <code>release</code> — توقيعهما مختلف فلا يجري التحديث فوقها.
    </div>
  </div>
</section>

<section id="honesty">
  <div class="wrap">
    <h2 class="h2">حدودٌ نعلنها</h2>
    <p class="sub">
      ما يلي ليس عيوبًا تُخفى بل قيودٌ حقيقيّة، ومعرفتُها جزءٌ من استعمال أداة قياس.
    </p>
    <div class="why">
{trio(LIMITS)}
    </div>
  </div>
</section>

<section id="links">
  <div class="wrap">
    <h2 class="h2">روابط</h2>
    <div class="links">
{link_cards()}
    </div>
  </div>
</section>

<footer>
  <div class="wrap">
    <p><strong>GT-SPEEDOMETER</strong> — تطوير <a href="{DEVELOPER}">SalehGNUTUX</a>.</p>
    <p>
      رخصة غنو العموميّة الإصدار الثالث (GPLv3): لك أن تشغّله وتدرسه وتعدّله
      وتوزّعه، على أن تبقى النسخ المعدَّلة تحت الرخصة نفسها. يُوزَّع على أمل أن يكون
      نافعًا، <b>بلا أيّ ضمان</b>.
    </p>
    <p>بيانات الخرائط © مساهمو OpenStreetMap.</p>
    <p>Copyright © 2026 SalehGNUTUX</p>
  </div>
</footer>

</body>
</html>
"""


def main() -> None:
    if not Path("app/build.gradle.kts").exists():
        sys.exit("شغّله من جذر المشروع (لم أجد app/build.gradle.kts).")

    items = releases()
    if not items:
        sys.exit("لا إصدارَ واحدًا: CHANGELOG.md فارغٌ أو غير مفهوم، والقائمة "
                 "الاحتياطيّة فارغة أيضًا.")

    gradle = gradle_version()
    newest = items[0][0]
    if gradle and gradle != newest:
        # تنبيهٌ لا توقّف: قد تُبنى الصفحة قبل كتابة قسم الإصدار في السجلّ
        print(f"! أحدث ما في CHANGELOG.md هو {newest} بينما build.gradle.kts يقول "
              f"{gradle}. اكتب قسم «## v{gradle}» في السجلّ ثمّ أعِد التوليد.",
              file=sys.stderr)

    out = Path("index.html")
    out.write_text(build(items), encoding="utf-8")
    kb = out.stat().st_size / 1024
    cards = sum(len(cards) for _, _, cards in FEATURES)
    print(f"✓ {out} — {kb:.0f} ك.ب · {len(items)} إصدارًا · {cards} خاصّية")


if __name__ == "__main__":
    main()
