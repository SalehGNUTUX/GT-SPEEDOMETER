package net.gnutux.speedometer.core.map.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import net.gnutux.speedometer.core.map.mvt.MvtTile

/**
 * يرسم بلاطةً نقطيّةً من بلاطةٍ متجهيّةٍ بمخطّط shortbread.
 *
 * ## لماذا نرسم بأنفسنا
 * لأنّ الأرشيفات النقطيّة الحرّة لبلدٍ كامل **لا وجود لها**: سياسة بلاطات OSM تمنع
 * الجلب بالجملة، وGeofabrik وBBBike كلاهما متجهيّ، والباقي يُباع. فإمّا محرّكٌ متجهيٌّ
 * كامل بواحدٍ وعشرين ميغابايت من المكتبات الأصليّة، وإمّا هذه: مئتا سطرٍ من `Canvas`
 * تُحوّل المتجهيّ إلى صورةٍ يفهمها المحرّك النقطيّ القائم.
 *
 * ## والعربيّة تُرسم هنا بلا خطوطٍ مولَّدة
 * `Canvas.drawText` يمرّ على مُشكِّل أندرويد نفسه: الحروف تتّصل وتُقلب من تلقائها.
 * والمحرّك المتجهيّ كان يحتاج ‎808‎ ك.ب من صور الحروف المولَّدة بـ`fontnik` — بنطاق
 * أشكال الاتّصال كلِّه — وإلّا انفصلت الحروف. فهذا المسار أصدق للعربيّة وأرخص.
 *
 * ## ما يُرسم وما لا يُرسم
 * الطرق والماء وغطاء الأرض والمباني والحدود، وأسماءُ الأماكن والشوارع. ولا تُرسم
 * الرموز (مسجدٌ، وقودٌ، مدرسة): تحتاج مجموعة أيقوناتٍ كاملة، وهي زينةٌ لا قياس.
 *
 * ## الأسماء تُقصّ عند حدّ البلاطة
 * اسمٌ يقع على الحدّ يُرسم مرّتين — مرّةً في كلّ بلاطة — وقد يُقصّ. وهذا ثمن الرسم
 * بلاطةً بلاطةً بلا سياقٍ بينها، وتفاديه يحتاج فهرسًا عامًّا للأسماء. مذكورٌ لا مخفيّ.
 */
