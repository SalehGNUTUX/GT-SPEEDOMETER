package net.gnutux.speedometer.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.gnutux.speedometer.R
import net.gnutux.speedometer.core.media.MediaItem
import net.gnutux.speedometer.ui.SpeedoViewModel
import net.gnutux.speedometer.ui.theme.Accent
import net.gnutux.speedometer.ui.theme.Surface
import net.gnutux.speedometer.ui.theme.SurfaceHigh
import net.gnutux.speedometer.ui.theme.TextSecondary

/**
 * قسم اللقطات والتسجيلات: لمسة تفتح، ولمسة مطوّلة تحذف.
 *
 * التبويبان يفصلان الصور عن الفيديوهات لأنّ الشبكة الموحّدة كانت تخلط نوعين لا
 * يُبحث عنهما معًا: من يريد لقطةً يمرّ على عشرات المقاطع قبل أن يجدها.
 */
// `SecondaryTabRow` ما زال تجريبيًّا في Material 3 والمصرّف يرفضه بلا إقرارٍ صريح.
// والإقرار مقصورٌ على هذه الدالّة لا على الملفّ كلّه: أيّ واجهةٍ تجريبيّة تُضاف
// لاحقًا يجب أن تُقرّ بنفسها لا أن تمرّ في ظلّ هذه.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(vm: SpeedoViewModel, modifier: Modifier = Modifier) {
    val items by vm.mediaItems.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<MediaItem?>(null) }
    // يبقى التبويب المختار عبر تدوير الشاشة: العودة إلى «الصور» بعد كلّ دورة إزعاج
    var tabIndex by rememberSaveable { mutableIntStateOf(TAB_PHOTOS) }

    LaunchedEffect(Unit) { vm.refreshMedia() }

    // التصفية مرهونة بالقائمة وحدها فلا تُعاد مع كلّ تبديل تبويب
    val photos = remember(items) { items.filter { !it.isVideo } }
    val videos = remember(items) { items.filter { it.isVideo } }
    val shown = if (tabIndex == TAB_VIDEOS) videos else photos

    Column(modifier) {
        // TabRow يرتّب أبناءه بحسب اتّجاه التخطيط، فالتبويب الأوّل يقع يمينًا في RTL
        // من تلقاء نفسه؛ أيّ عكسٍ يدويّ هنا يكسر الترتيب على الأجهزة اللاتينيّة
        SecondaryTabRow(
            selectedTabIndex = tabIndex,
            containerColor = Surface,
            contentColor = Accent,
        ) {
            Tab(
                selected = tabIndex == TAB_PHOTOS,
                onClick = { tabIndex = TAB_PHOTOS },
                text = { Text(stringResource(R.string.media_tab_photos)) },
                selectedContentColor = Accent,
                unselectedContentColor = TextSecondary,
            )
            Tab(
                selected = tabIndex == TAB_VIDEOS,
                onClick = { tabIndex = TAB_VIDEOS },
                text = { Text(stringResource(R.string.media_tab_videos)) },
                selectedContentColor = Accent,
                unselectedContentColor = TextSecondary,
            )
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
                    MediaCell(
                        vm = vm,
                        item = item,
                        onLongPress = { pendingDelete = item },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.media_delete_title)) },
            text = { Text("${target.name}\n${stringResource(R.string.media_delete_body)}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMedia(target)
                    pendingDelete = null
                }) { Text(stringResource(R.string.media_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = Surface,
        )
    }
}

private const val TAB_PHOTOS = 0
private const val TAB_VIDEOS = 1

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
private fun MediaCell(vm: SpeedoViewModel, item: MediaItem, onLongPress: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) { thumb = vm.thumbnailOf(item) }

    val mime = if (item.isVideo) "video/*" else "image/*"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceHigh)
            .combinedClickable(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(item.uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(intent) }
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
