package com.mggx.pccontrol.next.pairing

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrGenerationTest {
    @Test fun validLargeQrUsesOneBulkPixelBuffer() {
        val buffer = encodeQrPixels("mggx://pair/v1?payload=test", 720)
        assertEquals(720, buffer.width)
        assertEquals(720, buffer.height)
        assertEquals(720 * 720, buffer.pixels.size)
        assertTrue(buffer.pixels.any { it == android.graphics.Color.BLACK })
        assertTrue(buffer.pixels.any { it == android.graphics.Color.WHITE })
    }

    @Test fun heavyEncodingRunsOnProvidedWorkerDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "qr-generation-worker") }
        executor.asCoroutineDispatcher().use { dispatcher ->
            var encodingThread = ""
            val result = generateQrPixels("mggx://pair/v1?payload=test", 256, dispatcher) { payload, size ->
                encodingThread = Thread.currentThread().name
                encodeQrPixels(payload, size)
            }
            assertEquals(256 * 256, result.pixels.size)
            assertTrue(encodingThread.startsWith("qr-generation-worker"))
        }
    }
}
