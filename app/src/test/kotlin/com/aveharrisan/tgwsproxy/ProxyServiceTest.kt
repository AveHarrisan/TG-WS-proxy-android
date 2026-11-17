package com.aveharrisan.tgwsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProxyServiceTest {
    @Before fun setup() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        Settings.init(ctx)
        val port = ServerSocket(0).use { it.localPort }
        Settings.save(AppSettings(port = port, secret = "00000000000000000000000000000000",
            cfProxy = false, poolSize = 0, wakeLock = false))
    }

    private fun drainLifecycle() {
        val field = ProxyService::class.java.getDeclaredField("lifecycle").apply { isAccessible = true }
        (field.get(null) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
    }

    @Test fun duplicateStartDoesNotOpenPortTwice() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        try {
            controller.get().onStartCommand(null, 0, 1)
            controller.get().onStartCommand(null, 0, 2)
            drainLifecycle()
            assertEquals(Status.RUNNING, ProxyState.status.value)
        } finally {
            controller.destroy()
            drainLifecycle()
        }
    }

    @Test fun replacementServiceWaitsForPreviousServiceToClosePort() {
        val old = Robolectric.buildService(ProxyService::class.java).create()
        old.get().onStartCommand(null, 0, 1)
        drainLifecycle()
        old.destroy()
        val replacement = Robolectric.buildService(ProxyService::class.java).create()
        try {
            replacement.get().onStartCommand(null, 0, 1)
            drainLifecycle()
            assertEquals(Status.RUNNING, ProxyState.status.value)
        } finally {
            replacement.destroy()
            drainLifecycle()
        }
    }
}
