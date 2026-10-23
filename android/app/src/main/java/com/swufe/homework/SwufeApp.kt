package com.swufe.homework

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.swufe.homework.reminder.AiJobNotifier
import com.swufe.homework.reminder.Notifications
import com.swufe.homework.reminder.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SwufeApp : Application(), ImageLoaderFactory {
    /** 带证书固定的图片加载器（服务器自签证书），见 AppModule.imageLoader。 */
    @Inject lateinit var imageLoader: dagger.Lazy<ImageLoader>
    /** 后台识图：启动时恢复未完成的任务，App 在后台时识别结束发通知。 */
    @Inject lateinit var aiJobNotifier: AiJobNotifier

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        SyncWorker.schedulePeriodic(this)
        aiJobNotifier.start()
    }

    override fun newImageLoader(): ImageLoader = imageLoader.get()
}
