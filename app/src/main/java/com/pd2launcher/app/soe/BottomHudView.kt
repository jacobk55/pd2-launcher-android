package com.pd2launcher.app.soe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.pd2launcher.app.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The lower screen of [BottomHud]: the angel and demon panel art (`hud_bg`, 1240 x 1080, drawn
 * for the AYN Thor's lower screen) filled live. The angel's red orb is life and the demon's blue
 * orb mana, their glass going dark above the level (`hud_orb_*_empty`); the plates under them give
 * the numbers. The five tabs switch the centre panel between the map and the character's pages;
 * the bar under it is experience, and the four slots at the bottom the belt (a tap drinks).
 */
internal class BottomHudView(context: Context) : View(context) {
    private var frame: BottomHud.Frame? = null
    private val art: Bitmap = decode(R.drawable.hud_bg)
    private val lifeEmpty = decode(R.drawable.hud_orb_life_empty)
    private val manaEmpty = decode(R.drawable.hud_orb_mana_empty)

    private val pixel = Paint().apply { isFilterBitmap = false }
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    /** The page the centre panel shows: an index into [TABS]. */
    private var tab = 0
    private var pressed = -1

    // Touch areas in the view's pixels, set as they are drawn.
    private val slotRects = Array(BottomHud.SLOTS) { RectF() }
    private val tabRects = Array(TABS.size) { RectF() }

    // The art's parts, in its 1240 x 1080 pixels (measured on the 1024 x 890 template, see [t]).
    private val lifeOrb = Orb(228.9f, 278.3f, 70.2f, 156f, 206f)
    private val manaOrb = Orb(1020.8f, 317.1f, 73.9f, 944f, 241f)
    private val tabWindows = arrayOf(
        t(337, 57, 399, 118), t(410, 57, 472, 118), t(483, 57, 545, 118), t(556, 57, 618, 118), t(628, 57, 690, 118),
    )
    private val panel = t(362, 156, 662, 660)
    private val lifePlate = t(40, 630, 256, 701)
    private val manaPlate = t(770, 630, 988, 701)
    private val xpWindow = t(272, 742, 758, 770)
    private val slotWindows = arrayOf(t(366, 795, 432, 862), t(441, 795, 507, 862), t(517, 795, 583, 862), t(593, 795, 659, 862))

    /** An orb: its glass's centre and radius, and where its empty glass picture goes. */
    private class Orb(val cx: Float, val cy: Float, val r: Float, val left: Float, val top: Float)

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
        val stats = f?.stats
        if (f != null) {
            drain(canvas, lifeOrb, lifeEmpty, f.lifeLevel)
            drain(canvas, manaOrb, manaEmpty, f.manaLevel)
            text.textSize = 34f
            val lifeText = stats?.let { "${it.life} / ${it.maxLife}" } ?: "${(f.lifeLevel * 100).roundToInt()}%"
            val manaText = stats?.let { "${it.mana} / ${it.maxMana}" } ?: "${(f.manaLevel * 100).roundToInt()}%"
            label(canvas, "LIFE  $lifeText", lifePlate.centerX(), lifePlate.centerY() + 12f, Color.rgb(240, 110, 90))
            label(canvas, "MANA  $manaText", manaPlate.centerX(), manaPlate.centerY() + 12f, Color.rgb(130, 160, 255))

            // Experience.
            val xp = stats?.levelProgress
            if (xp != null && xp > 0f) {
                val right = xpWindow.left + xpWindow.width() * xp
                fill.color = Color.WHITE // opaque: the shader takes the paint's alpha
                fill.shader = LinearGradient(0f, xpWindow.top, 0f, xpWindow.bottom,
                    intArrayOf(Color.rgb(250, 226, 150), Color.rgb(206, 166, 64), Color.rgb(150, 112, 36)),
                    floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(xpWindow.left, xpWindow.top, right, xpWindow.bottom, fill)
                fill.shader = null
            }
            if (stats != null) {
                text.textSize = 26f
                val percent = xp?.let { "   ${(it * 1000).roundToInt() / 10f}%" } ?: ""
                label(canvas, "LEVEL ${stats.level}$percent", xpWindow.centerX(), xpWindow.centerY() + 9f, Color.rgb(255, 244, 214))
            }

            // The belt, from the game's picture.
            val slotW = f.belt.width / BottomHud.SLOTS.toFloat()
            for (i in 0 until BottomHud.SLOTS) {
                val dst = RectF(slotWindows[i]).apply { inset(6f, 6f) }
                val src = Rect((i * slotW).toInt(), 0, ((i + 1) * slotW).toInt(), f.belt.height)
                canvas.drawBitmap(f.belt, src, fitted(src.width().toFloat(), src.height().toFloat(), dst), smooth)
            }
        }

        // The centre panel's page.
        canvas.save()
        canvas.clipRect(panel)
        when (tab) {
            0 -> {
                val automap = f?.automap
                if (automap != null) drawAutomap(canvas, automap)
                else if (f != null) canvas.drawBitmap(f.map, null, fitted(f.map.width.toFloat(), f.map.height.toFloat(), panel), smooth)
            }
            1 -> drawInventory(canvas, f)
            2 -> drawCharacter(canvas, stats)
            else -> {
                text.textSize = 30f
                label(canvas, TAB_TITLES[tab], panel.centerX(), panel.centerY() - 10f, Color.rgb(226, 206, 156))
                text.textSize = 22f
                label(canvas, "coming in a later build", panel.centerX(), panel.centerY() + 26f, Color.rgb(150, 138, 116))
            }
        }
        canvas.restore()

        // Tabs.
        for (i in TABS.indices) {
            val w = tabWindows[i]
            if (i == tab) {
                fill.color = Color.argb(70, 200, 150, 60)
                canvas.drawRect(w.left + 5f, w.top + 5f, w.right - 5f, w.bottom - 5f, fill)
                line.color = Color.rgb(230, 190, 110)
                line.strokeWidth = 3f
                canvas.drawRect(w.left + 5f, w.top + 5f, w.right - 5f, w.bottom - 5f, line)
            }
            text.textSize = if (TABS[i].length > 4) 17f else 20f
            label(canvas, TABS[i], w.centerX(), w.centerY() + 7f,
                if (i == tab) Color.rgb(240, 218, 166) else Color.rgb(170, 156, 130))
            tabRects[i].set(ox + w.left * s, oy + w.top * s, ox + w.right * s, oy + w.bottom * s)
        }

        // Slots: the pressed one lit, and their keys.
        for (i in 0 until BottomHud.SLOTS) {
            val dst = slotWindows[i]
            if (i == pressed) {
                fill.color = Color.argb(110, 255, 230, 160)
                canvas.drawRect(dst, fill)
            }
            text.textSize = 20f
            label(canvas, "${i + 1}", dst.right - 12f, dst.bottom - 10f, Color.rgb(236, 220, 180))
            slotRects[i].set(ox + dst.left * s, oy + dst.top * s, ox + dst.right * s, oy + dst.bottom * s)
        }
        canvas.restore()
    }

    /** [empty] glass over [orb] above [level] (0 to 1), and a faint line at the level. */
    private fun drain(canvas: Canvas, orb: Orb, empty: Bitmap, level: Float) {
        val l = level.coerceIn(0f, 1f)
        val y = orb.cy + orb.r - 2 * orb.r * l
        if (l < 1f) {
            canvas.save()
            canvas.clipRect(orb.left, orb.top, orb.left + empty.width, y)
            canvas.drawBitmap(empty, orb.left, orb.top, pixel)
            canvas.restore()
        }
        if (l > 0f && l < 1f) {
            val half = kotlin.math.sqrt((orb.r * orb.r - (y - orb.cy) * (y - orb.cy)).coerceAtLeast(0f))
            line.color = Color.argb(110, 255, 255, 255)
            line.strokeWidth = 2f
            canvas.drawLine(orb.cx - half, y, orb.cx + half, y, line)
        }
    }

    /**
     * The inventory page: the grid (10 x 8, more if an item lies beyond it) with each item's own
     * picture from the game's archives at its place; an item without one shows its code.
     */
    private fun drawInventory(canvas: Canvas, f: BottomHud.Frame?) {
        val items = f?.inventory
        if (items == null) {
            text.textSize = 24f
            label(canvas, "reading the inventory", panel.centerX(), panel.centerY(), Color.rgb(150, 138, 116))
            return
        }
        val cols = maxOf(INV_COLS, items.maxOfOrNull { it.x + it.width } ?: 0)
        val rows = maxOf(INV_ROWS, items.maxOfOrNull { it.y + it.height } ?: 0)
        val cell = min((panel.width() - 20f) / cols, 44f)
        val left = panel.centerX() - cell * cols / 2
        val top = panel.top + 70f
        text.textSize = 28f
        label(canvas, "INVENTORY", panel.centerX(), panel.top + 44f, Color.rgb(226, 206, 156))
        fill.color = Color.argb(150, 6, 6, 10)
        canvas.drawRect(left, top, left + cell * cols, top + cell * rows, fill)
        line.color = Color.argb(90, 150, 130, 100)
        line.strokeWidth = 1f
        for (c in 0..cols) canvas.drawLine(left + c * cell, top, left + c * cell, top + rows * cell, line)
        for (r in 0..rows) canvas.drawLine(left, top + r * cell, left + cols * cell, top + r * cell, line)
        val pictures = f.pictures
        for (item in items) {
            val box = RectF(left + item.x * cell, top + item.y * cell, left + (item.x + item.width) * cell, top + (item.y + item.height) * cell)
            fill.color = Color.argb(70, 70, 80, 140)
            canvas.drawRect(box.left + 1f, box.top + 1f, box.right - 1f, box.bottom - 1f, fill)
            val picture = pictures?.get(item.picture)
            if (picture != null) {
                canvas.drawBitmap(picture, null, fitted(picture.width.toFloat(), picture.height.toFloat(), box), smooth)
            } else {
                text.textSize = 16f
                label(canvas, item.code, box.centerX(), box.centerY() + 6f, Color.rgb(220, 210, 190))
            }
        }
        text.textSize = 22f
        val gold = f.stats?.all?.get(14)
        if (gold != null) label(canvas, "Gold  $gold", panel.centerX(), top + rows * cell + 40f, Color.rgb(232, 200, 110))
    }

    /** The character page: the game's numbers for the character, from its memory. */
    private fun drawCharacter(canvas: Canvas, stats: GameMemory.Stats?) {
        if (stats == null) {
            text.textSize = 24f
            label(canvas, "waiting for the game", panel.centerX(), panel.centerY(), Color.rgb(150, 138, 116))
            return
        }
        fun v(id: Int) = stats.all[id] ?: 0L
        val rows = listOf(
            "Level" to "${stats.level}",
            "Experience" to "${stats.experience}",
            "" to "",
            "Strength" to "${v(0)}",
            "Dexterity" to "${v(2)}",
            "Vitality" to "${v(3)}",
            "Energy" to "${v(1)}",
            "" to "",
            "Life" to "${stats.life} / ${stats.maxLife}",
            "Mana" to "${stats.mana} / ${stats.maxMana}",
            "" to "",
            "Fire resist" to "${v(39).toInt()}%",
            "Cold resist" to "${v(43).toInt()}%",
            "Lightning resist" to "${v(41).toInt()}%",
            "Poison resist" to "${v(45).toInt()}%",
            "" to "",
            "Gold" to "${v(14)}",
            "Gold in stash" to "${v(15)}",
        )
        text.textSize = 25f
        var y = panel.top + 50f
        for ((name, value) in rows) {
            if (name.isNotEmpty()) {
                text.textAlign = Paint.Align.LEFT
                label(canvas, name, panel.left + 24f, y, Color.rgb(198, 182, 146))
                text.textAlign = Paint.Align.RIGHT
                label(canvas, value, panel.right - 24f, y, Color.rgb(246, 236, 210))
                y += 32f
            } else {
                y += 14f
            }
        }
        text.textAlign = Paint.Align.CENTER
    }

    /**
     * The automap in the panel: [MAP_SCALE] screen pixels per automap pixel, the player at the
     * centre. Floors are dim squares, walls bright ones, as the game's own automap reads; the
     * player is a red diamond.
     */
    private fun drawAutomap(canvas: Canvas, map: GameMemory.Automap) {
        val cx = panel.centerX()
        val cy = panel.centerY()
        fun plot(cells: IntArray, colour: Int, size: Float) {
            fill.color = colour
            var i = 0
            while (i + 1 < cells.size) {
                val x = cx + (cells[i] + CELL_CENTRE_X - map.playerX) * MAP_SCALE
                val y = cy + (cells[i + 1] + CELL_CENTRE_Y - map.playerY) * MAP_SCALE
                if (x > panel.left - size && x < panel.right && y > panel.top - size && y < panel.bottom) {
                    canvas.drawRect(x, y, x + size, y + size, fill)
                }
                i += 2
            }
        }
        plot(map.floors, Color.rgb(70, 64, 60), 5f)
        plot(map.walls, Color.rgb(226, 208, 150), 4f)
        fill.color = Color.rgb(235, 50, 35)
        val d = 9f
        val marker = Path().apply {
            moveTo(cx, cy - d); lineTo(cx + d, cy); lineTo(cx, cy + d); lineTo(cx - d, cy); close()
        }
        canvas.drawPath(marker, fill)
    }

    private fun label(canvas: Canvas, s: String, x: Float, y: Float, colour: Int) {
        text.color = Color.rgb(8, 6, 6)
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
                val t = tabRects.indexOfFirst { it.contains(event.x, event.y) }
                if (t >= 0) {
                    tab = t
                } else {
                    pressed = slotRects.indexOfFirst { it.contains(event.x, event.y) }
                    if (pressed >= 0) BottomHud.press(pressed)
                }
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

        /** The template is 1024 x 890; the art is it scaled by this, 1 pixel down. */
        const val K = 1240f / 1024f
        const val TOP = 1f

        /** A rectangle of the 1024 x 890 template, in the art's pixels. */
        fun t(l: Int, t: Int, r: Int, b: Int) = RectF(l * K, t * K + TOP, r * K, b * K + TOP)

        const val INV_COLS = 10
        const val INV_ROWS = 8

        val TABS = listOf("MAP", "INV", "CHAR", "SKILL", "QUEST")
        val TAB_TITLES = listOf("Map", "Inventory", "Character", "Skills", "Quests")

        /** Screen pixels per automap pixel in the map window. */
        const val MAP_SCALE = 1.0f

        /** A cell's position is its picture's corner; its middle is about this far in. */
        const val CELL_CENTRE_X = 8f
        const val CELL_CENTRE_Y = 8f
        val OUTLINE = listOf(2f to 2f, 2f to 0f, 0f to 2f, -2f to 0f, 0f to -2f)
    }
}
