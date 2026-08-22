package net.gnutux.speedometer.core.map.mvt

/**
 * فاكُّ المربّعات المتجهيّة (Mapbox Vector Tile) — قارئُ بروتوبَف بأقلّ ما يكفي.
 *
 * ## لماذا لا مكتبة
 * الصيغة رسالةُ بروتوبَف واحدةٌ بستّة حقولٍ لا غير، وقراءتها مئتا سطر. وإدخالُ
 * `protobuf-javalite` لأجلها يجرّ مولّدًا ومكتبةً وقاعدةَ بناءٍ كاملة على مشروعٍ
 * قاعدتُه أنّ الحزمة تبقى خفيفة. فالبايتات تُقرأ هنا مباشرةً.
 *
 * ## ما يُقرأ وما يُترك
 * تُقرأ الأسماء والهندسة والخصائص، ولا يُقرأ المعرّف: لا نستعمله. والهندسة تبقى
 * **بإحداثيّات البلاطة** (‎0..extent‎) لا بالدرجات — التحويل إلى بكسل عملُ الراسم،
 * وهو ضربٌ واحد.
 */
object MvtTile {

    /** نوعُ المعلَم كما تُعرّفه الصيغة */
    const val POINT = 1
    const val LINESTRING = 2
    const val POLYGON = 3

    /**
     * معلَمٌ واحد: نوعُه، وحلقاتُه، وخصائصُه.
     *
     * [rings] قائمةُ متتابعاتٍ من الإحداثيّات مسطَّحةً (`x0, y0, x1, y1, …`): مصفوفةُ
     * أعدادٍ صحيحةٍ واحدةٌ لكلّ حلقة، لا قائمةَ كائناتِ نقاطٍ. وبلاطةٌ حضريّةٌ فيها
     * ثلاثة آلاف مبنًى تعني عشراتِ آلاف النقاط، وتخصيصُ كائنٍ لكلّ واحدةٍ يُغرق جامعَ
     * المهملات في خيط رسم البلاطات.
     */
    class Feature(
        val type: Int,
        val rings: List<IntArray>,
        val properties: Map<String, String>,
    )

    /** طبقةٌ باسمها ومعالمها؛ [extent] مقياسُ إحداثيّاتها وهو ‎4096‎ عادةً */
    class Layer(
        val name: String,
        val extent: Int,
        val features: List<Feature>,
    )

    /**
     * يفكّ البلاطة إلى طبقاتٍ باسمها.
     *
     * و[wanted] ترشيحٌ مبكّر: البلاطة الحضريّة فيها ستٌّ وعشرون طبقةً ونحن نرسم
     * تسعًا، وفكُّ ما لا يُرسم عملٌ يقع في خيط البلاطات لا في فراغ.
     */
    fun decode(bytes: ByteArray, wanted: Set<String>? = null): Map<String, Layer> {
        val reader = Reader(bytes)
        val layers = LinkedHashMap<String, Layer>()
        while (reader.hasMore()) {
            val tag = reader.tag()
            if (tag.field == FIELD_TILE_LAYERS && tag.wire == WIRE_BYTES) {
                val slice = reader.bytes()
                val name = peekLayerName(slice)
                if (wanted == null || name in wanted) {
                    decodeLayer(slice, name)?.let { layers[it.name] = it }
                }
            } else {
                reader.skip(tag.wire)
            }
        }
        return layers
    }

    /**
     * اسمُ الطبقة وحده قبل فكّها كلِّها.
     *
     * الاسم هو الحقل الأوّل في الرسالة، فيُقرأ ثمّ يُتوقّف — وبه نعرف أنُفكّ الطبقة
     * أم نتخطّاها، قبل أن ندفع ثمن فكّ آلاف المعالم فيها.
     */
    private fun peekLayerName(bytes: ByteArray): String {
        val reader = Reader(bytes)
        while (reader.hasMore()) {
            val tag = reader.tag()
            if (tag.field == FIELD_LAYER_NAME && tag.wire == WIRE_BYTES) {
                return reader.string()
            }
            reader.skip(tag.wire)
        }
        return ""
    }

