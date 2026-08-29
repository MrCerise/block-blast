package com.mrcerise.blockgame

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GameViewTest {

    private val W = 1080
    private val H = 2400

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0
        )
        view.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun GameView.field(name: String): Any? {
        val f = GameView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(this)
    }

    private fun GameView.setField(name: String, value: Any?) {
        val f = GameView::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(this, value)
    }

    @Test
    fun fullFlow_renders_places_opensSettings_andRestarts() {
        val app = RuntimeEnvironment.getApplication()
        val view = GameView(app)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, W, H)

        fun f(name: String) = view.field(name) as Float
        val boardLeft = f("boardLeft"); val boardTop = f("boardTop")
        val boardPad = f("boardPad"); val cell = f("cell"); val gap = f("gap")
        val slotCenterX = view.field("slotCenterX") as FloatArray
        val trayCenterY = f("trayCenterY")

        // initial draw must not crash
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)

        // force a deterministic single-block piece into slot 0
        val single = PieceShape(listOf(Cell(0, 0)))
        view.game.tray[0] = single
        val slotX = slotCenterX[0]
        // target: top-left cell (empty on fresh board)
        val r = 0; val c = 0
        val originX = boardLeft + boardPad + c * (cell + gap)
        val originY = boardTop + boardPad + r * (cell + gap)
        val dropX = originX + cell / 2f
        val dropY = originY + cell * 1.15f + 1 * (cell + gap)

        assertFalse(view.game.grid[r][c])
        // drag from tray to the board
        touch(view, MotionEvent.ACTION_DOWN, slotX, trayCenterY)
        touch(view, MotionEvent.ACTION_MOVE, slotX, trayCenterY - 200f)
        touch(view, MotionEvent.ACTION_MOVE, dropX, dropY + 300f)
        touch(view, MotionEvent.ACTION_MOVE, dropX, dropY)
        touch(view, MotionEvent.ACTION_UP, dropX, dropY)

        // the block landed and scored 1 point (no line cleared)
        assertTrue("block should be placed at (0,0)", view.game.grid[r][c])
        assertEquals("single block scores 1", 1, view.game.score)
        assertTrue("slot should now be empty", view.game.tray[0] == null)

        // draw again with placed block + floating/anim state
        view.draw(canvas)
        RobolectricHelpers.idleAnimations()
        view.draw(canvas)

        // open settings via the gear -- a full press AND release, as a real tap is.
        // Regression: the gear used to open on ACTION_DOWN, and the very same gesture's
        // ACTION_UP landed outside the settings card and was read as a tap-outside
        // dismiss, so the panel was only visible while the finger stayed down.
        val gearX = W - 38f * app.resources.displayMetrics.density
        val gearY = view.paddingTop + 30f * app.resources.displayMetrics.density
        touch(view, MotionEvent.ACTION_DOWN, gearX, gearY)
        touch(view, MotionEvent.ACTION_UP, gearX, gearY)
        assertEquals("settings must stay open after the finger lifts",
            true, view.field("showSettings"))
        view.draw(canvas)

        // toggle the sound switch
        val switchRect = view.field("switchRect") as RectF
        val soundBefore = view.sound.enabled
        touch(view, MotionEvent.ACTION_DOWN, switchRect.centerX(), switchRect.centerY())
        touch(view, MotionEvent.ACTION_UP, switchRect.centerX(), switchRect.centerY())
        assertEquals(!soundBefore, view.sound.enabled)
        touch(view, MotionEvent.ACTION_DOWN, switchRect.centerX(), switchRect.centerY())
        touch(view, MotionEvent.ACTION_UP, switchRect.centerX(), switchRect.centerY())
        assertEquals(soundBefore, view.sound.enabled)

        // close settings
        view.setField("showSettings", false)

        // show the game-over overlay and restart via PLAY AGAIN
        view.setField("showGameOver", true)
        view.game.score = 12345
        view.draw(canvas)
        val btn = view.field("btnPlayAgain") as RectF
        touch(view, MotionEvent.ACTION_DOWN, btn.centerX(), btn.centerY())
        touch(view, MotionEvent.ACTION_UP, btn.centerX(), btn.centerY())
        assertEquals(false, view.field("showGameOver"))
        assertEquals(0, view.game.score)
        view.draw(canvas)

        // invalid drop on an occupied cell shakes and keeps the piece
        view.game.grid[0][0] = true
        view.game.tray[0] = single
        touch(view, MotionEvent.ACTION_DOWN, slotX, trayCenterY)
        touch(view, MotionEvent.ACTION_MOVE, slotX, trayCenterY - 200f)
        touch(view, MotionEvent.ACTION_MOVE, dropX, dropY) // (0,0) occupied
        touch(view, MotionEvent.ACTION_UP, dropX, dropY)
        assertNotNull("piece should remain after invalid drop", view.game.tray[0])
        assertTrue("shake should trigger", (view.field("shakeStart") as Long) > 0)

        // bottom-row placement must work even though the finger drops below the frame
        view.setField("shakeStart", -1L)
        view.game.grid[7][0] = false
        view.game.grid[7][1] = false
        view.game.tray[0] = PieceShape(listOf(Cell(0, 0), Cell(0, 1)))
        val domW = 2 * cell + gap
        val domOriginX = boardLeft + boardPad + 0 * (cell + gap)
        val domOriginY = boardTop + boardPad + 7 * (cell + gap)
        val dX = domOriginX + domW / 2f
        val dY = domOriginY + cell * 1.15f + 1 * (cell + gap) // finger ~0.15 cell below frame
        touch(view, MotionEvent.ACTION_DOWN, slotX, trayCenterY)
        touch(view, MotionEvent.ACTION_MOVE, slotX, trayCenterY - 200f)
        touch(view, MotionEvent.ACTION_MOVE, dX, dY - 200f)
        touch(view, MotionEvent.ACTION_MOVE, dX, dY)
        touch(view, MotionEvent.ACTION_UP, dX, dY)
        assertTrue("bottom row cell (7,0) should be filled", view.game.grid[7][0])
        assertTrue("bottom row cell (7,1) should be filled", view.game.grid[7][1])
    }

    /**
     * A finished game must still read as finished after a relaunch. saveGame() persists
     * "over", but loadGame() never read it back, so the flag kept whatever Game.init
     * computed on a fresh empty board (false) -- leaving the player staring at a dead
     * board with no GAME OVER overlay and no PLAY AGAIN button.
     */
    @Test
    fun savedGameOverStateIsRestoredOnRelaunch() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("blockblast_save", android.content.Context.MODE_PRIVATE)
        // a completely full board with three single blocks queued = no legal move
        prefs.edit()
            .putInt("best", 500)
            .putInt("score", 500)
            .putString("grid", "1".repeat(64))
            .putString("tray", "0,0,0")
            .putBoolean("over", true)
            .apply()

        val view = GameView(app)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, W, H)

        assertTrue("a full board with no legal move must be game over", view.game.over)
        assertTrue("the GAME OVER overlay must be shown", view.field("showGameOver") as Boolean)

        // and PLAY AGAIN must actually work from that restored state
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val btn = view.field("btnPlayAgain") as RectF
        touch(view, MotionEvent.ACTION_DOWN, btn.centerX(), btn.centerY())
        touch(view, MotionEvent.ACTION_UP, btn.centerX(), btn.centerY())
        assertFalse(view.field("showGameOver") as Boolean)
        assertFalse("restart must clear the game-over flag", view.game.over)
    }

    /** Build a laid-out view on a clean save, drawn once so hit rects are populated. */
    private fun freshView(): Pair<GameView, Canvas> {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("blockblast_save", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val view = GameView(app)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, W, H)
        val canvas = Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888))
        view.draw(canvas)
        return view to canvas
    }

    private fun openSettings(view: GameView, canvas: Canvas) {
        val gear = view.field("gearRect") as RectF
        touch(view, MotionEvent.ACTION_DOWN, gear.centerX(), gear.centerY())
        touch(view, MotionEvent.ACTION_UP, gear.centerX(), gear.centerY())
        view.draw(canvas)   // populates the switch / button hit rects
    }

    @Test
    fun maxFpsToggle_flipsPersistsAndNotifiesHost() {
        val (view, canvas) = freshView()
        openSettings(view, canvas)

        var notified = 0
        view.onDisplayPrefsChanged = { notified++ }

        val before = view.maxFps
        val fps = view.field("fpsSwitchRect") as RectF
        assertTrue("the Max FPS switch must have been laid out", fps.width() > 0f)

        touch(view, MotionEvent.ACTION_DOWN, fps.centerX(), fps.centerY())
        touch(view, MotionEvent.ACTION_UP, fps.centerX(), fps.centerY())

        assertEquals("toggle should flip", !before, view.maxFps)
        assertEquals("host must be told so it can re-request the display mode", 1, notified)
        assertTrue("settings must stay open while toggling", view.field("showSettings") as Boolean)

        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("blockblast_save", android.content.Context.MODE_PRIVATE)
        assertEquals("choice must persist", !before, prefs.getBoolean("max_fps", before))

        // and it survives a relaunch
        val reopened = GameView(RuntimeEnvironment.getApplication())
        assertEquals(!before, reopened.maxFps)
    }

    /**
     * Hit testing and drawing must agree on the card bounds. They used to be written out
     * separately, so growing the settings card would have made taps near its top edge
     * read as "outside the card" and dismiss the panel.
     */
    @Test
    fun tapInsideSettingsCardDoesNotDismissIt() {
        val (view, canvas) = freshView()
        openSettings(view, canvas)

        // the close X sits dp(12) below the card top, so this recovers the real top edge
        val close = view.field("closeRect") as RectF
        val cardTop = close.top - view.dp(12f)
        val sound = view.field("switchRect") as RectF

        // a point clearly inside the card but above every control
        val x = sound.left - view.dp(20f)
        val y = cardTop + view.dp(6f)
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_UP, x, y)
        assertTrue("tap inside the card must not dismiss it",
            view.field("showSettings") as Boolean)

        // ...but a tap well outside still does
        touch(view, MotionEvent.ACTION_DOWN, 4f, 4f)
        touch(view, MotionEvent.ACTION_UP, 4f, 4f)
        assertFalse("tap outside should dismiss", view.field("showSettings") as Boolean)
    }

    /**
     * Placing the last tray piece refills all three slots, and every one of them should
     * animate in -- including the slot just emptied, which used to be skipped.
     */
    @Test
    fun refilledTrayAnimatesEverySlotIncludingTheOneJustPlayed() {
        val (view, _) = freshView()
        fun f(n: String) = view.field(n) as Float
        val slotCenterX = view.field("slotCenterX") as FloatArray
        val trayCenterY = f("trayCenterY")
        val cell = f("cell"); val gap = f("gap")
        val originX = f("boardLeft") + f("boardPad")
        val originY = f("boardTop") + f("boardPad")

        // leave a single piece in slot 2 so playing it forces a full refill
        view.game.tray[0] = null
        view.game.tray[1] = null
        view.game.tray[2] = PieceShape(listOf(Cell(0, 0)))

        // mark every slot as long-since-born, so a missing animation is detectable
        val trayBorn = view.field("trayBorn") as LongArray
        java.util.Arrays.fill(trayBorn, 0L)

        val dropX = originX + cell / 2f
        val dropY = originY + cell * 1.15f + (cell + gap)
        val t0 = SystemClock.uptimeMillis()
        val sx = slotCenterX[2]
        touch(view, MotionEvent.ACTION_DOWN, sx, trayCenterY)
        touch(view, MotionEvent.ACTION_MOVE, sx, trayCenterY - 200f)
        touch(view, MotionEvent.ACTION_MOVE, dropX, dropY + 300f)
        touch(view, MotionEvent.ACTION_MOVE, dropX, dropY)
        touch(view, MotionEvent.ACTION_UP, dropX, dropY)

        assertTrue("placing the last piece should refill the tray",
            view.game.tray.all { it != null })
        for (i in 0..2) {
            assertTrue("slot " + i + " should animate in after the refill",
                trayBorn[i] >= t0)
        }
    }

    /** A tap on the gear must open settings and leave them open once the finger lifts. */
    @Test
    fun gearTap_opensSettings_andTheyStayOpenAfterRelease() {
        val app = RuntimeEnvironment.getApplication()
        val view = GameView(app)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, W, H)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)   // populates gearRect

        val gear = view.field("gearRect") as RectF
        assertFalse(view.field("showSettings") as Boolean)

        touch(view, MotionEvent.ACTION_DOWN, gear.centerX(), gear.centerY())
        touch(view, MotionEvent.ACTION_UP, gear.centerX(), gear.centerY())
        assertTrue("gear tap should open settings", view.field("showSettings") as Boolean)

        // and it must survive a redraw rather than flicking shut
        view.draw(canvas)
        assertTrue("settings should still be open", view.field("showSettings") as Boolean)

        // tapping well outside the card still dismisses it
        touch(view, MotionEvent.ACTION_DOWN, 5f, (H - 5).toFloat())
        touch(view, MotionEvent.ACTION_UP, 5f, (H - 5).toFloat())
        assertFalse("tap outside should dismiss", view.field("showSettings") as Boolean)
    }
}

/** Small helper for advancing Robolectric's time/looper. */
object RobolectricHelpers {
    fun idleAnimations() {
        try {
            val shadowLooperCls = Class.forName("org.robolectric.Shadows")
            val m = shadowLooperCls.getMethod("shadowOf", android.os.Looper::class.java)
            val looper = android.os.Looper.getMainLooper()
            val shadow = m.invoke(null, looper)
            shadow.javaClass.getMethod("idle").invoke(shadow)
        } catch (_: Throwable) {
        }
    }
}
