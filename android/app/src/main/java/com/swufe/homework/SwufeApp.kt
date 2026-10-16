package com.swufe.homework

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.swufe.homework.reminder.Notifications
import com.swufe.homework.reminder.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SwufeApp : Application(), ImageLoaderFactory {
    /** 带证书固定的图片加载器（服务器自签证书），见 AppModule.imageLoader。 */
    @Inject lateinit var imageLoader: dagger.Lazy<ImageLoader>

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        SyncWorker.schedulePeriodic(this)
    }

    override fun newImageLoader(): ImageLoader = imageLoader.get()
}