    private fun decodeLayer(bytes: ByteArray, name: String): Layer? {
        val reader = Reader(bytes)
        var extent = DEFAULT_EXTENT
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        val rawFeatures = ArrayList<ByteArray>()

        while (reader.hasMore()) {
            val tag = reader.tag()
            when {
                tag.field == FIELD_LAYER_FEATURES && tag.wire == WIRE_BYTES ->
                    rawFeatures.add(reader.bytes())

                tag.field == FIELD_LAYER_KEYS && tag.wire == WIRE_BYTES ->
                    keys.add(reader.string())

                tag.field == FIELD_LAYER_VALUES && tag.wire == WIRE_BYTES ->
                    values.add(decodeValue(reader.bytes()))

                tag.field == FIELD_LAYER_EXTENT && tag.wire == WIRE_VARINT ->
                    extent = reader.varint().toInt()

                else -> reader.skip(tag.wire)
            }
        }
        if (extent <= 0) return null

        val features = ArrayList<Feature>(rawFeatures.size)
        for (raw in rawFeatures) {
            decodeFeature(raw, keys, values)?.let(features::add)
        }
        return Layer(name, extent, features)
    }

    private fun decodeFeature(
        bytes: ByteArray,
        keys: List<String>,
        values: List<String>,
    ): Feature? {
        val reader = Reader(bytes)
        var type = 0
        var tags: IntArray? = null
        var geometry: IntArray? = null

        while (reader.hasMore()) {
            val tag = reader.tag()
            when {
                tag.field == FIELD_FEATURE_TYPE && tag.wire == WIRE_VARINT ->
                    type = reader.varint().toInt()

                tag.field == FIELD_FEATURE_TAGS && tag.wire == WIRE_BYTES ->
                    tags = reader.packedVarints()

                tag.field == FIELD_FEATURE_GEOMETRY && tag.wire == WIRE_BYTES ->
                    geometry = reader.packedVarints()

                else -> reader.skip(tag.wire)
            }
        }

        val geom = geometry ?: return null
        if (type != POINT && type != LINESTRING && type != POLYGON) return null

        val properties = if (tags == null || tags.isEmpty()) {
            emptyMap()
        } else {
            HashMap<String, String>(tags.size / 2).apply {
                var i = 0
                while (i + 1 < tags.size) {
                    val key = keys.getOrNull(tags[i])
                    val value = values.getOrNull(tags[i + 1])
                    if (key != null && value != null) put(key, value)
                    i += 2
                }
            }
        }
        return Feature(type, decodeGeometry(geom), properties)
    }

