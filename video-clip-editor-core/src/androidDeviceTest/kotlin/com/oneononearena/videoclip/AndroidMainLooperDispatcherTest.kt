package com.oneononearena.videoclip

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMainLooperDispatcherTest {
    @Test
    fun runs_work_on_main_looper_from_background_caller() = runTest {
        withContext(Dispatchers.Default) {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            AndroidMainLooperDispatcher.run {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
            }
        }
    }
}
