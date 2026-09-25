package com.pauwma.glyphbeat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.pauwma.glyphbeat.core.DeviceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports a user-supplied GIF and turns it into a looping [com.pauwma.glyphbeat.themes.animation.CustomTheme].
 *
 * The GIF is decoded frame-by-frame, each frame is fit to the current device's Glyph grid
 * (aspect-preserving, centred on black) and converted to a flat grayscale brightness array —
 * the exact same on-disk format used by themes imported from Glyph Museum. Because the result
 * is a normal CustomTheme, it shows up in the theme grid, animates in the preview, and loops on
 * the Glyph Matrix while music is playing with no changes to the playback service.
 *
 * Decoding is done with [Movie] so there are no extra dependencies. Movie doesn't expose exact
 * per-frame boundaries, so we sample the loop at a fixed rate; for a 25x25 monochrome matrix this
 * is visually indistinguishable from the source while keeping storage small.
 */
class GifImportManager(private val context: Context) {

    private val storage = CustomThemeStorage.getInstance(context)

    sealed class ImportResult {
        data class Success(val themeName: String) : ImportResult()
        data class Error(val message: String) : ImportResult()
        data object ThemeLimitReached : ImportResult()
    }

    /**
     * Decode [uri] and save it as a custom looping theme.
     *
     * @param uri content Uri of the picked GIF (must be readable by this app).
     * @param title optional display name; falls back to a default when blank.
     */
    suspend fun importGif(uri: Uri, title: String? = null): ImportResult = withContext(Dispatchers.IO) {
        try {
            if (storage.getThemeCount() >= MAX_THEMES) {
                return@withContext ImportResult.ThemeLimitReached
            }

            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext ImportResult.Error("Couldn't open the selected file")

            val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext ImportResult.Error("That doesn't look like a valid GIF")

            val srcW = movie.width()
            val srcH = movie.height()
            if (srcW <= 0 || srcH <= 0) {
                return@withContext ImportResult.Error("The GIF has no drawable frames")
            }

            val gs = DeviceManager.resolution.gridSize
            val totalDuration = movie.duration() // ms; 0 for static / undelayed gifs

            val frames = mutableListOf<List<Int>>()
            val durations = mutableListOf<Long>()

            // One reusable source-sized canvas; the grid frame is allocated per sample.
            val srcBitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
            val srcCanvas = Canvas(srcBitmap)

            if (totalDuration <= 0) {
                // Static or single-frame GIF — one frame is enough.
                movie.setTime(0)
                frames.add(renderFlatFrame(movie, srcBitmap, srcCanvas, srcW, srcH, gs))
                durations.add(DEFAULT_FRAME_MS)
            } else {
                val frameCount = (totalDuration / TARGET_FRAME_MS).coerceIn(MIN_FRAMES, MAX_FRAMES)
                val step = totalDuration.toDouble() / frameCount
                for (i in 0 until frameCount) {
                    val t = (i * step).toInt().coerceIn(0, totalDuration - 1)
                    movie.setTime(t)
                    frames.add(renderFlatFrame(movie, srcBitmap, srcCanvas, srcW, srcH, gs))
                    durations.add(step.toLong().coerceIn(MIN_FRAME_MS, MAX_FRAME_MS))
                }
            }

            srcBitmap.recycle()

            if (frames.isEmpty()) {
                return@withContext ImportResult.Error("Couldn't read any frames from the GIF")
            }

            val cleanTitle = (title?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TITLE)
                .take(MAX_TITLE_LEN)

            // Negative postId marks a locally-created GIF theme so it can never collide
            // with a positive Glyph Museum post id.
            val postId = -System.currentTimeMillis()

            val data = CustomThemeData(
                postId = postId,
                title = cleanTitle,
                author = AUTHOR,
                frames = frames,
                durations = durations,
                importDate = System.currentTimeMillis(),
                resolution = DeviceManager.resolution.dbValue
            )

            if (!storage.saveTheme(data)) {
                return@withContext ImportResult.Error("Couldn't save the GIF theme")
            }

            Log.d(TAG, "Imported GIF theme '$cleanTitle' (${frames.size} frames, ${totalDuration}ms loop)")
            ImportResult.Success(cleanTitle)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "GIF too large to import", e)
            ImportResult.Error("That GIF is too large to import")
        } catch (e: Exception) {
            Log.e(TAG, "GIF import failed", e)
            ImportResult.Error(e.message ?: "Something went wrong importing the GIF")
        }
    }

    /**
     * Draw the movie's current frame, fit it into a [gs]x[gs] grid (aspect-preserving, centred on
     * black) and convert to a flat grayscale brightness array of length gs*gs.
     */
    private fun renderFlatFrame(
        movie: Movie,
        srcBitmap: Bitmap,
        srcCanvas: Canvas,
        srcW: Int,
        srcH: Int,
        gs: Int
    ): List<Int> {
        // Flatten any transparency onto black, then draw the current frame at native size.
        srcCanvas.drawColor(Color.BLACK)
        movie.draw(srcCanvas, 0f, 0f)

        val scale = minOf(gs.toFloat() / srcW, gs.toFloat() / srcH)
        val drawW = (srcW * scale).coerceAtLeast(1f)
        val drawH = (srcH * scale).coerceAtLeast(1f)
        val left = (gs - drawW) / 2f
        val top = (gs - drawH) / 2f

        val gridBitmap = Bitmap.createBitmap(gs, gs, Bitmap.Config.ARGB_8888)
        val gridCanvas = Canvas(gridBitmap)
        gridCanvas.drawColor(Color.BLACK)
        gridCanvas.drawBitmap(srcBitmap, null, RectF(left, top, left + drawW, top + drawH), null)

        val flat = IntArray(gs * gs)
        for (row in 0 until gs) {
            for (col in 0 until gs) {
                val pixel = gridBitmap.getPixel(col, row)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Rec. 601 luminance — matches CoverArtTheme's conversion for a consistent look.
                flat[row * gs + col] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            }
        }
        gridBitmap.recycle()
        return flat.asList()
    }

    companion object {
        private const val TAG = "GifImportManager"
        private const val MAX_THEMES = 20        // mirrors CustomThemeStorage
        private const val TARGET_FRAME_MS = 66   // ~15 fps sampling of the source loop
        private const val MIN_FRAMES = 2
        private const val MAX_FRAMES = 60
        private const val DEFAULT_FRAME_MS = 150L
        private const val MIN_FRAME_MS = 40L
        private const val MAX_FRAME_MS = 500L
        private const val MAX_TITLE_LEN = 24
        private const val DEFAULT_TITLE = "My GIF"
        private const val AUTHOR = "you"
    }
}