    /**
     * أوامرُ الرسم إلى حلقاتٍ من الإحداثيّات.
     *
     * الهندسة سلسلةُ أوامر: `MoveTo` يفتح حلقةً جديدة، و`LineTo` يمدّها، و`ClosePath`
     * يُغلقها. والمعاملات **فروقٌ** عن النقطة السابقة مرمّزةً بترميز الإشارة المتعرّج
     * (zigzag)، فالقيمة المطلقة تُبنى بالتراكم — ومن قرأها قيمًا مطلقةً خرجت له خطوطٌ
     * تنطلق من زاوية البلاطة إلى كلّ اتّجاه.
     */
    private fun decodeGeometry(data: IntArray): List<IntArray> {
        val rings = ArrayList<IntArray>(4)
        var buffer = IntArray(64)
        var used = 0
        var x = 0
        var y = 0
        var at = 0

        fun flush() {
            if (used >= 4) rings.add(buffer.copyOf(used))
            used = 0
        }

        fun push(px: Int, py: Int) {
            if (used + 2 > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[used++] = px
            buffer[used++] = py
        }

        while (at < data.size) {
            val command = data[at++]
            val id = command and 0x7
            val count = command ushr 3
            when (id) {
                CMD_MOVE_TO -> {
                    for (i in 0 until count) {
                        if (at + 1 >= data.size) break
                        // نقطةٌ منفردة تُغلق ما قبلها وتفتح حلقة؛ ونقاطُ طبقةٍ نقطيّةٍ
                        // كثيرةٌ في أمرٍ واحد فتصير كلُّ واحدةٍ حلقةً بنفسها.
                        flush()
                        x += zigzag(data[at++])
                        y += zigzag(data[at++])
                        push(x, y)
                    }
                }

                CMD_LINE_TO -> {
                    for (i in 0 until count) {
                        if (at + 1 >= data.size) break
                        x += zigzag(data[at++])
                        y += zigzag(data[at++])
                        push(x, y)
                    }
                }

                CMD_CLOSE_PATH -> {
                    // الإغلاق ضمنيٌّ في الرسم: `Path.close` تكفي، ولا تُكرَّر النقطة
                    // الأولى في البيانات كي لا يزيد كلُّ مضلَّعٍ ضلعًا بطول صفر.
                    flush()
                }

                else -> return rings
            }
        }
        flush()
        return rings
    }

    /** قيمةُ خاصّيّةٍ نصًّا مهما كان نوعُها: الراسم يقارن نصوصًا لا أنواعًا */
    private fun decodeValue(bytes: ByteArray): String {
        val reader = Reader(bytes)
        while (reader.hasMore()) {
            val tag = reader.tag()
            when {
                tag.field == VALUE_STRING && tag.wire == WIRE_BYTES -> return reader.string()
                tag.field == VALUE_FLOAT && tag.wire == WIRE_FIXED32 ->
                    return Float.fromBits(reader.fixed32()).toString()
                tag.field == VALUE_DOUBLE && tag.wire == WIRE_FIXED64 ->
                    return Double.fromBits(reader.fixed64()).toString()
                tag.field == VALUE_INT && tag.wire == WIRE_VARINT -> return reader.varint().toString()
                tag.field == VALUE_UINT && tag.wire == WIRE_VARINT -> return reader.varint().toString()
                tag.field == VALUE_SINT && tag.wire == WIRE_VARINT ->
                    return zigzagLong(reader.varint()).toString()
                tag.field == VALUE_BOOL && tag.wire == WIRE_VARINT ->
                    return if (reader.varint() != 0L) "true" else "false"
                else -> reader.skip(tag.wire)
            }
        }
        return ""
    }

    private fun zigzag(value: Int): Int = (value ushr 1) xor -(value and 1)

    private fun zigzagLong(value: Long): Long = (value ushr 1) xor -(value and 1L)

    // ————————————————————————— قارئ البايتات —————————————————————————

    private class Tag(val field: Int, val wire: Int)

    private class Reader(private val bytes: ByteArray) {
        private var at = 0

        fun hasMore(): Boolean = at < bytes.size

        fun tag(): Tag {
            val key = varint().toInt()
            return Tag(key ushr 3, key and 0x7)
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (at < bytes.size) {
                val b = bytes[at++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 63) break
            }
            return result
        }

        fun bytes(): ByteArray {
            val length = varint().toInt()
            if (length < 0 || at + length > bytes.size) {
                at = bytes.size
                return ByteArray(0)
            }
            val slice = bytes.copyOfRange(at, at + length)
            at += length
            return slice
        }

        fun string(): String = String(bytes(), Charsets.UTF_8)

        /** حقلٌ مرصوصٌ من أعدادٍ متغيّرة الطول، يُقرأ إلى مصفوفةٍ واحدة */
        fun packedVarints(): IntArray {
            val length = varint().toInt()
            if (length <= 0 || at + length > bytes.size) {
                at = bytes.size
                return IntArray(0)
            }
            val end = at + length
            var out = IntArray(minOf(length, 1024))
            var used = 0
            while (at < end) {
                var value = 0L
                var shift = 0
                while (at < end) {
                    val b = bytes[at++].toInt() and 0xFF
                    value = value or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                    if (shift > 63) break
                }
                if (used == out.size) out = out.copyOf(out.size * 2)
                out[used++] = value.toInt()
            }
            return out.copyOf(used)
        }

        fun fixed32(): Int {
            if (at + 4 > bytes.size) {
                at = bytes.size
                return 0
            }
            var value = 0
            for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
            at += 4
            return value
        }

        fun fixed64(): Long {
            if (at + 8 > bytes.size) {
                at = bytes.size
                return 0
            }
            var value = 0L
            for (i in 7 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            at += 8
            return value
        }

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> varint()
                WIRE_FIXED64 -> fixed64()
                WIRE_BYTES -> bytes()
                WIRE_FIXED32 -> fixed32()
                else -> at = bytes.size
            }
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_BYTES = 2
    private const val WIRE_FIXED32 = 5

    private const val FIELD_TILE_LAYERS = 3

    private const val FIELD_LAYER_NAME = 1
    private const val FIELD_LAYER_FEATURES = 2
    private const val FIELD_LAYER_KEYS = 3
    private const val FIELD_LAYER_VALUES = 4
    private const val FIELD_LAYER_EXTENT = 5

    private const val FIELD_FEATURE_TAGS = 2
    private const val FIELD_FEATURE_TYPE = 3
    private const val FIELD_FEATURE_GEOMETRY = 4

    private const val VALUE_STRING = 1
    private const val VALUE_FLOAT = 2
    private const val VALUE_DOUBLE = 3
    private const val VALUE_INT = 4
    private const val VALUE_UINT = 5
    private const val VALUE_SINT = 6
    private const val VALUE_BOOL = 7

    private const val CMD_MOVE_TO = 1
    private const val CMD_LINE_TO = 2
    private const val CMD_CLOSE_PATH = 7

    private const val DEFAULT_EXTENT = 4096
}
