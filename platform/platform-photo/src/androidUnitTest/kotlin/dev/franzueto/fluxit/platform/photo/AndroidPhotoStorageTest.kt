package dev.franzueto.fluxit.platform.photo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidPhotoStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = AndroidPhotoStorage(context)

    @Test
    fun write_then_read_round_trips_the_bytes() =
        runBlocking {
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            val path = storage.write(bytes, "image/jpeg")
            assertTrue(path.startsWith("photos/"))
            assertTrue(path.endsWith(".jpg"))
            assertTrue(storage.read(path)!!.contentEquals(bytes))
        }

    @Test
    fun resolveAbsolute_points_inside_the_app_files_photos_dir() {
        val abs = storage.resolveAbsolute("photos/foo.jpg")
        val expectedRoot = File(context.filesDir, "photos").absolutePath
        assertTrue(abs.startsWith(expectedRoot), "expected $abs under $expectedRoot")
        assertTrue(abs.endsWith("foo.jpg"))
    }

    @Test
    fun delete_removes_the_file_and_reports_whether_it_existed() =
        runBlocking {
            val path = storage.write(byteArrayOf(9), "image/png")
            assertTrue(storage.delete(path))
            assertNull(storage.read(path))
            assertFalse(storage.delete(path))
        }

    @Test
    fun read_returns_null_for_a_missing_path() =
        runBlocking {
            assertNull(storage.read("photos/does-not-exist.jpg"))
        }

    @Test
    fun mime_drives_the_written_extension() =
        runBlocking {
            assertEquals("png", storage.write(byteArrayOf(1), "image/png").substringAfterLast('.'))
            assertEquals("jpg", storage.write(byteArrayOf(1), "application/octet-stream").substringAfterLast('.'))
        }
}
