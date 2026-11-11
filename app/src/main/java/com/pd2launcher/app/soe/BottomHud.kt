package com.pd2launcher.app.soe

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.pd2launcher.app.Framebuffer
import com.pd2launcher.app.GameEnvironment
import com.pd2launcher.app.PresentServer
import com.pd2launcher.app.ScreenPixels
import com.pd2launcher.app.input.RelayEvent
import java.io.IOException
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Bottom-screen HUD (Settings → Game, off by default): on a device with a second display Android
 * lets apps present on (the AYN Thor's lower screen), shows the game's life globe, potion belt and
 * mana globe there, copied from the game's picture [FPS] times a second. A tap on a belt slot
 * presses that slot's key (1 to 4), so the potion is drunk.
 *
 * The parts are rectangles of PD2's 1068 x 600 picture ([Part]), which D2GL scales to fill the X
 * screen, so they are read as fractions of the X screen. Where the game's own buffer is on the
 * screen ([PresentServer.readShown]) the part is read from it; otherwise from the X screen
 * ([Framebuffer]).
 */
class BottomHud private constructor(private val presentation: HudPresentation) {
    fun dismiss() = presentation.stop()

    /** A rectangle of PD2's 1068 x 600 picture. */
    data class Part(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    companion object {
        private const val TAG = "pd2-bottomhud"
        const val FPS = 20
        private const val PICTURE_WIDTH = 1068f
        private const val PICTURE_HEIGHT = 600f

        /** PD2's life globe, bottom left. */
        val LIFE = Part(0, 470, 160, 600)
        /** PD2's mana globe, bottom right. */
        val MANA = Part(908, 470, 1068, 600)
        /** PD2's belt: its first row, four slots, 1 to 4 left to right. */
        val BELT = Part(556, 566, 682, 598)
        const val SLOTS = 4

        /** The keysyms of 1 to 4. */
        private val SLOT_KEYS = intArrayOf(0x31, 0x32, 0x33, 0x34)

        /** The display apps may present on, other than the default; null without one. */
        fun secondDisplay(context: Context): Display? =
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        /** Shows the HUD on the second display, or null without one (or when Android refuses it). */
        fun show(activity: Activity, framebuffer: Framebuffer): BottomHud? {
            val display = secondDisplay(activity) ?: return null
            return try {
                val presentation = HudPresentation(activity, display, framebuffer)
                presentation.show()
                BottomHud(presentation)
            } catch (e: WindowManager.InvalidDisplayException) {
                Log.w(TAG, "no presentation on display ${display.displayId}", e)
                null
            }
        }

        fun press(slot: Int) {
            val relay = GameEnvironment.input.value?.relay ?: return
            val sym = SLOT_KEYS.getOrNull(slot) ?: return
            Thread {
                try {
                    relay.send(listOf(RelayEvent.Key(sym, true), RelayEvent.Key(sym, false)))
                } catch (_: IOException) {
                    // The relay is gone with the game environment.
                }
            }.start()
        }

        /**
         * Copies [part] of the X screen ([screenWidth] x [screenHeight]) into [into] (ARGB),
         * reading each pixel through [pixel] and [argb].
         */
        internal fun copy(
            part: Part, screenWidth: Int, screenHeight: Int, into: Bitmap, row: IntArray,
            pixel: (Int, Int) -> Int, argb: (Int) -> Int,
        ) {
            val sx = screenWidth / PICTURE_WIDTH
            val sy = screenHeight / PICTURE_HEIGHT
            val x0 = (part.left * sx).toInt().coerceIn(0, screenWidth - 1)
            val y0 = (part.top * sy).toInt().coerceIn(0, screenHeight - 1)
            val w = min(into.width, screenWidth - x0)
            val h = min(into.height, screenHeight - y0)
            for (y in 0 until h) {
                for (x in 0 until w) row[x] = argb(pixel(x0 + x, y0 + y))
                into.setPixels(row, 0, w, 0, y, w, 1)
            }
        }

        /** The X screen's words, 0xXXRRGGBB. */
        internal fun fromScreen(word: Int): Int = word or OPAQUE

        /** The game's buffer's words, R8G8B8A8 read little-endian: 0xAABBGGRR. */
        internal fun fromBuffer(word: Int): Int =
            OPAQUE or ((word and 0xFF) shl 16) or (word and 0xFF00) or ((word shr 16) and 0xFF)

        private const val OPAQUE = 0xFF000000.toInt()
    }

    private class HudPresentation(
        context: Context, display: Display, private val framebuffer: Framebuffer,
    ) : Presentation(context, display) {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "pd2-bottomhud") }
        @Volatile private var running = true
        private lateinit var view: HudView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view = HudView(context)
            setContentView(view)
            worker.execute(::loop)
        }

        fun stop() {
            running = false
            worker.shutdown()
            try {
                dismiss()
            } catch (_: Exception) {
                // The display or the window is already gone.
            }
        }

        private fun loop() {
            var pixels: ScreenPixels? = null
            // Two sets: the view draws one while the next frame is copied into the other.
            var sets = arrayOfNulls<Bitmaps>(2)
            var next = 0
            while (running) {
                val started = System.nanoTime()
                try {
                    if (pixels == null) pixels = framebuffer.map()
                    val screen = pixels
                    if (screen != null) {
                        val w = screen.header.width
                        val h = screen.header.height
                        if (sets[0]?.matches(w, h) != true) sets = arrayOf<Bitmaps?>(Bitmaps(w, h), Bitmaps(w, h))
                        val b = sets[next]!!
                        next = 1 - next
                        val shown = PresentServer.current.value?.readShown { frame ->
                            b.fill(w, h, frame::pixel, ::fromBuffer)
                        }
                        if (shown == null) b.fill(w, h, screen::pixel, ::fromScreen)
                        view.post { view.show(b) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "frame", e)
                    pixels = null
                }
                val waitMs = 1000L / FPS - (System.nanoTime() - started) / 1_000_000
                if (waitMs > 0) {
                    try {
                        Thread.sleep(waitMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
    }

    /** The parts' bitmaps at the X screen's scale. */
    private class Bitmaps(private val screenWidth: Int, private val screenHeight: Int) {
        private fun bitmap(part: Part) = Bitmap.createBitmap(
            maxOf(1, (part.width * screenWidth / PICTURE_WIDTH).toInt()),
            maxOf(1, (part.height * screenHeight / PICTURE_HEIGHT).toInt()),
            Bitmap.Config.ARGB_8888,
        )

        val life = bitmap(LIFE)
        val mana = bitmap(MANA)
        val belt = bitmap(BELT)
        private val row = IntArray(screenWidth)

        fun matches(w: Int, h: Int) = w == screenWidth && h == screenHeight

        fun fill(w: Int, h: Int, pixel: (Int, Int) -> Int, argb: (Int) -> Int) {
            copy(LIFE, w, h, life, row, pixel, argb)
            copy(MANA, w, h, mana, row, pixel, argb)
            copy(BELT, w, h, belt, row, pixel, argb)
        }
    }

    /**
     * The lower screen, dressed like Diablo II's own panels: a dark stone slab, bronze bevelled
     * frames with rivets, gold serif lettering. The life globe sits at the left and the mana globe
     * at the right, each in a bronze-ringed socket with its colour glowing behind it; the belt runs
     * across the bottom, four sunken slots numbered 1 to 4, each a button.
     */
    private class HudView(context: Context) : View(context) {
        private var bitmaps: Bitmaps? = null
        private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
        private val slotRects = Array(SLOTS) { RectF() }
        private var pressed = -1
        private var stone: Bitmap? = null

        private val gold = Color.rgb(199, 179, 119)
        private val bronzeLight = Color.rgb(212, 175, 106)
        private val bronze = Color.rgb(138, 109, 59)
        private val bronzeDark = Color.rgb(59, 42, 20)
        private val red = Color.rgb(170, 20, 20)
        private val blue = Color.rgb(30, 50, 190)

        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            color = gold
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.18f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        private val number = Paint(title).apply { letterSpacing = 0f }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        fun show(next: Bitmaps) {
            bitmaps = next
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            stone = if (w > 0 && h > 0) stoneSlab(w, h) else null
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            stone?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(Color.BLACK)
            val edge = w * 0.012f
            val outer = RectF(edge, edge, w - edge, h - edge)
            bevel(canvas, outer, edge * 1.4f, raised = true)
            rivets(canvas, outer, edge * 1.6f)

            val b = bitmaps
            val pad = w * 0.035f
            title.textSize = h * 0.045f
            val topH = h * 0.6f
            val globeBottom = topH - title.textSize * 1.8f
            globe(canvas, b?.life, RectF(pad * 1.5f, pad * 1.5f, w / 2 - pad, globeBottom), red, "LIFE")
            globe(canvas, b?.mana, RectF(w / 2 + pad, pad * 1.5f, w - pad * 1.5f, globeBottom), blue, "MANA")

            // The belt: a recessed bar with four slots across it.
            val bar = RectF(pad * 1.5f, topH + pad * 0.5f, w - pad * 1.5f, h - pad * 1.5f)
            fill.shader = null
            fill.color = Color.argb(150, 0, 0, 0)
            canvas.drawRect(bar, fill)
            bevel(canvas, bar, edge, raised = false)
            val gap = pad * 0.6f
            val size = min((bar.width() - gap * (SLOTS + 1)) / SLOTS, bar.height() - gap * 2)
            val totalW = size * SLOTS + gap * (SLOTS - 1)
            var left = bar.left + (bar.width() - totalW) / 2
            val top = bar.top + (bar.height() - size) / 2
            number.textSize = size * 0.2f
            for (i in 0 until SLOTS) {
                val dst = RectF(left, top, left + size, top + size)
                fill.color = Color.rgb(14, 11, 8)
                canvas.drawRect(dst, fill)
                if (b != null) {
                    val slotW = b.belt.width / SLOTS.toFloat()
                    val inset = size * 0.06f
                    val src = Rect((i * slotW).toInt(), 0, ((i + 1) * slotW).toInt(), b.belt.height)
                    val area = RectF(dst.left + inset, dst.top + inset, dst.right - inset, dst.bottom - inset)
                    canvas.drawBitmap(b.belt, src, fitted(src.width().toFloat(), src.height().toFloat(), area), picture)
                }
                if (i == pressed) {
                    fill.shader = RadialGradient(dst.centerX(), dst.centerY(), size * 0.7f,
                        Color.argb(140, 255, 200, 90), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(dst, fill)
                    fill.shader = null
                }
                bevel(canvas, dst, edge * 0.8f, raised = false)
                canvas.drawText("${i + 1}", dst.right - size * 0.13f, dst.bottom - size * 0.08f, number)
                slotRects[i].set(dst)
                left += size + gap
            }
        }

        /** A globe in its socket: a glow of its [colour] behind, a bronze ring, the [label] below. */
        private fun globe(canvas: Canvas, bitmap: Bitmap?, area: RectF, colour: Int, label: String) {
            val cx = area.centerX()
            val cy = area.centerY()
            val r = min(area.width(), area.height()) / 2
            fill.shader = RadialGradient(cx, cy, r * 1.25f,
                Color.argb(150, Color.red(colour), Color.green(colour), Color.blue(colour)), Color.TRANSPARENT,
                Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 1.25f, fill)
            fill.shader = null
            fill.color = Color.rgb(10, 8, 6)
            canvas.drawCircle(cx, cy, r, fill)
            if (bitmap != null) {
                canvas.save()
                canvas.clipPath(Path().apply { addCircle(cx, cy, r * 0.94f, Path.Direction.CW) })
                val square = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawBitmap(bitmap, null, cover(bitmap.width.toFloat(), bitmap.height.toFloat(), square), picture)
                canvas.restore()
            }
            ring(canvas, cx, cy, r)
            canvas.drawText(label, cx, area.bottom + title.textSize * 1.5f, title)
        }

        /** A bronze ring: bright on the upper left, dark below, with four studs. */
        private fun ring(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val ringW = r * 0.09f
            stroke.strokeWidth = ringW
            stroke.shader = SweepGradient(cx, cy, intArrayOf(bronze, bronzeDark, bronze, bronzeLight, bronze), null)
            canvas.drawCircle(cx, cy, r, stroke)
            stroke.shader = null
            stroke.strokeWidth = ringW * 0.25f
            stroke.color = Color.BLACK
            canvas.drawCircle(cx, cy, r + ringW / 2, stroke)
            canvas.drawCircle(cx, cy, r - ringW / 2, stroke)
            for (k in 0 until 4) {
                val a = Math.toRadians(45.0 + 90.0 * k)
                stud(canvas, cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat(), ringW * 0.7f)
            }
        }

        /** A bevelled bronze frame around [box], [w] wide: raised (light upper left) or sunken. */
        private fun bevel(canvas: Canvas, box: RectF, w: Float, raised: Boolean) {
            val half = w / 2
            stroke.shader = null
            stroke.strokeWidth = w
            stroke.color = if (raised) bronzeLight else bronzeDark
            canvas.drawLine(box.left, box.top + half, box.right, box.top + half, stroke)
            canvas.drawLine(box.left + half, box.top, box.left + half, box.bottom, stroke)
            stroke.color = if (raised) bronzeDark else bronzeLight
            canvas.drawLine(box.left, box.bottom - half, box.right, box.bottom - half, stroke)
            canvas.drawLine(box.right - half, box.top, box.right - half, box.bottom, stroke)
            stroke.strokeWidth = maxOf(1f, w * 0.2f)
            stroke.color = Color.BLACK
            canvas.drawRect(box, stroke)
        }

        private fun rivets(canvas: Canvas, box: RectF, r: Float) {
            val i = r * 1.6f
            stud(canvas, box.left + i, box.top + i, r)
            stud(canvas, box.right - i, box.top + i, r)
            stud(canvas, box.left + i, box.bottom - i, r)
            stud(canvas, box.right - i, box.bottom - i, r)
        }

        private fun stud(canvas: Canvas, x: Float, y: Float, r: Float) {
            fill.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r, bronzeLight, bronzeDark, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r, fill)
            fill.shader = null
        }

        /** A dark, uneven stone slab with torchlit middle, drawn once per size. */
        private fun stoneSlab(w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(24, 20, 16))
            val rnd = Random(0xD2L)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(260) {
                val x = rnd.nextFloat() * w
                val y = rnd.nextFloat() * h
                val r = (0.02f + rnd.nextFloat() * 0.08f) * w
                val v = 14 + rnd.nextInt(26)
                p.shader = RadialGradient(x, y, r, Color.argb(70, v + 6, v + 3, v), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, r, p)
            }
            p.shader = null
            repeat(w * h / 90) {
                val v = rnd.nextInt(60)
                p.color = Color.argb(60, v, v * 9 / 10, v * 3 / 4)
                c.drawPoint(rnd.nextFloat() * w, rnd.nextFloat() * h, p)
            }
            p.shader = RadialGradient(w / 2f, h * 0.35f, maxOf(w, h) * 0.75f,
                intArrayOf(Color.TRANSPARENT, Color.argb(200, 0, 0, 0)), floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            return bmp
        }

        /** [w] x [h] fitted inside [area], centred. */
        private fun fitted(w: Float, h: Float, area: RectF): RectF = scaled(w, h, area, min(area.width() / w, area.height() / h))

        /** [w] x [h] covering [area], centred (the caller clips the overflow). */
        private fun cover(w: Float, h: Float, area: RectF): RectF = scaled(w, h, area, maxOf(area.width() / w, area.height() / h))

        private fun scaled(w: Float, h: Float, area: RectF, s: Float): RectF {
            val l = area.left + (area.width() - w * s) / 2
            val t = area.top + (area.height() - h * s) / 2
            return RectF(l, t, l + w * s, t + h * s)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = slotRects.indexOfFirst { it.contains(event.x, event.y) }
                    if (pressed >= 0) press(pressed)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressed = -1
                    invalidate()
                }
            }
            return true
        }
    }
}
