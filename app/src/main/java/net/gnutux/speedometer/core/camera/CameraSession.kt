package net.gnutux.speedometer.core.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.gnutux.speedometer.core.media.MediaRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * جلسة الكاميرا. تعيش خارج التركيب (Composition) عمدًا.
 *
 * كانت النسخة الأولى تُنشئ حالات الاستعمال داخل الشاشة وتستدعي unbindAll كلّما
 * عاد المستخدم إلى تبويب الكاميرا — فكان الانتقال بين التبويبات أثناء التسجيل
 * يُجهض التسجيل الجاري. هنا حالات الاستعمال تُنشأ مرّة واحدة، ولا نفكّ الارتباط
 * أبدًا ما دام التسجيل جاريًا.
 */
class CameraSession(
    private val context: Context,
    private val media: MediaRepository,
) {

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    /** آخر رسالة للمستخدم: نجاح الحفظ أو سبب الفشل */
    private val _message = MutableStateFlow<Message?>(null)
    val message = _message.asStateFlow()

    sealed interface Message {
        data class Saved(val name: String) : Message
        data class Failed(val reason: String) : Message
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun bind(owner: LifecycleOwner, previewView: PreviewView, onFailure: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull()
            if (p == null) {
                onFailure()
                return@addListener
            }
            provider = p

            if (preview == null) preview = Preview.Builder().build()
            if (videoCapture == null) {
                videoCapture = VideoCapture.withOutput(
                    Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(
                                Quality.FHD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                            )
                        )
                        .build()
                )
            }
            preview?.setSurfaceProvider(previewView.surfaceProvider)

            val alreadyBound = p.isBound(preview!!) && p.isBound(videoCapture!!)
            if (!alreadyBound) {
                runCatching {
                    if (!_isRecording.value) p.unbindAll()
                    p.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        videoCapture,
                    )
                }.onFailure {
                    onFailure()
                    return@addListener
                }
            }
            _isReady.value = true
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * تُنادى عند مغادرة شاشة الكاميرا. لا تفكّ الارتباط أثناء التسجيل، وإلا ضاع
     * التسجيل الجاري بمجرّد أن يبدّل المستخدم التبويب.
     */
    fun detach() {
        if (_isRecording.value) {
            preview?.setSurfaceProvider(null)
            return
        }
        runCatching { provider?.unbindAll() }
        _isReady.value = false
    }

    /** @param onStarted تُنادى لحظة بدء الترميز فعلًا، لتثبيت مرساة المزامنة */
    @SuppressLint("MissingPermission")
    fun startRecording(onStarted: () -> Unit) {
        if (_isRecording.value) return
        val capture = videoCapture ?: run {
            _message.value = Message.Failed(REASON_NOT_READY)
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "ride-$stamp.mp4"

        var pending = media.prepareRecording(capture.output, name)
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) pending = pending.withAudioEnabled()

        recording = runCatching {
            pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        onStarted()
                        _isRecording.value = true
                    }

                    is VideoRecordEvent.Finalize -> {
                        _isRecording.value = false
                        recording = null
                        // الصمت عند الفشل هو ما جعل المستخدم يظنّ أن شيئًا لم يُسجَّل
                        _message.value = if (event.hasError()) {
                            Message.Failed("${event.error}")
                        } else {
                            Message.Saved(name)
                        }
                    }
                }
            }
        }.getOrElse {
            _message.value = Message.Failed(it.message ?: REASON_NOT_READY)
            null
        }
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    private companion object {
        const val REASON_NOT_READY = "camera-not-ready"
    }
}
