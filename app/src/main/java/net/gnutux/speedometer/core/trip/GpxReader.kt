package net.gnutux.speedometer.core.trip

import android.location.Location
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timeMs: Long,
    val speedMps: Float,
)

/** رحلة محفوظة، مقروءة من ملفّ GPX الذي كتبناه */
class TripTrack(
    val file: File,
    val name: String,
    val startMs: Long,
    val points: List<TrackPoint>,
    /** المدّة كما سجّلها التطبيق لحظة الإنهاء؛ `null` في ملفّات ما قبل 0.4.0 */
    private val recordedDurationMs: Long? = null,
) {
    val distanceM: Double
    val durationMs: Long
    val maxSpeedKmh: Float
    val movingTimeMs: Long

    init {
        var distance = 0.0
        var maxSpeed = 0f
        var moving = 0L
        val out = FloatArray(1)
        for (i in points.indices) {
            maxSpeed = max(maxSpeed, points[i].speedMps)
            if (i == 0) continue
            val a = points[i - 1]
            val b = points[i]
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
            val step = out[0].toDouble()
            val dt = b.timeMs - a.timeMs
            // نفس منطق التسجيل الحيّ: المسافة والزمن يُحسبان أثناء الحركة فقط،
            // وإلا سحبت الوقفات المتوسّط إلى أسفل وضخّم تذبذبُ الوقوف المسافةَ
            if (b.speedMps >= STOP_THRESHOLD_MPS && dt in 1..MAX_GAP_MS) {
                distance += step
                moving += dt
            }
        }
        distanceM = distance
        maxSpeedKmh = maxSpeed * 3.6f
        movingTimeMs = moving
        // المسجَّلة أصدق: فارق زمن النقاط يُسقط ما قبل أوّل تثبيتٍ ويحسب الوقفات،
        // وهو نقيض ما يعدّه العدّاد الحيّ. وتبقى الفروق للملفّات القديمة.
        durationMs = recordedDurationMs
            ?: if (points.size >= 2) points.last().timeMs - points.first().timeMs else 0L
    }

    val distanceKm: Double get() = distanceM / 1000.0
    val avgSpeedKmh: Float
        get() = if (movingTimeMs < 1000L) 0f else (distanceM / (movingTimeMs / 1000.0) * 3.6).toFloat()

    private companion object {
        const val STOP_THRESHOLD_MPS = 0.7f
        const val MAX_GAP_MS = 30_000L
    }
}

/** قارئ GPX خفيف يكفي لملفّاتنا: نقاط المسار وأزمنتها وسرعاتها */
object GpxReader {

    fun read(file: File): TripTrack? = runCatching {
        val points = mutableListOf<TrackPoint>()
        var name = file.nameWithoutExtension
        var nameFromMetadata = false
        var recordedDuration: Long? = null

        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)

            var lat = 0.0
            var lon = 0.0
            var timeMs = 0L
            var speed = 0f
            var inTrkpt = false
            var pendingText: String? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "trkpt" -> {
                            inTrkpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            timeMs = 0L
                            speed = 0f
                        }

                        "time", "speed", "name", "durationMs" -> pendingText = ""
                    }

                    XmlPullParser.TEXT -> if (pendingText != null) pendingText = parser.text

                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "trkpt" -> {
                                points += TrackPoint(lat, lon, timeMs, speed)
                                inTrkpt = false
                            }

                            "time" -> if (inTrkpt) {
                                timeMs = parseIso(pendingText) ?: 0L
                            }

                            "name" -> if (!inTrkpt && !nameFromMetadata) {
                                pendingText?.takeIf { it.isNotBlank() }?.let {
                                    name = it
                                    nameFromMetadata = true
                                }
                            }

                            "durationMs" -> if (!inTrkpt) {
                                recordedDuration = pendingText?.trim()?.toLongOrNull()
                            }

                            else -> if (inTrkpt && parser.name.endsWith("speed")) {
                                speed = pendingText?.toFloatOrNull() ?: 0f
                            }
                        }
                        pendingText = null
                    }
                }
                event = parser.next()
            }
        }

        if (points.isEmpty()) return@runCatching null
        TripTrack(
            file = file,
            name = name,
            startMs = points.first().timeMs.takeIf { it > 0 } ?: file.lastModified(),
            points = points,
            recordedDurationMs = recordedDuration,
        )
    }.getOrNull()

    private fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        for (pattern in PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value.trim())
                    ?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private val PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )
}

/** مكتبة الرحلات: ملفّات GPX في مجلّد التطبيق */
class TripLibrary(private val tracksDir: File) {

    fun list(): List<TripTrack> =
        tracksDir.listFiles { f -> f.isFile && f.extension.equals("gpx", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { GpxReader.read(it) }
            .sortedByDescending { it.startMs }

    fun delete(trip: TripTrack): Boolean = runCatching { trip.file.delete() }.getOrDefault(false)
}
