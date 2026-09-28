package com.aveharrisan.tgwsproxy

import android.Manifest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Первый запуск: никаких системных окон поверх ещё не нарисованного экрана. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {
    @Test
    fun launchDoesNotAskNotificationPermission() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull("разрешение спрошено при запуске", shadowOf(activity).lastRequestedPermission)
        assertTrue(activity.window.decorView.isShown)
        // Разрешения нет — значит, карточка «Разрешить» на экране будет.
        assertTrue(activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
}
