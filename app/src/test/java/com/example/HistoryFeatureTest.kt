package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.MediaType
import com.example.data.StatusItem
import com.example.data.StatusSource
import com.example.data.history.HistoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HistoryFeatureTest {

    @Test
    fun `remaining duration formats days hours minutes and expiry cleanly`() {
        val now = 1_700_000_000_000L
        val item6Days = StatusItem(
            id = "1",
            uri = Uri.EMPTY,
            name = "photo.jpg",
            mimeType = "image/jpeg",
            mediaType = MediaType.IMAGE,
            sizeBytes = 1024L,
            lastModifiedMillis = now,
            source = StatusSource.HISTORY_7D,
            expiresAtMillis = now + TimeUnit.DAYS.toMillis(6) + TimeUnit.HOURS.toMillis(5)
        )
        assertEquals("6d 5h left", item6Days.formattedRemainingDuration(now))
        assertFalse(item6Days.isExpiringSoon(now))

        val itemExpiringSoon = item6Days.copy(
            expiresAtMillis = now + TimeUnit.HOURS.toMillis(4) + TimeUnit.MINUTES.toMillis(30)
        )
        assertEquals("4h 30m left", itemExpiringSoon.formattedRemainingDuration(now))
        assertTrue(itemExpiringSoon.isExpiringSoon(now))

        val itemExpired = item6Days.copy(expiresAtMillis = now - 1000L)
        assertEquals("Expired", itemExpired.formattedRemainingDuration(now))
    }

    @Test
    fun `syncViewedStatuses caches files and purgeExpired deletes after 7 days`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = HistoryRepository(context)
        repo.clearAllHistory()

        val tempSource = File(context.cacheDir, "sample_status.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        }

        val liveItem = StatusItem(
            id = "wa:sample_status.jpg",
            uri = Uri.fromFile(tempSource),
            name = "sample_status.jpg",
            mimeType = "image/jpeg",
            mediaType = MediaType.IMAGE,
            sizeBytes = tempSource.length(),
            lastModifiedMillis = System.currentTimeMillis(),
            source = StatusSource.WHATSAPP
        )

        val archivedCount = repo.syncViewedStatuses(listOf(liveItem))
        assertEquals(1, archivedCount)

        // Duplicate sync should not create duplicates
        val secondArchived = repo.syncViewedStatuses(listOf(liveItem))
        assertEquals(0, secondArchived)

        val activeList = repo.observeHistoryItems(emptySet()).first()
        assertEquals(1, activeList.size)
        assertEquals("sample_status.jpg", activeList.first().name)

        // Advancing time past 7 days triggers automatic purge
        val eightDaysLater = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(8)
        val purged = repo.purgeExpired(nowMillis = eightDaysLater)
        assertEquals(1, purged)

        val afterPurge = repo.observeHistoryItems(emptySet()).first()
        assertTrue(afterPurge.isEmpty())
        tempSource.delete()
    }
}
