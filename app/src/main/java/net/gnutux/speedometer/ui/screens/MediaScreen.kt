package net.gnutux.speedometer.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.media.MediaItem
import net.gnutux.speedometer.ui.Fmt
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Bg
import net.gnutux.speedometer.ui.theme.Danger
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextPrimary
import net.gnutux.speedometer.ui.theme.TextSecondary

/**
 * قسم اللقطات والتسجيلات: لمسة تفتح، ولمسة مطوّلة تدخل وضع التحديد المتعدّد.
 *
 * التبويبان يفصلان الصور عن الفيديوهات لأنّ الشبكة الموحّدة كانت تخلط نوعين لا
 * يُبحث عنهما معًا: من يريد لقطةً يمرّ على عشرات المقاطع قبل أن يجدها.
 *
 * الحذف والمشاركة صارا جماعيّين: من صوّر رحلةً واحدة يعود بعشرين لقطة، وحذفها
 * واحدةً واحدة بمربّع تأكيدٍ لكلّ واحدة عملٌ لا يُطاق.
 */
// `SecondaryTabRow` ما زال تجريبيًّا في Material 3 والمصرّف يرفضه بلا إقرارٍ صريح.
// والإقرار مقصورٌ على هذه الدالّة لا على الملفّ كلّه: أيّ واجهةٍ تجريبيّة تُضاف
// لاحقًا يجب أن تُقرّ بنفسها لا أن تمرّ في ظلّ هذه.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items by vm.mediaItems.collectAsStateWithLifecycle()
    // يبقى التبويب المختار عبر تدوير الشاشة: العودة إلى «الصور» بعد كلّ دورة إزعاج
    var tabIndex by rememberSaveable { mutableIntStateOf(TAB_PHOTOS) }

    // وضع التحديد وقائمته ينجوان من التدوير أيضًا، ولا يُحفظ فيهما إلّا **مفاتيح
    // نصّيّة** (عنوان الملفّ): `MediaItem` ليس `Parcelable`، وحفظُ الكائن يعني حفظ
    // نسخةٍ قديمة من قائمةٍ تُعاد قراءتها من مزوّد المحتوى بعد كلّ عودة.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by rememberSaveable(stateSaver = SelectionKeysSaver) {
        mutableStateOf(emptySet<String>())
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshMedia() }

    LaunchedEffect(toast) {
        if (toast == null) return@LaunchedEffect
        delay(3000)
        toast = null
    }

    // التصفية مرهونة بالقائمة وحدها فلا تُعاد مع كلّ تبديل تبويب
    val photos = remember(items) { items.filter { !it.isVideo } }
    val videos = remember(items) { items.filter { it.isVideo } }
    val shown = if (tabIndex == TAB_VIDEOS) videos else photos

    // المحدَّد الفعّال يُشتقّ من المعروض لا من المحفوظ: ملفٌّ حذفه المستعمل من تطبيق
    // المعرض يختفي من `shown` عند أوّل تحديث، ومفتاحه قد يبقى في المجموعة. ولو عددنا
    // المفاتيح لأخبرناه أنّه حدّد خمسةً وهو يرى أربعًا، ولحاولنا حذف ما لا وجود له.
    val chosen = remember(shown, selectedKeys) { shown.filter { it.uri.toString() in selectedKeys } }

    val exitSelection: () -> Unit = {
        selecting = false
        selectedKeys = emptySet()
    }

    // من دخل وضع التحديد بالخطأ يضغط الرجوع أوّلًا، ولا يصحّ أن يخرج من التطبيق
    // حينها. ويُسجَّل هذا الاعتراض بعد اعتراضات `MainActivity` في ترتيب التركيب
    // فيسبقها إلى الحدث، وهو الترتيب نفسه الذي يتّكل عليه أرشيف الرحلات.
    BackHandler(enabled = selecting, onBack = exitSelection)

    // تبديل التبويب يُلغي التحديد.
    //
    // البديل — حفظُ تحديدٍ لكلّ تبويب — يترك في التبويب الغائب عناصرَ محدَّدةً لا
    // يراها المستعمل، ثمّ يُظهر شريطًا واحدًا لا يمكنه أن يعبّر عن عددين ولا أن يحذف
    // من قائمتين بمربّع تأكيدٍ واحد. والإلغاء أرخص: أسوأ ما يقع أن يُعيد تحديد ما في
    // التبويب الآخر، وهو ما كان سيفعله على أيّ حال ليراه.
    val openTab: (Int) -> Unit = { index ->
        if (index != tabIndex) selectedKeys = emptySet()
        tabIndex = index
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            // TabRow يرتّب أبناءه بحسب اتّجاه التخطيط، فالتبويب الأوّل يقع يمينًا في RTL
            // من تلقاء نفسه؛ أيّ عكسٍ يدويّ هنا يكسر الترتيب على الأجهزة اللاتينيّة
            SecondaryTabRow(
                selectedTabIndex = tabIndex,
                containerColor = Surface,
                contentColor = Accent,
            ) {
                Tab(
                    selected = tabIndex == TAB_PHOTOS,
                    onClick = { openTab(TAB_PHOTOS) },
                    text = { Text(stringResource(R.string.media_tab_photos)) },
                    selectedContentColor = Accent,
                    unselectedContentColor = TextSecondary,
                )
                Tab(
                    selected = tabIndex == TAB_VIDEOS,
                    onClick = { openTab(TAB_VIDEOS) },
                    text = { Text(stringResource(R.string.media_tab_videos)) },
                    selectedContentColor = Accent,
                    unselectedContentColor = TextSecondary,
                )
            }

            if (selecting) {
                SelectionBar(
                    count = chosen.size,
                    onSelectAll = { selectedKeys = shown.mapTo(mutableSetOf()) { it.uri.toString() } },
                    onSelectNone = { selectedKeys = emptySet() },
                    onShare = { shareMany(context, chosen) },
                    onDelete = { confirmDelete = true },
                    onExit = exitSelection,
                )
            } else if (shown.isNotEmpty()) {
                // مدخلٌ ثانٍ إلى وضع التحديد بجانب اللمسة المطوّلة: هي إيماءةٌ مخفيّة
                // لا يعرفها إلّا من جرّبها، والزرّ يُعلِّمها.
                EnterSelectionBar(onSelect = { selecting = true })
            }

            if (shown.isEmpty()) {
                MediaEmpty(
                    title = if (tabIndex == TAB_VIDEOS) {
                        stringResource(R.string.media_empty_videos)
                    } else {
                        stringResource(R.string.media_empty_photos)
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(shown, key = { it.uri.toString() }) { item ->
                        val key = item.uri.toString()
                        val toggle = {
                            selectedKeys = if (key in selectedKeys) {
                                selectedKeys - key
                            } else {
                                selectedKeys + key
                            }
                        }
                        MediaCell(
                            vm = vm,
                            item = item,
                            selecting = selecting,
                            selected = key in selectedKeys,
                            onToggle = toggle,
                            onLongPress = {
                                selecting = true
                                toggle()
                            },
                        )
                    }
                }
            }
        }

        toast?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp, start = 16.dp, end = 16.dp)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmDelete && chosen.isNotEmpty()) {
        val requested = chosen.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.media_delete_many_title)) },
            text = {
                Text(stringResource(R.string.media_delete_many_body, Fmt.count(requested)))
            },
            confirmButton = {
                TextButton(onClick = {
                    val targets = chosen
                    confirmDelete = false
                    exitSelection()
                    vm.deleteMediaBatch(targets) { done ->
                        // النصّ الموجود مفردٌ («حُذف الملفّ») ولا نصَّ للجمع ولا للفشل
                        // الجزئيّ، فيُلحق به العدد: «(3)» عند نجاح الجميع و«(2/3)» حين
                        // يرفض بعضهم الحذف. والسكوت عن ملفٍّ بقي مكانه أسوأ من عبارةٍ
                        // ركيكة: المستعمل يعدّ ما بقي في الشبكة ولا يعرف لماذا.
                        val label = context.getString(R.string.media_deleted)
                        toast = when {
                            requested == 1 && done == 1 -> label
                            done == requested -> "$label (${Fmt.count(done)})"
                            else -> "$label (${Fmt.count(done)}/${Fmt.count(requested)})"
                        }
                    }
                }) { Text(stringResource(R.string.media_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = Surface,
        )
    }
}

private const val TAB_PHOTOS = 0
private const val TAB_VIDEOS = 1

/** زوايا البلاطة: يُقرأ مرّتين — قصًّا للمحتوى وإطارًا للمحدَّد — فيُعرَّف مرّة */
private val CellShape = RoundedCornerShape(14.dp)

/**
 * حافظ التحديد: مجموعة مفاتيح لا كائنات.
 *
 * `Set` ليس ممّا يُكتب في `Bundle` مباشرةً، وتحويلُه إلى قائمةٍ ثمّ إعادتُه أوضح من
 * الاتّكال على كون تنفيذٍ بعينه قابلًا للتسلسل.
 */
private val SelectionKeysSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * مشاركةٌ جماعيّة بعناوين الملفّات نفسها التي تُشارَك بها الواحدة: على أندرويد 10 فما
 * فوق هي عناوين `MediaStore`، ودونه عناوين مزوّد الملفّات — وكلاهما مبنيٌّ في
 * `MediaRepository` فلا يُعاد بناؤه هنا.
 */
private fun shareMany(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList<Uri>(items.size)
    items.forEach { uris += it.uri }
    // النوع الصريح يُبقي في المُختار التطبيقات التي تقبل الصنف كلّه؛ و`*/*` عند الخلط
    // لأنّ إعلان `image/*` لمجموعةٍ فيها مقطعٌ يُرسل الملفّ إلى تطبيقٍ لا يفتحه.
    val type = when {
        items.all { it.isVideo } -> "video/*"
        items.none { it.isVideo } -> "image/*"
        else -> "*/*"
    }
    val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        this.type = type
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(share, context.getString(R.string.media_share_many))
        )
    }
}

