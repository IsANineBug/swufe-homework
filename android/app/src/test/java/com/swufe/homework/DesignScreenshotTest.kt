package com.swufe.homework

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.swufe.homework.ui.common.DesignShowcase
import com.swufe.homework.ui.theme.SwufeTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 渲染 README「视觉规范」的浅色/深色截图。只在传了 -Pscreenshots=<目录> 时运行：
 * ./gradlew testDebugUnitTest --tests '*DesignScreenshotTest' -Pscreenshots=screenshots（输出到 android/app/screenshots，再复制到 android/screenshots）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h1580dp-xhdpi")
class DesignScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(dark: Boolean, name: String) {
        val dir = System.getProperty("swufe.screenshots")
        assumeTrue("未传 -Pscreenshots，跳过截图", dir != null)
        // 页面里有无限动画（进度圈、骨架屏），关掉自动推进时钟，手动推进一点再截
        rule.mainClock.autoAdvance = false
        rule.setContent { SwufeTheme(dark = dark) { DesignShowcase() } }
        rule.mainClock.advanceTimeBy(400)
        // 直接用软件 Canvas 画 View（captureToImage 在慢机器上等重绘会超时）
        lateinit var bmp: Bitmap
        rule.runOnUiThread {
            val v = rule.activity.findViewById<View>(android.R.id.content)
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
        }
        File(dir!!).mkdirs()
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun light() = shoot(dark = false, "design-light.png")

    @Test fun dark() = shoot(dark = true, "design-dark.png")
}
