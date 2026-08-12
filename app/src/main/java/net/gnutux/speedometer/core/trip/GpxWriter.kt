package net.gnutux.speedometer.core.trip

import net.gnutux.speedometer.core.location.SpeedSample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * يكتب المسار بصيغة GPX 1.1 القياسية.
 *
 * اخترنا GPX صيغةً أصليّة لا مصدَّرة: بيانات المستخدم تبقى مقروءة في OsmAnd
 * وOpenTracks وJOSM وغيرها دون أن نكتب مصدّرًا لكلٍّ منها.
 *
 * كل الأرقام تُنسَّق بـ [Locale.US] عمدًا: لو نُسّقت بلغة الجهاز العربية لخرجت
 * بفاصلة عشرية أو بأرقام هندية، ولصار الملف غير صالح لأي قارئ GPX.
 */
object GpxWriter {

    private const val NS_GPX = "http://www.topografix.com/GPX/1/1"
    private const val NS_TPX = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
    private const val NS_GT = "https://github.com/SalehGNUTUX/GT-SPEEDOMETER/1"

    private fun isoFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /**
     * @param videoOffsetMs إزاحة بداية الفيديو عن بداية المسار بالملّي ثانية.
     *        سالبة تعني أن الفيديو بدأ قبل أول عيّنة موقع. تُحفظ في البيانات الوصفية
     *        كي تُعاد محاذاة الطبقة على الفيديو لاحقًا دون تخمين.
     */
    fun write(
        file: File,
        name: String,
        points: List<SpeedSample>,
        trackStartUtcMillis: Long,
        trackStartNanos: Long?,
        videoFileName: String? = null,
        videoOffsetMs: Long? = null,
    ) {
        val iso = isoFormat()
        val sb = StringBuilder(points.size * 160 + 1024)

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"GT-SPEEDOMETER\"")
            .append(" xmlns=\"").append(NS_GPX).append('"')
            .append(" xmlns:gpxtpx=\"").append(NS_TPX).append('"')
            .append(" xmlns:gtspeedo=\"").append(NS_GT).append("\">\n")

        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escape(name)).append("</name>\n")
        sb.append("    <time>").append(iso.format(Date(trackStartUtcMillis))).append("</time>\n")
        if (videoFileName != null && videoOffsetMs != null) {
            sb.append("    <extensions>\n")
            sb.append("      <gtspeedo:video>").append(escape(videoFileName)).append("</gtspeedo:video>\n")
            sb.append("      <gtspeedo:videoOffsetMs>").append(videoOffsetMs).append("</gtspeedo:videoOffsetMs>\n")
            sb.append("    </extensions>\n")
        }
        sb.append("  </metadata>\n")

        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")

        val startNanos = trackStartNanos ?: points.firstOrNull()?.elapsedRealtimeNanos
        for (p in points) {
            // زمن كل نقطة يُشتقّ من فرق elapsedRealtime لا من utcMillis لكل عيّنة،
            // كي يبقى تسلسل الزمن سليمًا حتى لو قفزت ساعة الجهاز أثناء الرحلة
            val offsetMs = if (startNanos != null) (p.elapsedRealtimeNanos - startNanos) / 1_000_000 else 0L
            sb.append("      <trkpt lat=\"")
                .append(fmt(p.latitude, 7)).append("\" lon=\"").append(fmt(p.longitude, 7)).append("\">\n")
            sb.append("        <ele>").append(fmt(p.altitudeM, 2)).append("</ele>\n")
            sb.append("        <time>").append(iso.format(Date(trackStartUtcMillis + offsetMs))).append("</time>\n")
            sb.append("        <hdop>").append(fmt(p.accuracyM.toDouble(), 1)).append("</hdop>\n")
            sb.append("        <extensions>\n")
            sb.append("          <gpxtpx:TrackPointExtension>\n")
            sb.append("            <gpxtpx:speed>").append(fmt(p.speedMps.toDouble(), 3)).append("</gpxtpx:speed>\n")
            sb.append("          </gpxtpx:TrackPointExtension>\n")
            sb.append("          <gtspeedo:src>").append(if (p.speedFromChip) "chip" else "derived").append("</gtspeedo:src>\n")
            sb.append("          <gtspeedo:provider>").append(p.provider).append("</gtspeedo:provider>\n")
            sb.append("        </extensions>\n")
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")

        file.parentFile?.mkdirs()
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun fmt(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
