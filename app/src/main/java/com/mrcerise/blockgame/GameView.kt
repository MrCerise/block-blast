package com.mrcerise.blockgame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---------------- game state ----------------
    private val prefs: android.content.SharedPreferences =
        context.getSharedPreferences("blockblast_save", Context.MODE_PRIVATE)
    val sound = SoundManager()
    val game = Game()

    // ---------------- metrics ----------------
    private val density = resources.displayMetrics.density
    fun dp(v: Float) = v * density

    // board geometry
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardSize = 0f
    private var cell = 0f
    private var gap = 0f
    private var boardPad = 0f

    // tray geometry
    private var trayCell = 0f
    private var trayGap = 0f
    private var trayCenterY = 0f
    private val slotCenterX = FloatArray(3)

    // ui hit rects
    private val gearRect = RectF()
    private val btnPlayAgain = RectF()
    private val btnRestart = RectF()
    private val switchRect = RectF()
    private val fpsSwitchRect = RectF()
    private val closeRect = RectF()
    private val cardRect = RectF()

    // ---------------- drag state ----------------
    private var dragSlot = -1
    private var dragShape: PieceShape? = null
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f
    private var anchorRow = -1
    private var anchorCol = -1
    private var anchorValid = false
    private var dragOverBoard = false

    // ---------------- animations ----------------
    private class ClearAnim(val cells: List<Cell>, val start: Long)
    private class PopAnim(val cells: List<Cell>, val start: Long)
    private class Floater(val text: String, val x: Float, val y: Float, val big: Boolean, val gold: Boolean, val start: Long)

    private var clearAnim: ClearAnim? = null
    private var popAnim: PopAnim? = null
    private val floaters = ArrayList<Floater>()
    private val trayBorn = LongArray(3)
    private var shakeStart = -1L
    private var gameOverAt = -1L
    private var comboPulseAt = -1L

    private var showSettings = false
    private var showGameOver = false
    private var gearPressed = false

    /**
     * When on, the app asks the compositor for the display's fastest mode. Android
     * presents at vsync, so the panel's refresh rate is a hard ceiling -- this lifts the
     * app from whatever the system picked (often 60Hz) to the panel's maximum.
     */
    var maxFps = false
        private set

    /** Set by the host activity so a settings change can be applied to the window. */
    var onDisplayPrefsChanged: (() -> Unit)? = null

    // measured frame rate, shown only while maxFps is on
    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var fpsValue = 0f

    // ---------------- paints ----------------
    private val pBlock = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val pTextLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    private val fontBold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val fontNormal = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

    // ---------------- cached art ----------------
    // Everything below exists so that onDraw allocates (almost) nothing. Constructing a
    // LinearGradient/RadialGradient allocates a native Skia shader, and the previous code
    // built ~170 of them per frame.

    private var bgFill: LinearGradient? = null
    private var bgVignette: RadialGradient? = null
    private var bgW = 0
    private var bgH = 0

    // top-bar gradients, rebuilt only when the bar geometry actually changes
    private var topBarKey = Int.MIN_VALUE
    private var shCrown: LinearGradient? = null
    private var shGear: LinearGradient? = null
    private var shPill: LinearGradient? = null

    /** Gold (0) and dimmed (3) blocks pre-rendered once, then blitted at any size. */
    private val blockArt = arrayOfNulls<Bitmap>(4)
    private val blockArtRatio = FloatArray(4)
    private var blockArtSize = 0
    private val pBitmap = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // scratch objects reused every frame instead of allocating
    private val tmpRect = RectF()
    private val blockRect = RectF()
    private val scratchPath = Path()

    // O(1) per-cell lookups instead of scanning the clear/pop cell lists 64x per frame
    private val clearMask = Array(8) { BooleanArray(8) }
    private val popMask = Array(8) { BooleanArray(8) }

    // "does this tray piece still fit anywhere" — an 8x8 scan per piece, so it is cached
    // and recomputed only when the game state actually changes
    private val trayFits = BooleanArray(3)
    private var trayFitsVersion = -1

    init {
        sound.enabled = prefs.getBoolean("sound_on", true)
        maxFps = prefs.getBoolean("max_fps", false)
        game.best = prefs.getInt("best", 0)
        loadGame()
        // Derive "is this game finished" from the board actually restored, rather than
        // from the flag Game.init computed on an empty board. saveGame() persisted
        // `over`, but nothing ever read it back, so a finished game came back looking
        // playable: no overlay, no PLAY AGAIN, and no legal move.
        if (game.tray.all { it == null }) game.refillTray()   // recomputes `over` itself
        else game.over = !game.anyMovePossible()
        val now = SystemClock.uptimeMillis()
        for (i in 0..2) {
            if (game.tray[i] != null && trayBorn[i] == 0L) trayBorn[i] = now + i * 90L
        }
        if (game.over) {
            showGameOver = true
        }
        setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            setPadding(0, top, 0, bottom)
            insets
        }
    }

    // ============================================================ layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val W = w.toFloat()
        val contentTop = paddingTop.toFloat()
        val contentBottom = h - paddingBottom.toFloat()
        val contentH = contentBottom - contentTop

        val sideMargin = dp(10f)
        // board sized by both width and the reference's ~41%-of-height proportion
        boardSize = minOf(W - sideMargin * 2f, contentH * 0.46f)
        cell = boardSize / 9.14f
        gap = cell * 0.06f
        boardPad = cell * 0.36f
        boardLeft = (W - boardSize) / 2f
        boardTop = contentTop + contentH * 0.195f

        trayCell = cell * 0.5f
        trayGap = trayCell * 0.08f
        trayCenterY = contentTop + contentH * 0.78f
        for (i in 0..2) slotCenterX[i] = boardLeft + boardSize * (i + 0.5f) / 3f

        buildBlockArt()
    }

    /**
     * Renders one gold block and one dimmed block into bitmaps at slightly above their
     * on-board size, so every later draw is a downscaled blit instead of three fresh
     * gradient shaders.
     */
    private fun buildBlockArt() {
        val artW = ceil(cell * 1.2f).toInt()
        if (artW < 2) return
        if (artW == blockArtSize && blockArt[0] != null) return
        blockArtSize = artW
        val sArt = artW.toFloat()
        for (style in intArrayOf(0, 3)) {
            val contentH = if (style == 0) sArt * BLOCK_EDGE else sArt
            val artH = ceil(contentH).toInt().coerceAtLeast(1)
            blockArt[style] = try {
                val bmp = Bitmap.createBitmap(artW, artH, Bitmap.Config.ARGB_8888)
                drawBlockDirect(Canvas(bmp), 0f, 0f, sArt, style, 255)
                blockArtRatio[style] = artH / sArt
                bmp
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** Cached per-slot "piece still fits somewhere" flag. */
    private fun trayFits(slot: Int): Boolean {
        if (trayFitsVersion != game.version) {
            for (i in 0..2) trayFits[i] = game.canPlaceAnywhere(game.tray[i])
            trayFitsVersion = game.version
        }
        return trayFits[slot]
    }

    // Returned as separate floats on purpose: Kotlin's Pair<Float, Float> is generic, so
    // it boxed both coordinates on every one of the 128 calls this used to make per frame.
    private fun cellX(col: Int): Float = boardLeft + boardPad + col * (cell + gap)
    private fun cellY(row: Int): Float = boardTop + boardPad + row * (cell + gap)

    private fun trayOriginX(shape: PieceShape, slot: Int): Float =
        slotCenterX[slot] - (shape.cols * trayCell + (shape.cols - 1) * trayGap) / 2f

    private fun trayOriginY(shape: PieceShape): Float =
        trayCenterY - (shape.rows * trayCell + (shape.rows - 1) * trayGap) / 2f

    // ============================================================ draw
    override fun onDraw(canvas: Canvas) {
        // Frame time, not wall time. SystemClock.uptimeMillis() sampled here drifts by
        // however long the frame took to start, so time-driven animations judder even at
        // a solid 60fps. drawingTime is the Choreographer's vsync-aligned frame time
        // (same uptimeMillis base as the timestamps the touch handlers record).
        val frame = drawingTime
        val now = if (frame > 0L) frame else SystemClock.uptimeMillis()
        if (maxFps) sampleFps(now)
        drawBackground(canvas)
        drawTopBar(canvas)
        drawScore(canvas)
        drawComboMeter(canvas, now)

        // shake
        var shakeX = 0f
        if (shakeStart > 0) {
            // the vsync frame time can sit just before a touch timestamp recorded in the
            // same frame, so clamp rather than letting the decay term exceed 1
            val t = ((now - shakeStart) / 400f).coerceAtLeast(0f)
            // 28 rad over 400ms is ~4.5 cycles -- barely 5 frames per cycle at 60fps, so
            // it aliased into a judder instead of reading as a shake. ~3 cycles samples
            // cleanly, and a squared falloff settles more softly.
            if (t < 1f) {
                val decay = (1f - t) * (1f - t)
                shakeX = sin(t * 19f) * dp(8f) * decay
            } else shakeStart = -1
        }
        canvas.save()
        canvas.translate(shakeX, 0f)
        drawBoard(canvas, now)
        drawTray(canvas, now)
        canvas.restore()

        drawDrag(canvas)
        drawFloaters(canvas, now)

        // advance clear animation
        val ca = clearAnim
        if (ca != null && now - ca.start > 420) {
            clearAnim = null
            val wasEmpty = game.tray.all { it == null }
            game.afterClearAnim()
            if (wasEmpty) {
                for (i in 0..2) trayBorn[i] = now + 120 + i * 100L
            }
            saveGame()
            if (game.over && gameOverAt < 0) {
                gameOverAt = now + 500
            }
        }
        if (popAnim != null && now - popAnim!!.start > 260) popAnim = null

        if (gameOverAt in 0..now && !showGameOver) {
            showGameOver = true
            sound.gameOver()
            haptic(HapticFeedbackConstants.LONG_PRESS)
        }

        if (showSettings) drawSettings(canvas)
        if (showGameOver) drawGameOver(canvas)

        scheduleNextFrame(now)
    }

    /** Rolling frame-rate estimate over the frames actually drawn. */
    private fun sampleFps(now: Long) {
        val elapsed = now - fpsWindowStart
        if (fpsWindowStart == 0L || elapsed > 1000L) {
            // first frame, or the loop went idle -- start a fresh window
            fpsWindowStart = now
            fpsFrames = 0
            return
        }
        fpsFrames++
        if (elapsed >= 500L) {
            fpsValue = fpsFrames * 1000f / elapsed
            fpsWindowStart = now
            fpsFrames = 0
        }
    }

    private fun scheduleNextFrame(now: Long) {
        val busy = dragSlot >= 0 || clearAnim != null || popAnim != null ||
            floaters.isNotEmpty() || shakeStart > 0 || gameOverAt > now ||
            (comboPulseAt > 0 && now - comboPulseAt < COMBO_PULSE_MS) ||
            trayBorn.any { it > now - 400 }
        if (busy) postInvalidateOnAnimation()
    }

    private fun ensureBackground() {
        if (width == bgW && height == bgH && bgFill != null) return
        bgW = width; bgH = height
        if (width <= 0 || height <= 0) return
        val h = height.toFloat()
        bgFill = LinearGradient(0f, 0f, 0f, h,
            Color.rgb(0xA8, 0x6A, 0x3D), Color.rgb(0x8B, 0x4D, 0x2A), Shader.TileMode.CLAMP)
        bgVignette = RadialGradient(width / 2f, h * 0.32f, h * 0.85f,
            intArrayOf(0x00000000, 0x22000000), floatArrayOf(0.55f, 1.0f), Shader.TileMode.CLAMP)
    }

    private fun drawBackground(canvas: Canvas) {
        ensureBackground()
        val w = width.toFloat(); val h = height.toFloat()
        val fill = bgFill ?: return
        pFill.shader = fill
        canvas.drawRect(0f, 0f, w, h, pFill)
        // The vignette is a full-screen radial gradient — the single most expensive fill
        // in the app — so it is built once rather than per frame.
        pFill.shader = bgVignette
        canvas.drawRect(0f, 0f, w, h, pFill)
        pFill.shader = null
    }

    /** The top bar's gradients depend only on width + top inset, so they are cached. */
    private fun ensureTopBar() {
        val key = width * 31 + paddingTop
        if (key == topBarKey && shCrown != null) return
        topBarKey = key
        val y = paddingTop + dp(30f)
        val ch = dp(26f) * 0.78f
        val ct = y - ch / 2f
        shCrown = LinearGradient(0f, ct, 0f, ct + ch,
            Color.rgb(0xFF, 0xD9, 0x4D), Color.rgb(0xE8, 0xA9, 0x21), Shader.TileMode.CLAMP)
        val gearR = dp(20f)
        shGear = LinearGradient(0f, y - gearR, 0f, y + gearR,
            Color.rgb(0xE3, 0xB2, 0x7A), Color.rgb(0xC2, 0x8A, 0x52), Shader.TileMode.CLAMP)
        val py = y - gearR * 1.18f
        shPill = LinearGradient(0f, py, 0f, py + dp(20f),
            Color.rgb(0xF7, 0xB4, 0x3C), Color.rgb(0xE8, 0x8E, 0x1E), Shader.TileMode.CLAMP)
    }

    private fun drawTopBar(canvas: Canvas) {
        ensureTopBar()
        val y = paddingTop + dp(30f)
        // crown + best
        val crownSize = dp(26f)
        val crownX = dp(18f)
        drawCrown(canvas, crownX + crownSize / 2f, y, crownSize, shCrown)
        pTextLeft.textSize = dp(19f)
        pTextLeft.typeface = fontBold
        pTextLeft.color = Color.rgb(0xF9, 0xCB, 0x6E)
        pTextLeft.setShadowLayer(dp(2f), 0f, dp(1.5f), 0x55000000)
        canvas.drawText(game.best.toString(), crownX + crownSize + dp(8f), y + dp(7f), pTextLeft)
        pTextLeft.clearShadowLayer()

        // gear + NEW badge (fully inside the right edge; the pill sits on the gear's upper-right)
        val gearR = dp(20f)
        val gx = width - dp(38f)
        gearRect.set(gx - gearR, y - gearR, gx + gearR, y + gearR)
        drawGear(canvas, gx, y, gearR, shGear)
        // NEW pill
        val pillW = dp(44f); val pillH = dp(20f)
        val px = gx + gearR * 0.18f; val py = y - gearR * 1.18f
        pFill.shader = shPill
        tmpRect.set(px, py, px + pillW, py + pillH)
        canvas.drawRoundRect(tmpRect, pillH / 2f, pillH / 2f, pFill)
        pFill.shader = null
        pText.textSize = dp(11f)
        pText.typeface = fontBold
        pText.color = Color.WHITE
        canvas.drawText("NEW", px + pillW / 2f, py + pillH * 0.74f, pText)
    }

    private fun drawScore(canvas: Canvas) {
        pText.textSize = cell * 1.05f
        pText.typeface = fontBold
        pText.color = Color.rgb(0xFF, 0xF6, 0xEA)
        pText.setShadowLayer(dp(3f), 0f, dp(2.5f), 0x66000000)
        val y = boardTop - dp(20f)
        canvas.drawText(game.score.toString(), width / 2f, y, pText)
        pText.clearShadowLayer()
    }

    /**
     * Persistent combo readout: how long the current chain is, and how many dry moves it
     * can still absorb before it breaks.
     */
    private fun drawComboMeter(canvas: Canvas, now: Long) {
        val streak = game.streak
        if (streak < 2) return

        // sit in the gap between the top bar and the score
        val gapTop = paddingTop + dp(56f)
        val gapBottom = boardTop - dp(20f) - cell * 1.05f
        val cy = if (gapBottom - gapTop > dp(34f)) (gapTop + gapBottom) / 2f else gapTop + dp(18f)
        val cx = width / 2f

        val pulse = if (comboPulseAt > 0) ((now - comboPulseAt) / COMBO_PULSE_MS.toFloat())
            .coerceIn(0f, 1f) else 1f
        val bump = if (pulse < 1f) 1f + 0.30f * (1f - pulse) * (1f - pulse) else 1f

        val w = dp(150f); val h = dp(30f)
        canvas.save()
        canvas.scale(bump, bump, cx, cy)

        // the chain runs hotter as it grows
        pFill.shader = null
        pFill.color = when {
            streak >= 8 -> Color.rgb(0xFF, 0x5E, 0x3C)
            streak >= 5 -> Color.rgb(0xFF, 0x92, 0x2E)
            else -> Color.rgb(0xF7, 0xB4, 0x3C)
        }
        tmpRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(tmpRect, h / 2f, h / 2f, pFill)

        pText.textSize = dp(15f)
        pText.typeface = fontBold
        pText.color = Color.rgb(0x4A, 0x27, 0x10)
        canvas.drawText("COMBO x" + streak, cx, cy + dp(5f), pText)

        // draining grace bar, just under the pill
        val frac = game.chainGrace.toFloat() / Game.CHAIN_GRACE_MOVES.toFloat()
        val bw = w * 0.72f
        val by = cy + h / 2f + dp(4f)
        pFill.color = 0x55000000
        tmpRect.set(cx - bw / 2f, by, cx + bw / 2f, by + dp(4f))
        canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), pFill)
        if (frac > 0f) {
            pFill.color = Color.rgb(0xFF, 0xF0, 0xD0)
            tmpRect.set(cx - bw / 2f, by, cx - bw / 2f + bw * frac, by + dp(4f))
            canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), pFill)
        }
        canvas.restore()
    }

    private fun drawBoard(canvas: Canvas, now: Long) {
        // frame shadow + frame
        pFill.color = 0x55000000
        tmpRect.set(boardLeft, boardTop + dp(5f), boardLeft + boardSize, boardTop + boardSize + dp(5f))
        canvas.drawRoundRect(tmpRect, dp(12f), dp(12f), pFill)
        pFill.color = Color.rgb(0x3A, 0x1E, 0x10)
        tmpRect.set(boardLeft, boardTop, boardLeft + boardSize, boardTop + boardSize)
        canvas.drawRoundRect(tmpRect, dp(12f), dp(12f), pFill)

        val ca = clearAnim
        val pa = popAnim
        val tClear = if (ca != null) (now - ca.start).toFloat() else 0f

        // rebuild the per-cell masks once instead of scanning the cell lists 64x below
        for (r in 0..7) { clearMask[r].fill(false); popMask[r].fill(false) }
        if (ca != null) for (cc in ca.cells) {
            if (cc.row in 0..7 && cc.col in 0..7) clearMask[cc.row][cc.col] = true
        }
        if (pa != null) for (cc in pa.cells) {
            if (cc.row in 0..7 && cc.col in 0..7) popMask[cc.row][cc.col] = true
        }

        // empty cells underneath everything
        pFill.color = Color.rgb(0x4C, 0x2B, 0x1A)
        for (r in 0..7) {
            for (c in 0..7) {
                if (game.grid[r][c] || clearMask[r][c]) continue
                val x = cellX(c); val y = cellY(r)
                tmpRect.set(x, y, x + cell, y + cell)
                canvas.drawRoundRect(tmpRect, cell * 0.14f, cell * 0.14f, pFill)
            }
        }

        // blocks currently on the board (with pop-in for the just-placed ones)
        for (r in 0..7) {
            for (c in 0..7) {
                if (!game.grid[r][c]) continue
                val x = cellX(c); val y = cellY(r)
                if (pa != null && popMask[r][c]) {
                    val pt = ((now - pa.start) / 230f).coerceIn(0f, 1f)
                    val s = popScale(pt)
                    val d = cell * (1f - s) / 2f
                    drawBlock(canvas, x + d, y + d, cell * s, 0, 255)
                } else {
                    drawBlock(canvas, x, y, cell, 0, 255)
                }
            }
        }

        // cells being cleared: flash white, then shrink away
        if (ca != null) for (cc in ca.cells) {
            val x = cellX(cc.col); val y = cellY(cc.row)
            if (tClear < 150f) {
                val a = if (tClear < 75f) tClear / 75f else 1f - (tClear - 75f) / 75f
                drawBlock(canvas, x, y, cell, 0, (255 * (0.45f + 0.55f * a)).toInt().coerceIn(0, 255))
                pFill.color = Color.argb((a * 170).toInt().coerceIn(0, 255), 0xFF, 0xFF, 0xFF)
                tmpRect.set(x, y, x + cell, y + cell)
                canvas.drawRoundRect(tmpRect, cell * 0.14f, cell * 0.14f, pFill)
            } else {
                val s = 1f - smooth(((tClear - 150f) / 270f).coerceIn(0f, 1f))
                val d = cell * (1f - s) / 2f
                drawBlock(canvas, x + d, y + d, cell * s, 0, 255)
            }
        }

        // hover targets
        val shape = dragShape
        if (dragSlot >= 0 && shape != null && anchorRow >= 0) {
            val style = if (anchorValid) 1 else 2
            for (cc in shape.cells) {
                val r = anchorRow + cc.row
                val c = anchorCol + cc.col
                if (r in 0..7 && c in 0..7) {
                    drawBlock(canvas, cellX(c), cellY(r), cell, style, 255)
                }
            }
        }
    }

    /** Smooth acceleration then deceleration across 0..1. */
    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    /** Quick start, gentle finish. */
    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

    private fun popScale(t: Float): Float =
        if (t < 0.7f) {
            val u = t / 0.7f
            0.55f + 0.55f * (1f - (1f - u) * (1f - u))
        } else {
            val u = (t - 0.7f) / 0.3f
            1.1f - 0.1f * u
        }

    private fun drawTray(canvas: Canvas, now: Long) {
        for (i in 0..2) {
            val shape = game.tray[i] ?: continue
            if (dragSlot == i) continue
            val ox = trayOriginX(shape, i)
            val oy = trayOriginY(shape)
            val age = (now - trayBorn[i]).coerceAtLeast(0)
            val appear = (age / 320f).coerceIn(0f, 1f)
            val scale = if (appear < 1f) popScale(appear) else 1f
            val slide = (1f - appear) * dp(18f)
            val alpha = (appear * 255).toInt()
            val fits = trayFits(i)
            canvas.save()
            val cx = slotCenterX[i]
            val cy = oy + (shape.rows * trayCell + (shape.rows - 1) * trayGap) / 2f
            canvas.translate(cx, cy + slide)
            canvas.scale(scale, scale)
            canvas.translate(-cx, -cy)
            for (cc in shape.cells) {
                val x = ox + cc.col * (trayCell + trayGap)
                val y = oy + cc.row * (trayCell + trayGap)
                drawBlock(canvas, x, y, trayCell, if (fits) 0 else 3, alpha)
            }
            canvas.restore()
        }
    }

    /** Top-left (in board/cell metrics) of the piece as it floats above the finger. */
    private fun dragOriginX(shape: PieceShape): Float =
        dragX - (shape.cols * cell + (shape.cols - 1) * gap) / 2f

    private fun dragOriginY(shape: PieceShape): Float =
        dragY - cell * 1.15f - shape.rows * (cell + gap)

    private fun drawDrag(canvas: Canvas) {
        val shape = dragShape ?: return
        if (dragSlot < 0) return
        if (dragOverBoard) {
            val ox = dragOriginX(shape); val oy = dragOriginY(shape)
            for (cc in shape.cells) {
                drawBlock(canvas, ox + cc.col * (cell + gap), oy + cc.row * (cell + gap), cell, 0, 235)
            }
        } else {
            val w = shape.cols * trayCell + (shape.cols - 1) * trayGap
            val lift = trayCell * 1.3f
            val ox = dragX - w / 2f
            val oy = dragY - lift - shape.rows * (trayCell + trayGap)
            for (cc in shape.cells) {
                drawBlock(canvas, ox + cc.col * (trayCell + trayGap), oy + cc.row * (trayCell + trayGap), trayCell, 0, 235)
            }
        }
    }

    private fun drawFloaters(canvas: Canvas, now: Long) {
        val it = floaters.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val t = (now - f.start) / 900f
            if (t >= 1f) { it.remove(); continue }
            val alpha = (if (t < 0.15f) t / 0.15f
                else 1f - smooth((t - 0.15f) / 0.85f)).coerceIn(0f, 1f)
            val rise = easeOut(t) * dp(38f)
            pText.textSize = if (f.big) cell * 0.62f else cell * 0.42f
            pText.typeface = fontBold
            pText.color = if (f.gold) Color.argb((alpha * 255).toInt(), 0xFF, 0xD3, 0x7A)
            else Color.argb((alpha * 255).toInt(), 0xFF, 0xF6, 0xEA)
            pText.setShadowLayer(dp(2f), 0f, dp(1.5f), 0x66000000)
            canvas.drawText(f.text, f.x, f.y - rise, pText)
            pText.clearShadowLayer()
        }
    }

    /** style: 0 = gold block, 1 = ghost (valid), 2 = invalid, 3 = dimmed gray */
    private fun drawBlock(canvas: Canvas, x: Float, y: Float, s: Float, style: Int, alpha: Int) {
        if (s <= 0.5f) return
        // Styles 0 and 3 are the gradient-filled ones and account for nearly every block
        // drawn, so they come from the pre-rendered bitmaps: one scaled blit instead of
        // three native shader allocations per block per frame.
        val art = if (style == 0 || style == 3) blockArt[style] else null
        if (art != null) {
            blockRect.set(x, y, x + s, y + s * blockArtRatio[style])
            pBitmap.alpha = alpha
            canvas.drawBitmap(art, null, blockRect, pBitmap)
            return
        }
        drawBlockDirect(canvas, x, y, s, style, alpha)
    }

    /**
     * The original immediate-mode block renderer. Still used for the ghost/invalid hover
     * styles (which allocate no shaders anyway) and to render the cached bitmaps above.
     */
    private fun drawBlockDirect(canvas: Canvas, x: Float, y: Float, s: Float, style: Int, alpha: Int) {
        if (s <= 0.5f) return
        val radius = s * 0.15f
        val rect = blockRect
        rect.set(x, y, x + s, y + s)
        when (style) {
            0 -> {
                pBlock.shader = LinearGradient(x, y, x, y + s,
                    Color.rgb(0xF2, 0xCE, 0x80), Color.rgb(0xD8, 0xA6, 0x53), Shader.TileMode.CLAMP)
                pBlock.alpha = alpha
                canvas.drawRoundRect(rect, radius, radius, pBlock)
                // the bottom edge had two identical gradient stops — a flat fill does it
                pBlock.shader = null
                pBlock.color = Color.rgb(0xA8, 0x72, 0x2E)
                pBlock.alpha = (alpha * 0.9f).toInt()
                tmpRect.set(x, y + s, x + s, y + s * BLOCK_EDGE)
                canvas.drawRoundRect(tmpRect, radius * 0.5f, radius * 0.5f, pBlock)
                pBlock.alpha = 255
                // gloss
                val inset = s * 0.13f
                pFill.shader = LinearGradient(x, y, x, y + s * 0.5f,
                    Color.argb((alpha * 0.34f).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255),
                    Shader.TileMode.CLAMP)
                tmpRect.set(x + inset, y + inset * 0.9f, x + s - inset, y + s * 0.48f)
                canvas.drawRoundRect(tmpRect, radius * 0.7f, radius * 0.7f, pFill)
                pFill.shader = null
            }
            1 -> {
                pFill.color = Color.argb((alpha * 0.28f).toInt(), 0xF2, 0xCE, 0x86)
                canvas.drawRoundRect(rect, radius, radius, pFill)
                pStroke.color = Color.argb((alpha * 0.75f).toInt(), 0xF8, 0xD7, 0x8A)
                pStroke.strokeWidth = s * 0.045f
                canvas.drawRoundRect(rect, radius, radius, pStroke)
            }
            2 -> {
                pFill.color = Color.argb((alpha * 0.30f).toInt(), 0xDE, 0x55, 0x3A)
                canvas.drawRoundRect(rect, radius, radius, pFill)
                pStroke.color = Color.argb((alpha * 0.80f).toInt(), 0xF0, 0x7A, 0x55)
                pStroke.strokeWidth = s * 0.045f
                canvas.drawRoundRect(rect, radius, radius, pStroke)
            }
            3 -> {
                // the shader already carries `alpha`, so the paint must not scale it again
                pBlock.alpha = 255
                pBlock.shader = LinearGradient(x, y, x, y + s,
                    Color.argb(alpha, 0x9B, 0x8B, 0x76), Color.argb(alpha, 0x66, 0x59, 0x48),
                    Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, radius, radius, pBlock)
                pBlock.shader = null
            }
        }
    }

    // ---------------- icons ----------------
    private fun drawCrown(canvas: Canvas, cx: Float, cy: Float, size: Float, cached: LinearGradient? = null) {
        val w = size; val h = size * 0.78f
        val l = cx - w / 2f; val t = cy - h / 2f
        val path = scratchPath
        path.reset()
        path.moveTo(l, t + h * 0.85f)
        path.lineTo(l, t + h * 0.25f)
        path.lineTo(l + w * 0.28f, t + h * 0.55f)
        path.lineTo(cx, t)
        path.lineTo(l + w * 0.72f, t + h * 0.55f)
        path.lineTo(l + w, t + h * 0.25f)
        path.lineTo(l + w, t + h * 0.85f)
        path.close()
        pFill.shader = cached ?: LinearGradient(0f, t, 0f, t + h,
            Color.rgb(0xFF, 0xD9, 0x4D), Color.rgb(0xE8, 0xA9, 0x21), Shader.TileMode.CLAMP)
        canvas.drawPath(path, pFill)
        pFill.shader = null
        pFill.color = Color.rgb(0xF5, 0xB9, 0x2C)
        canvas.drawRect(l, t + h * 0.82f, l + w, t + h, pFill)
        pFill.color = Color.rgb(0xFF, 0xE9, 0x8A)
        canvas.drawCircle(l, t + h * 0.22f, w * 0.07f, pFill)
        canvas.drawCircle(cx, t, w * 0.07f, pFill)
        canvas.drawCircle(l + w, t + h * 0.22f, w * 0.07f, pFill)
    }

    private fun drawGear(canvas: Canvas, cx: Float, cy: Float, r: Float, cached: LinearGradient? = null) {
        val teeth = 8
        val path = scratchPath
        path.reset()
        for (i in 0 until teeth * 2) {
            val rr = if (i % 2 == 0) r else r * 0.82f
            val ang = Math.PI * i / teeth
            val px = cx + (rr * Math.cos(ang)).toFloat()
            val py = cy + (rr * Math.sin(ang)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        pFill.shader = cached ?: LinearGradient(0f, cy - r, 0f, cy + r,
            Color.rgb(0xE3, 0xB2, 0x7A), Color.rgb(0xC2, 0x8A, 0x52), Shader.TileMode.CLAMP)
        canvas.drawPath(path, pFill)
        pFill.shader = null
        pFill.color = Color.rgb(0x9A, 0x58, 0x38)
        canvas.drawCircle(cx, cy, r * 0.36f, pFill)
    }

    // ---------------- overlays ----------------
    private fun dimBackground(canvas: Canvas) {
        pFill.color = Color.argb(0x88, 0x2A, 0x14, 0x08)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
    }

    private fun drawCard(canvas: Canvas, rect: RectF) {
        pFill.color = 0x66000000
        canvas.drawRoundRect(RectF(rect.left, rect.top + dp(6f), rect.right, rect.bottom + dp(6f)),
            dp(18f), dp(18f), pFill)
        pFill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            Color.rgb(0x7E, 0x48, 0x28), Color.rgb(0x5C, 0x31, 0x19), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pFill)
        pFill.shader = null
        pStroke.color = Color.argb(160, 0xE0, 0xA8, 0x62)
        pStroke.strokeWidth = dp(1.5f)
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pStroke)
    }

    private fun drawGameOver(canvas: Canvas) {
        dimBackground(canvas)
        gameOverCard(cardRect)
        val card = cardRect
        val cw = card.width()
        val cx = card.centerX()
        drawCard(canvas, card)

        pText.textSize = dp(26f)
        pText.typeface = fontBold
        pText.color = Color.rgb(0xFF, 0xF6, 0xEA)
        canvas.drawText("GAME OVER", cx, card.top + dp(46f), pText)

        drawCrown(canvas, cx, card.top + dp(88f), dp(30f))
        pText.textSize = dp(16f)
        pText.typeface = fontBold
        pText.color = Color.rgb(0xF9, 0xCB, 0x6E)
        canvas.drawText("BEST  " + game.best, cx, card.top + dp(118f), pText)

        pText.textSize = dp(13f)
        pText.typeface = fontNormal
        pText.color = Color.argb(200, 0xFF, 0xE8, 0xCE)
        canvas.drawText("SCORE", cx, card.top + dp(158f), pText)
        pText.textSize = dp(40f)
        pText.typeface = fontBold
        pText.color = Color.WHITE
        canvas.drawText(game.score.toString(), cx, card.top + dp(200f), pText)

        val bw = cw * 0.7f; val bh = dp(52f)
        btnPlayAgain.set(cx - bw / 2f, card.bottom - dp(74f), cx + bw / 2f, card.bottom - dp(74f) + bh)
        pFill.shader = LinearGradient(0f, btnPlayAgain.top, 0f, btnPlayAgain.bottom,
            Color.rgb(0xF8, 0xD7, 0x8A), Color.rgb(0xDF, 0xAF, 0x5E), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(btnPlayAgain, bh / 2f, bh / 2f, pFill)
        pFill.shader = null
        pText.textSize = dp(18f)
        pText.typeface = fontBold
        pText.color = Color.rgb(0x5A, 0x32, 0x16)
        canvas.drawText("PLAY AGAIN", cx, btnPlayAgain.centerY() + dp(7f), pText)
    }

    /** One labelled toggle row; [out] receives the switch's hit rect. */
    private fun drawToggleRow(
        canvas: Canvas, card: RectF, y: Float, label: String, on: Boolean, out: RectF
    ) {
        pText.textAlign = Paint.Align.LEFT
        pText.textSize = dp(18f)
        pText.typeface = fontNormal
        pText.color = Color.rgb(0xFF, 0xF0, 0xDE)
        canvas.drawText(label, card.left + dp(28f), y + dp(7f), pText)
        pText.textAlign = Paint.Align.CENTER

        val sw = dp(54f); val shh = dp(30f)
        out.set(card.right - dp(28f) - sw, y - shh / 2f, card.right - dp(28f), y + shh / 2f)
        pFill.shader = null
        pFill.color = if (on) Color.rgb(0xF0, 0xB7, 0x5E) else Color.rgb(0x6B, 0x47, 0x30)
        canvas.drawRoundRect(out, shh / 2f, shh / 2f, pFill)
        pFill.color = Color.WHITE
        canvas.drawCircle(
            if (on) out.right - shh / 2f else out.left + shh / 2f,
            out.centerY(), shh / 2f - dp(3f), pFill
        )
    }

    private fun drawSettings(canvas: Canvas) {
        dimBackground(canvas)
        settingsCard(cardRect)
        val card = cardRect
        val cw = card.width()
        val cx = card.centerX()
        drawCard(canvas, card)

        pText.textSize = dp(23f)
        pText.typeface = fontBold
        pText.color = Color.rgb(0xFF, 0xF6, 0xEA)
        canvas.drawText("SETTINGS", cx, card.top + dp(44f), pText)

        // close X
        closeRect.set(card.right - dp(42f), card.top + dp(12f), card.right - dp(10f), card.top + dp(44f))
        pText.textSize = dp(22f)
        pText.color = Color.rgb(0xFF, 0xE8, 0xCE)
        canvas.drawText("×", closeRect.centerX(), closeRect.centerY() + dp(8f), pText)

        drawToggleRow(canvas, card, card.top + dp(94f), "Sound", sound.enabled, switchRect)
        drawToggleRow(canvas, card, card.top + dp(150f), "Max FPS", maxFps, fpsSwitchRect)

        // caption under the FPS row: what it is actually doing right now
        pText.textAlign = Paint.Align.LEFT
        pText.textSize = dp(11f)
        pText.typeface = fontNormal
        pText.color = Color.argb(170, 0xFF, 0xE8, 0xCE)
        val caption = if (!maxFps) "Uses the system default refresh rate"
            else if (fpsValue > 0f) "Running at %.0f fps".format(fpsValue)
            else "Requesting the display's fastest mode"
        canvas.drawText(caption, card.left + dp(28f), card.top + dp(176f), pText)
        pText.textAlign = Paint.Align.CENTER

        // restart button
        val bw = cw * 0.66f; val bh = dp(48f)
        btnRestart.set(cx - bw / 2f, card.bottom - dp(70f), cx + bw / 2f, card.bottom - dp(70f) + bh)
        pFill.color = Color.argb(0, 0, 0, 0)
        pStroke.color = Color.rgb(0xF0, 0xB7, 0x5E)
        pStroke.strokeWidth = dp(2f)
        canvas.drawRoundRect(btnRestart, bh / 2f, bh / 2f, pStroke)
        pText.textSize = dp(17f)
        pText.typeface = fontBold
        pText.color = Color.rgb(0xF8, 0xD7, 0x8A)
        canvas.drawText("RESTART GAME", cx, btnRestart.centerY() + dp(6f), pText)
    }

    // ============================================================ touch
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                if (showGameOver) return true
                if (showSettings) return true
                if (gearRect.contains(x, y)) {
                    // Open on ACTION_UP, like every other button here. Opening on DOWN
                    // meant this same gesture's UP landed outside the settings card and
                    // was treated as a tap-outside dismiss, so the panel stayed up only
                    // while the finger was held down.
                    gearPressed = true
                    return true
                }
                // tray hit test (whole slot is a generous touch target)
                val slot = slotAt(x, y)
                if (slot >= 0 && game.tray[slot] != null) {
                    dragSlot = slot
                    dragShape = game.tray[slot]
                    dragX = x; dragY = y
                    anchorRow = -1; anchorCol = -1
                    sound.pickup()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragSlot >= 0) {
                    dragX = x; dragY = y
                    updateAnchor()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (gearPressed) {
                    gearPressed = false
                    if (gearRect.contains(x, y)) {
                        showSettings = true
                        sound.click()
                    }
                    invalidate()
                    return true
                }
                if (showGameOver) {
                    if (btnPlayAgain.contains(x, y)) { sound.click(); restart() }
                    // only the PLAY AGAIN button dismisses the game-over screen
                    invalidate()
                    return true
                }
                if (showSettings) {
                    if (closeRect.contains(x, y) || !pointInCard(x, y, true)) {
                        showSettings = false
                    } else if (switchRect.contains(x, y)) {
                        sound.enabled = !sound.enabled
                        prefs.edit().putBoolean("sound_on", sound.enabled).apply()
                        sound.click()
                    } else if (fpsSwitchRect.contains(x, y)) {
                        maxFps = !maxFps
                        prefs.edit().putBoolean("max_fps", maxFps).apply()
                        fpsValue = 0f; fpsWindowStart = 0L; fpsFrames = 0
                        sound.click()
                        onDisplayPrefsChanged?.invoke()
                    } else if (btnRestart.contains(x, y)) {
                        sound.click()
                        showSettings = false
                        restart()
                    }
                    invalidate()
                    return true
                }
                if (dragSlot >= 0) {
                    val shape = dragShape
                    val ok = shape != null && anchorValid && anchorRow >= 0
                    if (ok) {
                        placePiece(anchorRow, anchorCol)
                    } else {
                        val wasOverBoard = dragOverBoard
                        dragSlot = -1; dragShape = null; anchorRow = -1; dragOverBoard = false
                        if (wasOverBoard) {
                            sound.invalid()
                            shakeStart = SystemClock.uptimeMillis()
                            haptic(HapticFeedbackConstants.LONG_PRESS)
                        }
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                gearPressed = false
                dragSlot = -1; dragShape = null; anchorRow = -1; dragOverBoard = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // Card bounds live in one place so drawing and hit testing can never drift apart.
    private fun settingsCard(out: RectF) = cardBounds(out, 0.80f, dp(302f))
    private fun gameOverCard(out: RectF) = cardBounds(out, 0.82f, dp(330f))

    private fun cardBounds(out: RectF, widthFactor: Float, h: Float) {
        val w = boardSize * widthFactor
        val cx = width / 2f
        val cy = boardTop + boardSize / 2f
        out.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    private fun pointInCard(x: Float, y: Float, settings: Boolean): Boolean {
        if (settings) settingsCard(cardRect) else gameOverCard(cardRect)
        return x >= cardRect.left && x <= cardRect.right &&
            y >= cardRect.top && y <= cardRect.bottom
    }

    private fun slotAt(x: Float, y: Float): Int {
        val trayH = trayCell * 5f
        if (y < trayCenterY - trayH / 2f - dp(24f) || y > trayCenterY + trayH / 2f + dp(24f)) return -1
        for (i in 0..2) {
            if (abs(x - slotCenterX[i]) < boardSize / 6f) return i
        }
        return -1
    }

    private fun updateAnchor() {
        val shape = dragShape ?: return
        anchorValid = false
        anchorRow = -1; anchorCol = -1
        dragOverBoard = false

        // decide board-mode by whether the floating piece (lifted above the finger)
        // overlaps the board — this lets pieces reach the bottom row even though the
        // finger itself drops below the board frame
        val ox = dragOriginX(shape); val oy = dragOriginY(shape)
        val pieceW = shape.cols * cell + (shape.cols - 1) * gap
        val pieceH = shape.rows * cell + (shape.rows - 1) * gap
        val m = cell
        val overlaps = oy + pieceH >= boardTop - m && oy <= boardTop + boardSize + m &&
            ox + pieceW >= boardLeft - m && ox <= boardLeft + boardSize + m
        if (!overlaps) return
        dragOverBoard = true

        // anchor row/col = where the piece's top-left would snap on the grid
        val r = Math.round((oy - (boardTop + boardPad)) / (cell + gap))
        val c = Math.round((ox - (boardLeft + boardPad)) / (cell + gap))
        anchorRow = r
        anchorCol = c
        anchorValid = game.canPlace(shape, r, c)
    }

    // ============================================================ actions
    private fun placePiece(r: Int, c: Int) {
        val slot = dragSlot
        val shape = dragShape ?: return
        // snapshot the actual pieces, not just "was occupied" -- see the refill note below
        val before = arrayOfNulls<PieceShape>(3)
        for (i in 0..2) before[i] = game.tray[i]
        val result = game.place(slot, r, c)
        if (!result.placed) {
            dragSlot = -1; dragShape = null
            return
        }
        val now = SystemClock.uptimeMillis()
        dragSlot = -1; dragShape = null; anchorRow = -1; anchorCol = -1

        val placed = shape.cells.map { Cell(r + it.row, c + it.col) }
        popAnim = PopAnim(placed, now)
        sound.place()
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)

        if (result.clearedLines > 0) {
            clearAnim = ClearAnim(result.clearedCells, now + 150)
            sound.clear(result.clearedLines)
            haptic(HapticFeedbackConstants.LONG_PRESS)
            val centerX = width / 2f
            val centerY = boardTop + boardSize / 2f
            floaters.add(Floater("+${result.gained}", centerX, centerY - cell * 0.6f, false, false, now + 200))
            // clearedLines == 1 used to fall through to "UNBELIEVABLE!"
            val praise = when {
                result.clearedLines >= 5 -> "UNBELIEVABLE!"
                result.clearedLines == 4 -> "EXCELLENT!"
                result.clearedLines == 3 -> "GREAT!"
                result.clearedLines == 2 -> "GOOD!"
                result.streak >= 5 -> "ON FIRE!"
                result.streak >= 3 -> "COMBO!"
                else -> "NICE!"
            }
            floaters.add(Floater(praise, centerX, centerY + cell * 0.2f, true, true, now + 200))
            if (result.streak >= 2) {
                floaters.add(Floater("COMBO x${result.streak}", centerX, centerY + cell * 0.95f, true, true, now + 200))
                comboPulseAt = now
                // a long chain or a big multi-clear gets a kick
                if (result.streak >= 3 || result.clearedLines >= 3) shakeStart = now
            }
        }
        // Refilled slots animate in. The slot just played counts too: placing the last
        // piece refills all three, and keying off "was this slot occupied" skipped the
        // one you had just emptied, so its replacement popped in with no animation while
        // the other two slid in.
        for (i in 0..2) {
            if (game.tray[i] != null && (before[i] == null || i == slot)) {
                trayBorn[i] = now + 250 + i * 90L
            }
        }
        saveGame()
        if (game.over && clearAnim == null && gameOverAt < 0) {
            gameOverAt = now + 500
        }
    }

    private fun restart() {
        game.reset()
        clearAnim = null; popAnim = null
        floaters.clear()
        gameOverAt = -1L
        showGameOver = false
        showSettings = false
        shakeStart = -1
        comboPulseAt = -1L
        val now = SystemClock.uptimeMillis()
        for (i in 0..2) trayBorn[i] = now + i * 90L
        saveGame()
        invalidate()
    }

    private fun haptic(constant: Int) {
        try {
            performHapticFeedback(constant, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        } catch (_: Throwable) {
            try { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } catch (_: Throwable) {}
        }
    }

    private companion object {
        /** Blocks render a thin lip below the body: total height is size * BLOCK_EDGE. */
        const val BLOCK_EDGE = 1.06f

        /** How long the combo meter's pop lasts, in ms. */
        const val COMBO_PULSE_MS = 340L
    }

    // ============================================================ persistence
    // NB: `over` is intentionally not persisted. It is fully derivable from the grid and
    // tray, and storing it separately is what let the two disagree: the flag was written
    // but never read back, so a finished game reloaded as playable.
    private fun saveGame() {
        prefs.edit()
            .putInt("best", game.best)
            .putInt("score", game.score)
            .putString("grid", game.serializeGrid())
            .putString("tray", game.serializeTray())
            .apply()
    }

    private fun loadGame() {
        if (!prefs.contains("grid")) return
        try {
            game.score = prefs.getInt("score", 0)
            game.deserializeGrid(prefs.getString("grid", "") ?: "")
            game.deserializeTray(prefs.getString("tray", "-1,-1,-1") ?: "-1,-1,-1")
            // existing pieces shouldn't play the entrance animation
            val old = SystemClock.uptimeMillis() - 10000L
            for (i in 0..2) if (game.tray[i] != null) trayBorn[i] = old
            game.best = maxOf(game.best, game.score)
            // NB: `over` is deliberately not decided here -- the tray may still be empty
            // and about to be refilled. init() recomputes it once the tray is settled.
        } catch (_: Throwable) {
        }
    }
}
