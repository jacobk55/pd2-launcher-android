package com.pd2launcher.app.soe

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import com.pd2launcher.app.R
import com.pd2launcher.app.input.ControlsLayout
import java.io.File
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The top screen's bottom row, while [BottomHud] runs and the player keeps it on: over PD2's own
 * bar between its two skill buttons, one stone panel with PD2's ten controller skill binds in
 * sockets (A, B, Y, X, RB, LT+A, LT+B, LT+Y, LT+X, LT+RB, each its skill's icon and its button
 * badges; an empty socket for an unbound one), then a small stamina gauge with the game's own
 * run/walk button. PD2's own skill bar is turned off meanwhile ([applyPd2SkillBar]).
 *
 * The panel is put together once ([art]) from the row pieces (the player's own art, in the app)
 * and the resolution add-on's stone (from the player's game files, toned to the pieces' grey).
 * Everything is in the panel's own pixels ([ROW_W] x [ROW_H]), drawn scaled into [rect].
 */
object TopRow {
    private const val TAG = "pd2-toprow"

    /** Whether the row shows now (a character in the world, the setting on): taps on it are not the game's. */
    @Volatile var shown = false

    /** The panel's pixels: the row pieces' scale, 96 tall. */
    const val ROW_W = 1461
    const val ROW_H = 96

    /** Where the row lies in PD2's 1068 x 600 world picture: between the skill buttons, over the bar. */
    private const val PIC_LEFT = 165.8f
    private const val PIC_RIGHT = 902.2f
    private const val PIC_TOP = 552.4f
    private const val PIC_W = 1068f
    private const val PIC_H = 600f

    /** The row on a [width] x [height] X screen (or a view showing one), in its pixels. */
    fun rect(width: Float, height: Float): RectF {
        val world = ControlsLayout.worldPicture(width, height)
        val sx = world.width / PIC_W
        val sy = world.height / PIC_H
        return RectF(world.left + PIC_LEFT * sx, world.top + PIC_TOP * sy, world.left + PIC_RIGHT * sx, world.bottom)
    }

    /**
     * The crest over the spot where PD2 draws its own controller skill bar once a controller is
     * used (right above the row, over the scene): the row shows the binds, so that bar is covered.
     */
    private const val CREST_LEFT = 781.5f
    private const val CREST_RIGHT = 954.5f
    private const val CREST_TOP = 521.7f

    /** The crest on a [width] x [height] X screen (or a view showing one), in its pixels. */
    fun crestRect(width: Float, height: Float): RectF {
        val world = ControlsLayout.worldPicture(width, height)
        val sx = world.width / PIC_W
        val sy = world.height / PIC_H
        return RectF(world.left + CREST_LEFT * sx, world.top + CREST_TOP * sy, world.left + CREST_RIGHT * sx, world.top + (PIC_TOP + 1f) * sy)
    }

    /** Whether a touch at [x], [y] on a [width] x [height] X screen lands on the shown row or its crest. */
    fun blocks(x: Int, y: Int, width: Int, height: Int): Boolean {
        if (!shown) return false
        val w = width.toFloat()
        val h = height.toFloat()
        return rect(w, h).contains(x.toFloat(), y.toFloat()) || crestRect(w, h).contains(x.toFloat(), y.toFloat())
    }

    // ---------------------------------------------------------------- layout, in panel pixels

    private const val SLOTS = GameMemory.BIND_ENTRIES
    private const val BORDER_TOP = 8
    private const val BORDER_BOT = 88
    private const val LCAP_W = 47
    private const val RCAP_W = 47
    private const val STAM_W = 218
    private const val BIG = 1.2f

    private val stamX = ROW_W - RCAP_W - 20 - STAM_W
    private val areaL = LCAP_W + 10f
    private val areaR = stamX - 10f
    private val step = (areaR - areaL) / SLOTS

    /** The sockets' wells, where the icons go. */
    private val wells: List<RectF> = List(SLOTS) { i ->
        val sockW = (66 * BIG).roundToInt()
        val sockH = (68 * BIG).roundToInt()
        val x = (areaL + step * (i + 0.5f) - sockW / 2f).roundToInt()
        val top = BORDER_TOP + Math.floorDiv(BORDER_BOT - BORDER_TOP - sockH, 2)
        RectF(x + 10 * BIG, top + 10 * BIG, x + 56 * BIG, top + 57 * BIG)
    }
    private val button = RectF(stamX + 13f, 34f, stamX + 41f, 62f)
    private val trough = RectF(stamX + 58f, 42f, stamX + 208f, 53f)

    /** Each bind's badges: PD2's button pictures, LT first for the LT binds. */
    private val BADGES = listOf(
        intArrayOf(0), intArrayOf(1), intArrayOf(2), intArrayOf(3), intArrayOf(9),
        intArrayOf(12, 0), intArrayOf(12, 1), intArrayOf(12, 2), intArrayOf(12, 3), intArrayOf(12, 9),
    )

    // ---------------------------------------------------------------- the panel

    @Volatile private var panel: Bitmap? = null
    @Volatile private var crest: Bitmap? = null
    private var panelTried = false

    /** The panel if it is made already ([art]); never makes it. */
    fun panelOrNull(): Bitmap? = panel

    /** The panel, put together once (on the HUD's thread): null until the game's stone is readable. */
    internal fun art(resources: Resources, pictures: ItemPictures?): Bitmap? {
        panel?.let { return it }
        if (pictures == null) return null
        synchronized(this) {
            panel?.let { return it }
            if (panelTried) return null
            panelTried = true
            return try {
                compose(resources, pictures.stone).also { panel = it }
            } catch (e: Exception) {
                Log.w(TAG, "panel", e)
                null
            }
        }
    }

    private fun decode(resources: Resources, id: Int): Bitmap =
        BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false; inMutable = true })

    private fun compose(resources: Resources, stone: Bitmap?): Bitmap {
        val lcap = decode(resources, R.drawable.hud_row_lcap)
        val sock = decode(resources, R.drawable.hud_row_socket)
        val knot = decode(resources, R.drawable.hud_row_knot)
        val stam = decode(resources, R.drawable.hud_row_stamina)
        val rcap = decode(resources, R.drawable.hud_row_rcap)
        val out = Bitmap.createBitmap(ROW_W, ROW_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)

        // The stone: the game's, at the bar's scale, toned to the pieces' grey; or the pieces' own plain stone.
        var plainStone: Bitmap? = null
        if (stone != null) {
            // The bar's scale (X screen pixels per picture pixel) in the pieces' pixels (87 screen pixels to their 96).
            val k = (1920f / 1068f) / (87f / 96f)
            val tile = Bitmap.createScaledBitmap(stone, (stone.width * k).roundToInt(), (stone.height * k).roundToInt(), true)
            val strip = Bitmap.createBitmap(tile, 0, 40.coerceAtMost(tile.height - ROW_H), tile.width, ROW_H)
            toned(strip, stam)
            plainStone = strip
            var x = 0
            while (x < ROW_W) {
                c.drawBitmap(strip, x.toFloat(), 0f, null)
                x += strip.width
            }
        } else {
            val plain = Bitmap.createBitmap(stam, 150, 0, 40, ROW_H)
            var x = 0
            while (x < ROW_W) {
                c.drawBitmap(plain, x.toFloat(), 0f, null)
                x += plain.width
            }
        }
        // The gold borders, top and bottom, from the pieces.
        c.drawBitmap(stam, android.graphics.Rect(100, 0, 101, BORDER_TOP), RectF(0f, 0f, ROW_W.toFloat(), BORDER_TOP.toFloat()), null)
        c.drawBitmap(stam, android.graphics.Rect(100, BORDER_BOT, 101, ROW_H), RectF(0f, BORDER_BOT.toFloat(), ROW_W.toFloat(), ROW_H.toFloat()), null)
        c.drawBitmap(lcap, 0f, 0f, null)
        c.drawBitmap(rcap, (ROW_W - RCAP_W).toFloat(), 0f, null)

        val big = Bitmap.createBitmap(sock, 0, 14, sock.width, 68)
        val bigSock = feathered(Bitmap.createScaledBitmap(big, (66 * BIG).roundToInt(), (68 * BIG).roundToInt(), true), 4, 0, 82)
        val divider = feathered(Bitmap.createBitmap(knot, 17, BORDER_TOP, 28, BORDER_BOT - BORDER_TOP), 3, 0, BORDER_BOT - BORDER_TOP)
        val top = BORDER_TOP + Math.floorDiv(BORDER_BOT - BORDER_TOP - bigSock.height, 2)
        for (i in 0 until SLOTS) {
            val cx = areaL + step * (i + 0.5f)
            if (i > 0) c.drawBitmap(divider, (cx - step / 2 - divider.width / 2f).roundToInt().toFloat(), BORDER_TOP.toFloat(), p)
            c.drawBitmap(bigSock, (cx - bigSock.width / 2f).roundToInt().toFloat(), top.toFloat(), p)
        }
        c.drawBitmap(divider, (areaL - divider.width / 2f).roundToInt().toFloat(), BORDER_TOP.toFloat(), p)
        c.drawBitmap(divider, (areaR - divider.width / 2f).roundToInt().toFloat(), BORDER_TOP.toFloat(), p)
        c.drawBitmap(feathered(stam, 6, BORDER_TOP, BORDER_BOT), stamX.toFloat(), 0f, p)
        crest = composeCrest(plainStone, stam)
        return out
    }

    /**
     * The crest: the row's plain stone, the pieces' gold
     * border along its top and both sides, open at the bottom where it meets the row. Its size in
     * the panel's pixels is the crest's share of the picture at the row's scale.
     */
    private fun composeCrest(stone: Bitmap?, stam: Bitmap): Bitmap {
        val perUnit = ROW_H / (PIC_H - PIC_TOP)
        val w = ((CREST_RIGHT - CREST_LEFT) * perUnit).roundToInt()
        val h = ((PIC_TOP - CREST_TOP + 1f) * perUnit).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Stone: the row's own toned stone (or, without the game's, the stamina module's plain corner), tiled.
        val plain = stone ?: Bitmap.createBitmap(stam, 210, BORDER_TOP, 8, BORDER_BOT - BORDER_TOP)
        var x = 0
        while (x < w) {
            var y = 0
            while (y < h) {
                c.drawBitmap(plain, x.toFloat(), y.toFloat(), null)
                y += plain.height
            }
            x += plain.width
        }
        // The gold border: the pieces' top border along the top, the same turned down both sides.
        c.drawBitmap(stam, android.graphics.Rect(100, 0, 101, BORDER_TOP), RectF(0f, 0f, w.toFloat(), BORDER_TOP.toFloat()), null)
        val side = Bitmap.createBitmap(stam, 100, 0, 1, BORDER_TOP)
        val m = android.graphics.Matrix().apply { postRotate(-90f) }
        val turned = Bitmap.createBitmap(side, 0, 0, side.width, side.height, m, true)
        c.drawBitmap(turned, null, RectF(0f, 0f, BORDER_TOP.toFloat(), h.toFloat()), null)
        c.drawBitmap(turned, null, RectF((w - BORDER_TOP).toFloat(), 0f, w.toFloat(), h.toFloat()), null)
        return out
    }

    /** [strip] (mutable) toned in place to the grey of [pieces]' plain stone: its brightness spread and colour. */
    private fun toned(strip: Bitmap, pieces: Bitmap) {
        val target = IntArray(140 * 26)
        pieces.getPixels(target, 0, 140, 60, 10, 140, 26)
        var tr = 0.0; var tg = 0.0; var tb = 0.0
        for (px in target) { tr += Color.red(px); tg += Color.green(px); tb += Color.blue(px) }
        val n = target.size.toDouble()
        tr /= n; tg /= n; tb /= n
        val tLum = (tr + tg + tb) / 3
        var tVar = 0.0
        for (px in target) { val l = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3.0 - tLum; tVar += l * l }
        val tSd = sqrt(tVar / n)
        val px = IntArray(strip.width * strip.height)
        strip.getPixels(px, 0, strip.width, 0, 0, strip.width, strip.height)
        var m = 0.0
        for (v in px) m += (Color.red(v) + Color.green(v) + Color.blue(v)) / 3.0
        m /= px.size
        var sVar = 0.0
        for (v in px) { val l = (Color.red(v) + Color.green(v) + Color.blue(v)) / 3.0 - m; sVar += l * l }
        val sd = sqrt(sVar / px.size).coerceAtLeast(1.0)
        for (i in px.indices) {
            val v = px[i]
            val lum = ((Color.red(v) + Color.green(v) + Color.blue(v)) / 3.0 - m) / sd * tSd + tLum
            fun ch(t: Double) = (lum * t / tLum).roundToInt().coerceIn(0, 255)
            px[i] = Color.argb(255, ch(tr), ch(tg), ch(tb))
        }
        strip.setPixels(px, 0, strip.width, 0, 0, strip.width, strip.height)
    }

    /** [piece] with its left and right [edge] columns fading out between rows [from] and [to]. */
    private fun feathered(piece: Bitmap, edge: Int, from: Int, to: Int): Bitmap {
        val p = piece.copy(Bitmap.Config.ARGB_8888, true)
        val col = IntArray(1)
        for (i in 0 until edge) {
            val a = 255 * (i + 1) / (edge + 1)
            for (x in intArrayOf(i, p.width - 1 - i)) {
                for (y in from.coerceAtLeast(0) until to.coerceAtMost(p.height)) {
                    p.getPixels(col, 0, 1, x, y, 1, 1)
                    col[0] = (col[0] and 0x00FFFFFF) or ((Color.alpha(col[0]) * a / 255) shl 24)
                    p.setPixels(col, 0, 1, x, y, 1, 1)
                }
            }
        }
        return p
    }

    // ---------------------------------------------------------------- each frame

    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint()

    /**
     * The row into [into] on [canvas]: the [panel], each bound skill's icon and badges
     * ([binds]: skill ids, -1 unbound), the [run] button and the [stamina] gauge (0 to 1).
     */
    internal fun draw(
        canvas: Canvas, into: RectF, crestInto: RectF?, panel: Bitmap, binds: IntArray, pictures: ItemPictures,
        stamina: Float, run: Bitmap?,
    ) {
        val c = crest
        if (crestInto != null && c != null) canvas.drawBitmap(c, null, crestInto, smooth)
        canvas.save()
        canvas.translate(into.left, into.top)
        canvas.scale(into.width() / ROW_W, into.height() / ROW_H)
        canvas.drawBitmap(panel, 0f, 0f, smooth)
        for (i in 0 until minOf(SLOTS, binds.size)) {
            val icon = pictures.skillIcon(binds[i]) ?: continue
            val w = wells[i]
            val size = minOf(w.width(), w.height())
            val l = w.centerX() - size / 2
            val t = w.centerY() - size / 2
            canvas.drawBitmap(icon, null, RectF(l, t, l + size, t + size), smooth)
            val g = 15.5f
            var bx = l + size - BADGES[i].size * (g - 1)
            for (b in BADGES[i]) {
                pictures.badge(b)?.let { canvas.drawBitmap(it, null, RectF(bx, t + size - g + 2, bx + g, t + size + 2), smooth) }
                bx += g - 1
            }
        }
        run?.let { canvas.drawBitmap(it, null, button, smooth) }
        val s = stamina.coerceIn(0f, 1f)
        if (s > 0f) {
            fill.shader = LinearGradient(0f, trough.top, 0f, trough.bottom,
                intArrayOf(Color.rgb(232, 200, 110), Color.rgb(162, 120, 50)), null, Shader.TileMode.CLAMP)
            canvas.drawRect(trough.left, trough.top, trough.left + trough.width() * s, trough.bottom, fill)
            fill.shader = null
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------- PD2's own skill bar

    /**
     * PD2's own skill bar (`pd2_game_settings.skill_bar` in [settings], ProjectDiablo.json) off
     * while the row shows instead ([rowOn]); on again when the row is turned off, only if it was
     * this that turned it off ([marker] remembers). Read before the game starts: PD2 keeps it.
     */
    fun applyPd2SkillBar(settings: File, marker: File, rowOn: Boolean, log: (String) -> Unit) {
        try {
            if (!settings.isFile) return
            val json = JSONObject(settings.readText().removePrefix("﻿"))
            val game = json.optJSONObject("pd2_game_settings") ?: return
            val bar = game.optBoolean("skill_bar", true)
            when {
                rowOn && bar -> {
                    game.put("skill_bar", false)
                    settings.writeText(json.toString(4))
                    marker.parentFile?.mkdirs()
                    marker.writeText("1")
                    log("PD2's skill bar off: the top screen's row shows the binds")
                }
                !rowOn && !bar && marker.isFile -> {
                    game.put("skill_bar", true)
                    settings.writeText(json.toString(4))
                    marker.delete()
                    log("PD2's skill bar on again")
                }
            }
        } catch (e: Exception) {
            log("PD2's skill bar setting not changed: $e")
        }
    }
}
