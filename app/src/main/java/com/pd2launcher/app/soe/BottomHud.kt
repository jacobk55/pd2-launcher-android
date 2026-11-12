package com.pd2launcher.app.soe

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.pd2launcher.app.Framebuffer
import com.pd2launcher.app.GameEnvironment
import com.pd2launcher.app.PresentServer
import com.pd2launcher.app.ScreenPixels
import com.pd2launcher.app.input.RelayEvent
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Bottom-screen HUD (Settings → Game): on a device with a second display Android lets apps
 * present on (the AYN Thor's lower screen), shows the life and mana tubes, the belt, and the
 * game's map there ([BottomHudView]), from the game's picture [FPS] times a second. A tap on a
 * belt slot presses that slot's key (1 to 4).
 *
 * What is read from the picture are rectangles of PD2's 1068 x 600 picture ([Part]), which D2GL
 * scales to fill the X screen. They can be tuned without a new build: `files/hud.json` in the
 * app's data (`{"life":[l,t,r,b], "mana":[...], "belt":[...], "map":[...]}`), read when the HUD
 * starts. Where the game's own buffer is on the screen ([PresentServer.readShown]) the parts are
 * read from it; otherwise from the X screen ([Framebuffer]).
 */
class BottomHud private constructor(private val presentation: HudPresentation) {
    fun dismiss() = presentation.stop()

    /** A rectangle of PD2's 1068 x 600 picture. */
    data class Part(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** The rectangles read from the picture. */
    data class Parts(val life: Part, val mana: Part, val belt: Part, val map: Part, val run: Part = RUN) {
        companion object {
            val DEFAULT = Parts(
                // Measured on the AYN Thor (1920 x 1080 X screen, PD2 with its minimap on).
                life = Part(33, 507, 109, 579),
                mana = Part(963, 507, 1039, 579),
                belt = Part(555, 559, 680, 594),
                map = Part(861, 17, 1061, 160),
                run = RUN,
            )

            /** [DEFAULT] with whatever [file] sets; a broken file is ignored, one part at a time. */
            fun load(file: File): Parts {
                if (!file.isFile) return DEFAULT
                return try {
                    val json = JSONObject(file.readText())
                    fun part(name: String, fallback: Part): Part {
                        val a = json.optJSONArray(name) ?: return fallback
                        if (a.length() != 4) return fallback
                        val p = Part(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
                        return if (p.width > 0 && p.height > 0) p else fallback
                    }
                    Parts(part("life", DEFAULT.life), part("mana", DEFAULT.mana), part("belt", DEFAULT.belt), part("map", DEFAULT.map), part("run", DEFAULT.run))
                } catch (e: Exception) {
                    Log.w(TAG, "hud.json", e)
                    DEFAULT
                }
            }
        }
    }

    companion object {
        private const val TAG = "pd2-bottomhud"

        /** The run/walk button on PD2's bar (measured on the AYN Thor), for the top screen's row. */
        internal val RUN = Part(389, 575, 405, 590)

        /** The newest frame while the HUD runs, for the top screen's row ([TopRow]); null otherwise. */
        @Volatile internal var latest: Frame? = null
        const val FPS = 15

        /** How often the HUD looks whether its screen is on again while it is off. */
        private const val OFF_POLL_MS = 500L
        internal const val PICTURE_WIDTH = 1068f
        internal const val PICTURE_HEIGHT = 600f
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
            val display = secondDisplay(activity)
            if (display == null) {
                Log.i(TAG, "no second display")
                return null
            }
            return try {
                val parts = Parts.load(File(activity.filesDir, "hud.json"))
                Log.i(TAG, "showing on display ${display.displayId} with $parts")
                val presentation = HudPresentation(activity, display, framebuffer, parts)
                presentation.show()
                BottomHud(presentation)
            } catch (e: Exception) {
                Log.w(TAG, "no presentation on display ${display.displayId}", e)
                null
            }
        }

        /** Drinks the potion in belt slot [slot] (0 to 3). */
        fun press(slot: Int) {
            tap(SLOT_KEYS.getOrNull(slot) ?: return)
        }

        /** Presses and releases X [keysym] in the game, off the main thread. */
        fun tap(keysym: Int) {
            val relay = GameEnvironment.input.value?.relay ?: return
            Thread {
                try {
                    relay.send(listOf(RelayEvent.Key(keysym, true), RelayEvent.Key(keysym, false)))
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

        /**
         * How full a globe is, 0 to 1: the share of its rows, counted up from the bottom, whose
         * middle third is mostly [red] (life) or blue (mana) liquid. The globe's empty top is dark.
         */
        internal fun level(globe: Bitmap, red: Boolean, row: IntArray): Float {
            val w = globe.width
            val h = globe.height
            if (w < 3 || h < 3) return 0f
            val from = w / 3
            val to = w - w / 3
            var filled = 0
            for (y in 0 until h) {
                globe.getPixels(row, 0, w, 0, y, w, 1)
                var hits = 0
                for (x in from until to) {
                    val c = row[x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val liquid = if (red) r > 70 && r > g * 2 && r > b * 2 else b > 70 && b > r * 1.5f && b > g * 1.2f
                    if (liquid) hits++
                }
                if (hits * 2 >= to - from) filled++
            }
            return filled.toFloat() / h
        }

        /** The X screen's words, 0xXXRRGGBB. */
        internal fun fromScreen(word: Int): Int = word or OPAQUE

        /** The game's buffer's words, R8G8B8A8 read little-endian: 0xAABBGGRR. */
        internal fun fromBuffer(word: Int): Int =
            OPAQUE or ((word and 0xFF) shl 16) or (word and 0xFF00) or ((word shr 16) and 0xFF)

        private const val OPAQUE = 0xFF000000.toInt()
    }

    private class HudPresentation(
        context: Context, display: Display, private val framebuffer: Framebuffer, private val parts: Parts,
    ) : Presentation(context, display) {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "pd2-bottomhud") }
        @Volatile private var running = true
        private lateinit var view: BottomHudView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view = BottomHudView(context)
            setContentView(view)
            worker.execute(::loop)
        }

        fun stop() {
            running = false
            latest = null
            TopRow.shown = false
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
            var sets = arrayOfNulls<Frame>(2)
            var next = 0
            var logged = 0L
            var memory: GameMemory? = null
            var lookedForGame = 0L
            var pictures: ItemPictures? = null
            var inventory: List<GameMemory.Item>? = null
            var inventoryRead = 0L
            var warmed: GameMemory.Automap? = null
            while (running) {
                // With the screens off (lid closed, power button) there is nothing to show: no
                // reading of the picture or the game's memory until they are on again.
                if (display.state != Display.STATE_ON) {
                    try {
                        Thread.sleep(OFF_POLL_MS)
                    } catch (_: InterruptedException) {
                        return
                    }
                    continue
                }
                val started = System.nanoTime()
                try {
                    if (pixels == null) pixels = framebuffer.map()
                    val screen = pixels
                    if (screen != null) {
                        val w = screen.header.width
                        val h = screen.header.height
                        if (sets[0]?.matches(w, h) != true) sets = arrayOf<Frame?>(Frame(w, h, parts), Frame(w, h, parts))
                        val f = sets[next]!!
                        next = 1 - next
                        val shown = PresentServer.current.value?.readShown { frame ->
                            f.fill(w, h, frame::pixel, ::fromBuffer)
                        }
                        if (shown == null) f.fill(w, h, screen::pixel, ::fromScreen)
                        // The exact numbers from the game's memory; the globes' look stands in without them.
                        val open = memory
                        if (open != null && !open.alive) {
                            open.close()
                            pictures = null
                            memory = null
                        }
                        if (memory == null && started - lookedForGame > 2_000_000_000L) {
                            lookedForGame = started
                            memory = GameMemory.open()
                            pictures = memory?.gameFolder?.let { ItemPictures.open(it) }
                        }
                        f.stats = memory?.stats()
                        f.automap = memory?.automap(started)
                        // The map's cell pictures, decoded here once each, not while drawing.
                        f.automap?.let { map ->
                            val p = pictures
                            if (p != null && map !== warmed) {
                                warmed = map
                                var i = 0
                                while (i < map.cells.size) { p.automapCell(map.cells[i]); i += 3 }
                            }
                        }
                        // The inventory, twice a second; its pictures decoded here, not while drawing.
                        if (started - inventoryRead > 500_000_000L) {
                            inventoryRead = started
                            inventory = memory?.inventory()
                            inventory?.forEach { pictures?.get(it.picture) }
                        }
                        f.inventory = inventory
                        // The top screen's row: PD2's controller binds, their icons and the panel, made here.
                        val binds = memory?.binds()
                        f.binds = binds
                        if (binds != null && pictures != null) binds.forEach { pictures?.skillIcon(it) }
                        TopRow.art(context.resources, pictures)
                        latest = f
                        f.pictures = pictures
                        if (started - logged > 10_000_000_000L) {
                            logged = started
                            Log.i(TAG, "screen ${w}x$h direct=${shown != null} life=${f.life} mana=${f.mana} stats=${f.stats?.let { "${it.life}/${it.maxLife} ${it.mana}/${it.maxMana} level ${it.level} str ${it.all[0]}" }} xp=${f.stats?.levelProgress}")
                        }
                        view.post { view.show(f) }
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

    /** One copy of the parts, at the X screen's scale, and the globes' levels read from them. */
    internal class Frame(private val screenWidth: Int, private val screenHeight: Int, private val parts: Parts) {
        private fun bitmap(part: Part) = Bitmap.createBitmap(
            maxOf(1, (part.width * screenWidth / PICTURE_WIDTH).toInt()),
            maxOf(1, (part.height * screenHeight / PICTURE_HEIGHT).toInt()),
            Bitmap.Config.ARGB_8888,
        )

        private val lifeGlobe = bitmap(parts.life)
        private val manaGlobe = bitmap(parts.mana)
        val belt = bitmap(parts.belt)
        val map = bitmap(parts.map)
        val run = bitmap(parts.run)
        @Volatile var life = 0f
        @Volatile var mana = 0f
        @Volatile var stats: GameMemory.Stats? = null
        @Volatile var automap: GameMemory.Automap? = null
        @Volatile var inventory: List<GameMemory.Item>? = null
        @Volatile var pictures: ItemPictures? = null
        @Volatile var binds: IntArray? = null
        private val row = IntArray(maxOf(screenWidth, 1))

        fun matches(w: Int, h: Int) = w == screenWidth && h == screenHeight

        fun fill(w: Int, h: Int, pixel: (Int, Int) -> Int, argb: (Int) -> Int) {
            copy(parts.life, w, h, lifeGlobe, row, pixel, argb)
            copy(parts.mana, w, h, manaGlobe, row, pixel, argb)
            copy(parts.belt, w, h, belt, row, pixel, argb)
            copy(parts.map, w, h, map, row, pixel, argb)
            copy(parts.run, w, h, run, row, pixel, argb)
            life = level(lifeGlobe, red = true, row = row)
            mana = level(manaGlobe, red = false, row = row)
        }

        /** Life, 0 to 1: exact from memory, or as the globe looks. */
        val lifeLevel: Float get() = stats?.let { if (it.maxLife > 0) it.life.toFloat() / it.maxLife else null } ?: life

        /** Mana, 0 to 1: exact from memory, or as the globe looks. */
        val manaLevel: Float get() = stats?.let { if (it.maxMana > 0) it.mana.toFloat() / it.maxMana else null } ?: mana
    }
}