/** شريطٌ فوق الشبكة خارج وضع التحديد: مدخلٌ ظاهر إلى الوضع */
@Composable
private fun EnterSelectionBar(onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onSelect,
            modifier = Modifier.height(56.dp),
        ) {
            Icon(
                Icons.Filled.Checklist,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.media_select),
                style = MaterialTheme.typography.labelLarge.copy(color = Accent),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * شريط إجراءات التحديد.
 *
 * العدّاد يأخذ ما بقي من العرض (`weight`) والأزرار خمسة بمساحة ‎56dp‎ لكلّ واحد: هي
 * أضيق ما يُقبل، وأيّ زيادةٍ هنا تدفع العدّاد خارج الشاشة على هاتفٍ ‎360dp‎.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    val hasAny = count > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceHigh)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.media_selected_count, Fmt.count(count)),
            style = MaterialTheme.typography.labelLarge.copy(color = TextPrimary),
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        BarAction(Icons.Filled.SelectAll, R.string.media_select_all, Accent, true, onSelectAll)
        BarAction(Icons.Filled.Deselect, R.string.media_select_none, Accent, hasAny, onSelectNone)
        BarAction(Icons.Filled.Share, R.string.media_share_many, Accent, hasAny, onShare)
        BarAction(Icons.Filled.Delete, R.string.media_delete, Danger, hasAny, onDelete)
        BarAction(Icons.Filled.Close, R.string.media_exit_selection, TextSecondary, true, onExit)
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    labelRes: Int,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(56.dp),
    ) {
        Icon(
            icon,
            contentDescription = stringResource(labelRes),
            // التعتيم لا الإخفاء: زرٌّ يظهر ويختفي بحسب العدد يُزحزح جيرانه تحت الإصبع
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** حالة الفراغ: عنوانٌ خاصّ بالتبويب، ويبقى سطر «أين تُحفظ الملفّات» لأنّه الجواب المطلوب */
@Composable
private fun MediaEmpty(title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.media_hint),
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    vm: SpeedoViewModel,
    item: MediaItem,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) { thumb = vm.thumbnailOf(item) }

    val mime = if (item.isVideo) "video/*" else "image/*"

    // علامة المحدَّد إطارٌ وعلامة صحّ لا تغييرَ شفافيّة: الصور هنا لقطاتُ طريقٍ ليليّ،
    // والتعتيم على صورةٍ سوداء أصلًا لا يُرى. والألوان تُقرأ هنا لا في عمق الشجرة.
    val markColor = Accent
    val markInk = Bg

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CellShape)
            .background(SurfaceHigh)
            .then(if (selected) Modifier.border(3.dp, markColor, CellShape) else Modifier)
            .combinedClickable(
                onClick = {
                    if (selecting) {
                        onToggle()
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(item.uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                },
                onLongClick = onLongPress,
            ),
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(30.dp)
                    .background(markColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = markInk,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // زرّ المشاركة المفردة يغيب في وضع التحديد: المشاركة حينها من الشريط، وزرٌّ
        // صغير داخل بلاطةٍ تُحدَّد باللمس يسرق لمسةَ التحديد لا أكثر.
        if (!selecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(32.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .combinedClickable(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, item.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(share, context.getString(R.string.media_share))
                            )
                        }
                    }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = stringResource(R.string.media_share),
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelSmall.copy(color = Color.White),
                maxLines = 1,
            )
        }
    }
}