class ShortbreadPainter(private val density: Float) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        isSubpixelText = true
    }
    private val path = Path()

    /**
     * يرسم البلاطة كاملةً ويردّ الصورة.
     *
     * [size] ضلعُ البلاطة بالبكسل، و[zoom] لازمٌ لا زينة: عرضُ الطريق واختيارُ ما
     * يُرسم يتبعان التكبير، وبلاطةُ ‎z8‎ لا تُرسم بعرض بلاطة ‎z16‎.
     */
    fun paint(tileBytes: ByteArray, zoom: Int, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Palette.LAND)

        val layers = MvtTile.decode(tileBytes, WANTED_LAYERS)
        val extent = layers.values.firstOrNull()?.extent ?: 4096
        val scale = size.toFloat() / extent

        drawFills(canvas, layers["ocean"], scale) { Palette.OCEAN }
        drawFills(canvas, layers["land"], scale) { Palette.landcover(it) }
        drawFills(canvas, layers["water_polygons"], scale) { Palette.WATER }
        drawLines(canvas, layers["water_lines"], scale, Palette.WATER_LINE, widthOf(zoom, 0.5f))

        if (zoom >= BUILDINGS_FROM) {
            drawFills(canvas, layers["buildings"], scale) { Palette.BUILDING }
        }

        drawBoundaries(canvas, layers["boundaries"], scale)
        drawStreets(canvas, layers["streets"], scale, zoom)
        drawStreetLabels(canvas, layers["street_labels"], scale, zoom)
        drawPlaceLabels(canvas, layers["place_labels"], scale, zoom)

        return bitmap
    }

    // ————————————————————————————— المضلَّعات —————————————————————————————

    private inline fun drawFills(
        canvas: Canvas,
        layer: MvtTile.Layer?,
        scale: Float,
        colourOf: (String?) -> Int,
    ) {
        if (layer == null) return
        for (feature in layer.features) {
            if (feature.type != MvtTile.POLYGON) continue
            fill.color = colourOf(feature.properties["kind"])
            buildPath(feature.rings, scale, close = true)
            canvas.drawPath(path, fill)
        }
    }

    private fun drawLines(
        canvas: Canvas,
        layer: MvtTile.Layer?,
        scale: Float,
        colour: Int,
        width: Float,
    ) {
        if (layer == null) return
        stroke.color = colour
        stroke.strokeWidth = width
        stroke.pathEffect = null
        for (feature in layer.features) {
            if (feature.type == MvtTile.POINT) continue
            buildPath(feature.rings, scale, close = false)
            canvas.drawPath(path, stroke)
        }
    }

    /** الحدود الإداريّة متقطّعة، والبحريّةُ منها تُترك: تلفّ الساحل فتُسيّج البحر */
    private fun drawBoundaries(canvas: Canvas, layer: MvtTile.Layer?, scale: Float) {
        if (layer == null) return
        stroke.color = Palette.BOUNDARY
        stroke.strokeWidth = 1f * density
        stroke.pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
        for (feature in layer.features) {
            if (feature.properties["maritime"] == "true") continue
            val level = feature.properties["admin_level"]?.toFloatOrNull() ?: continue
            if (level > 4f) continue
            buildPath(feature.rings, scale, close = false)
            canvas.drawPath(path, stroke)
        }
        stroke.pathEffect = null
    }

    // ————————————————————————————— الطرق —————————————————————————————

    /**
     * الطرق **مرتّبةً من الأدنى إلى الأعلى**: الطريق السيّار يُرسم آخرًا فيعلو ما
     * يعبره. وترتيبُها في البيانات ترتيبُ ورودها لا ترتيبُ أهمّيّتها، فمن رسمها كما
     * وردت خرجت له أزقّةٌ تقطع الطرق السيّارة.
     *
     * والمرور واحدٌ لا أحدَ عشرَ مرورًا: تُبوَّب المعالم بنوعها مرّةً، وبلاطةٌ حضريّةٌ
     * فيها ألفٌ وخمسمئة طريق.
     */
    private fun drawStreets(canvas: Canvas, layer: MvtTile.Layer?, scale: Float, zoom: Int) {
        if (layer == null) return
        val buckets = HashMap<String, MutableList<MvtTile.Feature>>()
        for (feature in layer.features) {
            if (feature.type == MvtTile.POINT) continue
            val kind = feature.properties["kind"] ?: continue
            buckets.getOrPut(kind) { ArrayList() }.add(feature)
        }

        // السكك أوّلًا وتحت الجميع، وهي متقطّعةٌ كي لا تُقرأ طريقًا
        stroke.color = Palette.RAIL
        stroke.strokeWidth = 1.2f * density
        stroke.pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
        for (kind in RAIL_KINDS) {
            for (feature in buckets[kind].orEmpty()) {
                buildPath(feature.rings, scale, close = false)
                canvas.drawPath(path, stroke)
            }
        }
        stroke.pathEffect = null

        for (road in ROAD_ORDER) {
            if (zoom < road.fromZoom) continue
            val features = buckets[road.kind].orEmpty()
            if (features.isEmpty()) continue
            stroke.color = road.colour
            stroke.strokeWidth = widthOf(zoom, road.width)
            for (feature in features) {
                buildPath(feature.rings, scale, close = false)
                canvas.drawPath(path, stroke)
            }
        }
    }

    /**
     * عرض الطريق بالتكبير: يتضاعف كلَّ مستويين تقريبًا.
     *
     * وله حدٌّ أدنى بكسلٌ واحد: خطٌّ بجزءٍ من بكسل يختفي عند التنعيم، فتصير الخريطة
     * فارغةً عند التكبير الواسع — وهي علّةٌ مرّت بنا في النمط المتجهيّ.
     */
    private fun widthOf(zoom: Int, base: Float): Float {
        val grow = Math.pow(2.0, (zoom - REFERENCE_ZOOM) * 0.5).toFloat()
        return (base * grow * density).coerceAtLeast(1f)
    }

    // ————————————————————————————— الأسماء —————————————————————————————

    /**
     * أسماء الشوارع على منحنياتها.
     *
     * `drawTextOnPath` تضع النصّ على المسار وتديره معه، **وتمرّ على مُشكِّل أندرويد**
     * فتتّصل العربيّة من نفسها. وهي ثمن هذا الطريق كلِّه: الحروف موصولةٌ بلا صورةِ
     * حرفٍ واحدةٍ في الحزمة.
     */
    private fun drawStreetLabels(
        canvas: Canvas,
        layer: MvtTile.Layer?,
        scale: Float,
        zoom: Int,
    ) {
        if (layer == null || zoom < STREET_LABELS_FROM) return
        prepareText(11f * density)
        for (feature in layer.features) {
            val name = nameOf(feature) ?: continue
            val ring = feature.rings.firstOrNull() ?: continue
            if (ring.size < 4) continue
            buildSinglePath(ring, scale)
            // اسمٌ أطول من مساره يخرج عن طرفيه ويُقرأ حرفًا حرفًا
            val length = pathLength(ring, scale)
            if (text.measureText(name) > length * 0.9f) continue
            canvas.drawTextOnPath(name, path, 0f, -3f * density, halo)
            canvas.drawTextOnPath(name, path, 0f, -3f * density, text)
        }
    }

    /** أسماء الأماكن نقطًا: مقاسُها يتبع رتبتها فتُقرأ العاصمةُ قبل الحيّ */
    private fun drawPlaceLabels(
        canvas: Canvas,
        layer: MvtTile.Layer?,
        scale: Float,
        zoom: Int,
    ) {
        if (layer == null) return
        for (feature in layer.features) {
            if (feature.type != MvtTile.POINT) continue
            val name = nameOf(feature) ?: continue
            val ring = feature.rings.firstOrNull() ?: continue
            if (ring.size < 2) continue
            val kind = feature.properties["kind"].orEmpty()
            val size = placeTextSize(kind, zoom) ?: continue
            prepareText(size * density)
            val x = ring[0] * scale
            val y = ring[1] * scale
            canvas.drawText(name, x, y, halo)
            canvas.drawText(name, x, y, text)
        }
    }

    private fun placeTextSize(kind: String, zoom: Int): Float? = when (kind) {
        "country" -> if (zoom in 3..7) 15f else null
        "state", "state_capital" -> if (zoom in 5..10) 13f else null
        "capital", "city" -> if (zoom >= 6) 14f else null
        "town" -> if (zoom >= 9) 12f else null
        "village" -> if (zoom >= 11) 11f else null
        "suburb", "quarter" -> if (zoom >= 13) 11f else null
        "neighbourhood", "hamlet" -> if (zoom >= 14) 10f else null
        else -> if (zoom >= 15) 10f else null
    }

    private fun prepareText(size: Float) {
        text.textSize = size
        text.color = Palette.TEXT
        halo.textSize = size
        halo.color = Palette.TEXT_HALO
        halo.strokeWidth = 3f * density
    }

    /** الاسم المحلّيّ أوّلًا ثمّ الإنجليزيّ: من في المغرب يقرأ «الرباط» لا «Rabat» */
    private fun nameOf(feature: MvtTile.Feature): String? =
        (feature.properties["name"] ?: feature.properties["name_en"])
            ?.takeIf { it.isNotBlank() && it.length <= MAX_LABEL_CHARS }

    // ————————————————————————————— المسارات —————————————————————————————

    private fun buildPath(rings: List<IntArray>, scale: Float, close: Boolean) {
        path.rewind()
        for (ring in rings) {
            if (ring.size < 4) continue
            path.moveTo(ring[0] * scale, ring[1] * scale)
            var i = 2
            while (i + 1 < ring.size) {
                path.lineTo(ring[i] * scale, ring[i + 1] * scale)
                i += 2
            }
            if (close) path.close()
        }
    }

    private fun buildSinglePath(ring: IntArray, scale: Float) {
        path.rewind()
        path.moveTo(ring[0] * scale, ring[1] * scale)
        var i = 2
        while (i + 1 < ring.size) {
            path.lineTo(ring[i] * scale, ring[i + 1] * scale)
            i += 2
        }
    }

    private fun pathLength(ring: IntArray, scale: Float): Float {
        var total = 0f
        var i = 0
        while (i + 3 < ring.size) {
            val dx = (ring[i + 2] - ring[i]) * scale
            val dy = (ring[i + 3] - ring[i + 1]) * scale
            total += kotlin.math.hypot(dx, dy)
            i += 2
        }
        return total
    }

    /** نوعُ طريقٍ ولونُه وعرضُه ومن أيّ تكبيرٍ يُرسم */
    private class Road(
        val kind: String,
        val colour: Int,
        val width: Float,
        val fromZoom: Int,
    )

    companion object {
        /** ما نفكّه من البلاطة؛ وما عداه من الستّ والعشرين طبقةً لا يُرسم فلا يُفكّ */
        private val WANTED_LAYERS = setOf(
            "ocean", "land", "water_polygons", "water_lines", "buildings",
            "boundaries", "streets", "street_labels", "place_labels",
        )

        /** السكك تسكن طبقة `streets` بـ‎kind‎ خاصّ، فتُفصل وإلّا رُسمت طرقًا */
        private val RAIL_KINDS = listOf(
            "rail", "tram", "light_rail", "subway", "narrow_gauge", "funicular",
        )

        /**
         * الترتيب من الأدنى إلى الأعلى، والقيم مقيسةٌ على ‎z14‎.
         *
         * ولا `_link` في هذه القيم: الوصلة صفةٌ منطقيّة مستقلّة في shortbread، وقيمُ
         * `kind` مفردة. (قِيست من أرشيفٍ حقيقيّ، وكان النمط المتجهيّ يرشّح
         * `"primary_link"` فلا يُطابق شيئًا قطّ.)
         */
        private val ROAD_ORDER = listOf(
            Road("track", Palette.ROAD_TRACK, 0.8f, 14),
            Road("path", Palette.ROAD_MINOR, 0.7f, 15),
            Road("footway", Palette.ROAD_MINOR, 0.7f, 15),
            Road("steps", Palette.ROAD_MINOR, 0.7f, 16),
            Road("cycleway", Palette.ROAD_MINOR, 0.8f, 15),
            Road("service", Palette.ROAD_SERVICE, 0.9f, 14),
            Road("pedestrian", Palette.ROAD_MINOR, 1.0f, 14),
            Road("living_street", Palette.ROAD_MINOR, 1.0f, 14),
            Road("residential", Palette.ROAD_RESIDENTIAL, 1.6f, 12),
            Road("unclassified", Palette.ROAD_RESIDENTIAL, 1.6f, 12),
            Road("tertiary", Palette.ROAD_TERTIARY, 2.0f, 10),
            Road("secondary", Palette.ROAD_SECONDARY, 2.6f, 8),
            Road("primary", Palette.ROAD_PRIMARY, 3.2f, 6),
            Road("trunk", Palette.ROAD_TRUNK, 3.6f, 5),
            Road("motorway", Palette.ROAD_MOTORWAY, 4.2f, 4),
        )

        private const val REFERENCE_ZOOM = 14
        private const val BUILDINGS_FROM = 15
        private const val STREET_LABELS_FROM = 14
        private const val MAX_LABEL_CHARS = 40
    }
}

