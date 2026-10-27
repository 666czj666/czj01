package com.zilv.app.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用图标加载与缓存。
 *
 * **为什么必须缓存**：白名单候选列表最多会展示 200 个应用，
 * `PackageManager.getApplicationIcon` 每个都要读 APK 资源，很慢。
 * 不加缓存的话每次滚动/重组都会重新解码，列表会明显卡顿。
 *
 * 缓存的是**已经缩放好的位图**，尺寸固定为 [ICON_PX]，
 * 避免同一个小图标在几处不同尺寸各解码一份。
 */
object AppIconCache {

    /** 解码尺寸。40dp 在 xxhdpi 上约 120px，取 128 足够清晰。 */
    private const val ICON_PX = 128

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    /** 记录加载失败的包名，避免对同一个坏包反复重试。 */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /**
     * 取应用图标；失败返回 null（界面回退到占位方块，不抛异常）。
     */
    suspend fun load(context: Context, packageName: String): ImageBitmap? {
        cache[packageName]?.let { return it }
        if (packageName in failed) return null

        return withContext(Dispatchers.IO) {
            // 双重检查：并发进入时只解码一次
            cache[packageName]?.let { return@withContext it }

            runCatching {
                val pm = context.packageManager
                val drawable: Drawable = pm.getApplicationIcon(packageName)
                drawable.toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.onFailure {
                failed += packageName
            }.getOrNull()?.also { cache[packageName] = it }
        }
    }

    /** 仅供测试：清空缓存。 */
    fun clear() {
        cache.clear()
        failed.clear()
    }

    /** 仅供测试：当前缓存条数。 */
    val size: Int get() = cache.size
}

/**
 * 应用图标。
 *
 * 加载是异步的，加载完成前显示一块占位底色 —— 而不是留空白，
 * 那样列表在滚动时会"跳一下"。
 *
 * 加载失败时回退为首字母方块，保证每一行的高度和视觉重量一致。
 */
@Composable
fun AppIcon(
    packageName: String,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val context = LocalContext.current

    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = AppIconCache.load(context, packageName)
    }

    val shape = RoundedCornerShape(size / 4)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size)
            )
        } else {
            // 占位：显示首字母，避免空白造成布局跳动
            Text(
                text = fallbackLabel.trim().take(1).ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
