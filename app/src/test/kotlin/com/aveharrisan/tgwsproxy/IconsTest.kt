package com.aveharrisan.tgwsproxy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Значки как их покажет Android: иконка на рабочем столе (круг) и плитка в шторке. PNG в app/build/screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class IconsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun save(bmp: Bitmap, name: String) {
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun launcher() {
        val d = ctx.getDrawable(R.mipmap.ic_launcher)
        assertTrue("иконка должна быть адаптивной", d is AdaptiveIconDrawable)
        d as AdaptiveIconDrawable
        val size = 432
        // Круглая маска, как у большинства лаунчеров, и «квадрат со скруглением».
        for ((name, clip) in listOf("icon_circle" to { p: Path -> p.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) },
            "icon_squircle" to { p: Path -> p.addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 110f, 110f, Path.Direction.CW) })) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.parseColor("#FF303030"))
            val path = Path().also(clip)
            c.clipPath(path)
            // Видимая часть — 72 из 108 dp слоя: растягиваем слои так, чтобы 72 dp заняли весь кадр.
            val layer = (size * 108f / 72f).toInt()
            val off = (size - layer) / 2
            d.background.setBounds(off, off, off + layer, off + layer); d.background.draw(c)
            d.foreground.setBounds(off, off, off + layer, off + layer); d.foreground.draw(c)
            save(bmp, name)
        }
    }

    @Test
    fun tileAndNotification() {
        val glyph = ctx.getDrawable(R.drawable.ic_notification)!!.mutate()
        val size = 360
        for ((name, bg, fg) in listOf(
            Triple("tile_active", "#FF2F7BF5", Color.WHITE),
            Triple("tile_inactive", "#FF3C3C3C", Color.parseColor("#FFBDBDBD")),
        )) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.parseColor("#FF1E1E1E"))
            c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(bg) })
            glyph.setTint(fg); glyph.setTintMode(PorterDuff.Mode.SRC_IN)
            val g = (size * 0.46f).toInt(); val o = (size - g) / 2
            glyph.setBounds(o, o, o + g, o + g); glyph.draw(c)
            save(bmp, name)
        }
        // Маленький размер — как в строке состояния: должно читаться и в 24 dp.
        val small = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
        Canvas(small).apply { drawColor(Color.BLACK); glyph.setTint(Color.WHITE); glyph.setBounds(0, 0, 72, 72); glyph.draw(this) }
        save(small, "status_bar_24dp")
    }
}