/**
 * ألوان الخريطة — **فاتحة**.
 *
 * التطبيق يقلب ألوان البلاطات افتراضيًّا لأنّ أرشيفات OSM المعتادة فاتحة، فلوحةٌ
 * داكنةٌ هنا تخرج بيضاءَ عند من لم يبدّل إعدادًا. والقلبُ يتكفّل بالسمة الداكنة.
 */
private object Palette {
    const val LAND = 0xFFF2EFE9.toInt()
    const val OCEAN = 0xFF9AC8DB.toInt()
    const val WATER = 0xFFAAD3DF.toInt()
    const val WATER_LINE = 0xFFAAD3DF.toInt()
    const val BUILDING = 0xFFD9D0C9.toInt()
    const val BOUNDARY = 0xFFA88AA8.toInt()
    const val RAIL = 0xFFAAAAAA.toInt()

    const val ROAD_MOTORWAY = 0xFFE892A2.toInt()
    const val ROAD_TRUNK = 0xFFF9B29C.toInt()
    const val ROAD_PRIMARY = 0xFFFCD6A4.toInt()
    const val ROAD_SECONDARY = 0xFFF7FABF.toInt()
    const val ROAD_TERTIARY = 0xFFFFFFFF.toInt()
    const val ROAD_RESIDENTIAL = 0xFFFFFFFF.toInt()
    const val ROAD_SERVICE = 0xFFFFFFFF.toInt()
    const val ROAD_MINOR = 0xFFEDEDED.toInt()
    const val ROAD_TRACK = 0xFFC1A782.toInt()

