package com.pd2launcher.app.soe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.pd2launcher.app.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The lower screen of [BottomHud]: the stone panel art (`hud_bg`, 1240 x 1080, drawn for the
 * AYN Thor's lower screen) with its windows filled live. The life and mana tubes fill to the
 * game's globes' levels with the art's own liquids, the four belt slots show the game's belt,
 * and the map window shows the game's map. The art is scaled to fit the display, its pixels kept
 * hard.
 */
internal class BottomHudView(context: Context) : View(context) {
    private var frame: BottomHud.Frame? = null
    private val art: Bitmap = decode(R.drawable.hud_bg)
    private val lifeLiquid = decode(R.drawable.hud_liquid_life)
    private val manaLiquid = decode(R.drawable.hud_liquid_mana)
    private val xpLiquid = decode(R.drawable.hud_liquid_xp)

    private val pixel = Paint().apply { isFilterBitmap = false }
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val slotRects = Array(BottomHud.SLOTS) { RectF() }
    private var pressed = -1

    // Where the art's windows are, in its 1240 x 1080 pixels.
    private val lifeWindow = RectF(116f, 341f, 213f, 973f)
    private val manaWindow = RectF(1024f, 481f, 1124f, 980f)
    private val xpWindow = RectF(358f, 936f, 877f, 1009f)
    private val mapWindow = RectF(392f, 266f, 845f, 818f)
    private val slotWindows = arrayOf(
        RectF(419f, 68f, 509f, 150f), RectF(523f, 68f, 613f, 150f),
        RectF(627f, 68f, 717f, 150f), RectF(731f, 68f, 823f, 150f),
    )

    private fun decode(id: Int): Bitmap =
        BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })

    fun show(next: BottomHud.Frame) {
        frame = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        // The art, scaled to fit, centred.
        val s = min(width / ART_W, height / ART_H)
        val ox = (width - ART_W * s) / 2
        val oy = (height - ART_H * s) / 2
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        canvas.drawBitmap(art, 0f, 0f, pixel)

        val f = frame
        if (f != null) {
            pour(canvas, lifeLiquid, lifeWindow, f.lifeLevel, vertical = true)
            pour(canvas, manaLiquid, manaWindow, f.manaLevel, vertical = true)
            // The experience bar: its glass first (over the art's own gold edges), then the fill.
            glass(canvas, RectF(xpWindow.left, xpWindow.top - 8f, xpWindow.right, xpWindow.bottom + 8f))
            val stats = f.stats
            stats?.levelProgress?.let { pour(canvas, xpLiquid, xpWindow, it, vertical = false) }

            // The map: the game's explored automap from its memory, centred on the player; the
            // game's own minimap from the picture only when the memory cannot be read.
            fill.color = Color.rgb(8, 8, 10)
            canvas.drawRect(mapWindow, fill)
            val automap = f.automap
            if (automap != null) drawAutomap(canvas, automap)
            else canvas.drawBitmap(f.map, null, fitted(f.map.width.toFloat(), f.map.height.toFloat(), mapWindow), smooth)

            text.textSize = 26f
            val lifeText = stats?.let { "${it.life}/${it.maxLife}" } ?: "${(f.lifeLevel * 100).roundToInt()}%"
            val manaText = stats?.let { "${it.mana}/${it.maxMana}" } ?: "${(f.manaLevel * 100).roundToInt()}%"
            label(canvas, "LIFE $lifeText", mapWindow.left + 110f, mapWindow.top + 34f, Color.rgb(255, 120, 96))
            label(canvas, "MANA $manaText", mapWindow.right - 110f, mapWindow.top + 34f, Color.rgb(140, 170, 255))
            if (stats != null) {
                val xp = stats.levelProgress?.let { " ${(it * 1000).roundToInt() / 10f}%" } ?: ""
                text.textSize = 24f
                label(canvas, "LEVEL ${stats.level}$xp", xpWindow.centerX(), xpWindow.centerY() + 9f, Color.rgb(255, 240, 200))
            }

            val slotW = f.belt.width / BottomHud.SLOTS.toFloat()
            for (i in 0 until BottomHud.SLOTS) {
                val dst = slotWindows[i]
                fill.color = Color.rgb(10, 10, 12)
                canvas.drawRect(dst, fill)
                val src = Rect((i * slotW).toInt(), 0, ((i + 1) * slotW).toInt(), f.belt.height)
                canvas.drawBitmap(f.belt, src, fitted(src.width().toFloat(), src.height().toFloat(), dst), smooth)
            }
        }
        for (i in 0 until BottomHud.SLOTS) {
            val dst = slotWindows[i]
            if (i == pressed) {
                fill.color = Color.argb(110, 255, 230, 160)
                canvas.drawRect(dst, fill)
            }
            text.textSize = 22f
            label(canvas, "${i + 1}", dst.right - 10f, dst.bottom - 8f, Color.rgb(232, 220, 188))
            slotRects[i].set(ox + dst.left * s, oy + dst.top * s, ox + dst.right * s, oy + dst.bottom * s)
        }
        canvas.restore()
    }

    /**
     * The automap in [mapWindow]: [MAP_SCALE] screen pixels per automap pixel, the player at the
     * centre. Floors are dim squares, walls bright ones, as the game's own automap reads; the
     * player is a red diamond.
     */
    private fun drawAutomap(canvas: Canvas, map: GameMemory.Automap) {
        canvas.save()
        canvas.clipRect(mapWindow)
        val cx = mapWindow.centerX()
        val cy = mapWindow.centerY() + 20f
        fun plot(cells: IntArray, colour: Int, size: Float) {
            fill.color = colour
            var i = 0
            while (i + 1 < cells.size) {
                val x = cx + (cells[i] + CELL_CENTRE_X - map.playerX) * MAP_SCALE
                val y = cy + (cells[i + 1] + CELL_CENTRE_Y - map.playerY) * MAP_SCALE
                if (x > mapWindow.left - size && x < mapWindow.right && y > mapWindow.top - size && y < mapWindow.bottom) {
                    canvas.drawRect(x, y, x + size, y + size, fill)
                }
                i += 2
            }
        }
        plot(map.floors, Color.rgb(58, 56, 64), 5f)
        plot(map.walls, Color.rgb(222, 206, 150), 4f)
        fill.color = Color.rgb(235, 50, 35)
        val d = 9f
        val marker = android.graphics.Path().apply {
            moveTo(cx, cy - d); lineTo(cx + d, cy); lineTo(cx, cy + d); lineTo(cx - d, cy); close()
        }
        canvas.drawPath(marker, fill)
        canvas.restore()
    }

    /** Empty dark glass over [box]. */
    private fun glass(canvas: Canvas, box: RectF) {
        fill.shader = android.graphics.LinearGradient(0f, box.top, 0f, box.bottom,
            intArrayOf(Color.rgb(6, 6, 8), Color.rgb(34, 36, 42), Color.rgb(16, 17, 21), Color.rgb(5, 5, 6)),
            floatArrayOf(0f, 0.25f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(box, fill)
        fill.shader = null
    }

    /** [liquid] in [window], filled from the bottom (or the left) to [level], with a bright edge. */
    private fun pour(canvas: Canvas, liquid: Bitmap, window: RectF, level: Float, vertical: Boolean) {
        val l = level.coerceIn(0f, 1f)
        if (l <= 0f) return
        canvas.save()
        if (vertical) canvas.clipRect(window.left, window.bottom - window.height() * l, window.right, window.bottom)
        else canvas.clipRect(window.left, window.top, window.left + window.width() * l, window.bottom)
        canvas.drawBitmap(liquid, null, window, pixel)
        canvas.restore()
        fill.color = Color.argb(90, 255, 255, 255)
        if (vertical) {
            val y = window.bottom - window.height() * l
            canvas.drawRect(window.left, y, window.right, y + 4f, fill)
        }
    }

    private fun label(canvas: Canvas, s: String, x: Float, y: Float, colour: Int) {
        text.color = Color.rgb(14, 14, 22)
        for ((dx, dy) in OUTLINE) canvas.drawText(s, x + dx, y + dy, text)
        text.color = colour
        canvas.drawText(s, x, y, text)
    }

    private fun fitted(w: Float, h: Float, area: RectF): RectF {
        val k = min(area.width() / w, area.height() / h)
        val l = area.left + (area.width() - w * k) / 2
        val t = area.top + (area.height() - h * k) / 2
        return RectF(l, t, l + w * k, t + h * k)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = slotRects.indexOfFirst { it.contains(event.x, event.y) }
                if (pressed >= 0) BottomHud.press(pressed)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val ART_W = 1240f
        const val ART_H = 1080f

        /** Screen pixels per automap pixel in the map window. */
        const val MAP_SCALE = 0.75f

        /** A cell's position is its picture's corner; its middle is about this far in. */
        const val CELL_CENTRE_X = 8f
        const val CELL_CENTRE_Y = 8f
        val OUTLINE = listOf(3f to 3f, 3f to 0f, 0f to 3f, -3f to 0f, 0f to -3f)
    }
}