    const val TEXT = 0xFF33333A.toInt()
    const val TEXT_HALO = Color.WHITE

    /**
     * غطاء الأرض بنوعه.
     *
     * و`land` في shortbread **غطاءُ أرضٍ لا يابسة**: بُقَعُ غابةٍ وحقلٍ وحيٍّ متفرّقة،
     * لا مضلَّعٌ يملأ البرّ. فاليابسة لونُ الخلفيّة، والبحرُ يُرسم فوقها. (قِيست من
     * الأرشيف نفسه بعد أن أخرج النمطُ المعاكس خريطةً سوداءَ بكاملها.)
     */
    fun landcover(kind: String?): Int = when (kind) {
        "forest", "wood" -> 0xFFC8DDB8.toInt()
        "park", "garden", "grass", "grassland", "meadow", "village_green",
        "playground", "pitch", "golf_course", "allotments" -> 0xFFCDEBB0.toInt()
        "farmland", "farmyard", "orchard", "vineyard" -> 0xFFEEF0D5.toInt()
        "scrub", "heath" -> 0xFFC9D8B5.toInt()
        "bare_rock", "scree" -> 0xFFDEDBD5.toInt()
        "sand", "beach" -> 0xFFFFF1BA.toInt()
        "residential" -> 0xFFE0DFDF.toInt()
        "commercial", "retail" -> 0xFFFEE4E2.toInt()
        "industrial", "brownfield", "greenfield", "quarry", "railway" -> 0xFFE6DEE5.toInt()
        "cemetery" -> 0xFFAACBAF.toInt()
        "hospital", "university", "college", "school", "military" -> 0xFFFFFFE5.toInt()
        else -> 0xFFE8E6E0.toInt()
    }
}
