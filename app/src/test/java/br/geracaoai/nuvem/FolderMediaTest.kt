package br.geracaoai.nuvem

import android.net.Uri
import br.geracaoai.nuvem.data.CloudItem
import br.geracaoai.nuvem.data.FolderItemKind
import br.geracaoai.nuvem.data.FolderMedia
import br.geracaoai.nuvem.data.FolderMediaFilter
import br.geracaoai.nuvem.data.LocalItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FolderMediaTest {

    private fun loc(id: Long, name: String, size: Long, path: String = "/sdcard/DCIM/Camera/$name", mime: String = "image/jpeg") =
        LocalItem(Uri.parse("content://media/$id"), path, name, size, id * 10, id * 10, "Camera", "DCIM/Camera/", mime, id)

    private fun cloud(id: Long, name: String, size: Long, hash: String, path: String = "/sdcard/DCIM/Camera/$name") =
        CloudItem(id, 1L, name, "image/jpeg", size, id * 10, "Camera", path, hash, null)

    private fun merge(local: List<LocalItem>, cloud: List<CloudItem>, hashes: Map<String, String> = emptyMap()) =
        FolderMedia.merge(local, cloud) { hashes[it.path] }

    @Test fun pendingLocalIsNotSelectableAndSelectAllSkipsIt() {
        val pending = loc(1, "new.jpg", 100)
        val backed = loc(2, "ok.jpg", 200)
        val items = merge(listOf(pending, backed), listOf(cloud(20, "ok.jpg", 200, "h-ok")), mapOf(backed.path to "h-ok"))
        assertEquals(FolderItemKind.LOCAL_PENDING, items.first { it.name == "new.jpg" }.kind)
        assertEquals(FolderItemKind.LOCAL_BACKED, items.first { it.name == "ok.jpg" }.kind)
        val all = FolderMedia.selectAll(items)
        assertEquals(setOf(items.first { it.name == "ok.jpg" }.id), all)
        assertFalse(items.first { it.name == "new.jpg" }.id in all)
        assertEquals(all, FolderMedia.selectableIds(items))
    }

    @Test fun deselectAllClearsSelection() {
        val backed = loc(2, "ok.jpg", 200)
        val items = merge(listOf(backed), listOf(cloud(20, "ok.jpg", 200, "h-ok")), mapOf(backed.path to "h-ok"))
        val selected = FolderMedia.selectAll(items)
        assertEquals(1, selected.size)
        assertTrue(FolderMedia.deselectAll().isEmpty())
    }

    @Test fun toggleIgnoresPendingAndCloudOnlyAndTogglesBacked() {
        val pending = loc(1, "new.jpg", 100)
        val backed = loc(2, "ok.jpg", 200)
        val only = cloud(99, "old.jpg", 50, "h-old")
        val items = merge(listOf(pending, backed), listOf(cloud(20, "ok.jpg", 200, "h-ok"), only), mapOf(backed.path to "h-ok"))
        val pendingId = items.first { it.name == "new.jpg" }.id
        val backedId = items.first { it.name == "ok.jpg" }.id
        val cloudId = items.first { it.name == "old.jpg" }.id
        var sel = emptySet<String>()
        sel = FolderMedia.toggle(sel, pendingId, items)
        assertTrue(sel.isEmpty())
        sel = FolderMedia.toggle(sel, cloudId, items)
        assertTrue(sel.isEmpty())
        sel = FolderMedia.toggle(sel, backedId, items)
        assertEquals(setOf(backedId), sel)
        sel = FolderMedia.toggle(sel, backedId, items)
        assertTrue(sel.isEmpty())
    }

    @Test fun cloudOnlyAppearsAfterLocalIsGoneAndIsNotSelectable() {
        val remaining = loc(2, "ok.jpg", 200)
        val gone = cloud(99, "old.jpg", 50, "h-old", "/sdcard/DCIM/Camera/old.jpg")
        val still = cloud(20, "ok.jpg", 200, "h-ok")
        val items = merge(listOf(remaining), listOf(still, gone), mapOf(remaining.path to "h-ok"))
        val only = items.filter { it.kind == FolderItemKind.CLOUD_ONLY }
        assertEquals(1, only.size)
        assertEquals("old.jpg", only[0].name)
        assertFalse(only[0].selectable)
        assertTrue(FolderMedia.selectAll(items).none { it == only[0].id })
    }

    @Test fun doesNotDuplicateCloudItemStillOnDevice() {
        val local = loc(2, "ok.jpg", 200)
        val ci = cloud(20, "ok.jpg", 200, "h-ok", local.path)
        val items = merge(listOf(local), listOf(ci), mapOf(local.path to "h-ok"))
        assertEquals(1, items.size)
        assertEquals(FolderItemKind.LOCAL_BACKED, items[0].kind)
        assertEquals(ci.msgId, items[0].cloud?.msgId)
    }

    @Test fun nameAndSizeWithoutHashStillCountsAsBacked() {
        val local = loc(2, "ok.jpg", 200)
        val items = merge(listOf(local), listOf(cloud(20, "ok.jpg", 200, "h-ok")))
        assertEquals(FolderItemKind.LOCAL_BACKED, items[0].kind)
        assertTrue(items[0].selectable)
    }

    @Test fun cachedHashMismatchIsNotBackedEvenIfNameMatches() {
        val local = loc(2, "ok.jpg", 200)
        val items = merge(listOf(local), listOf(cloud(20, "ok.jpg", 200, "h-ok")), mapOf(local.path to "h-other"))
        assertEquals(FolderItemKind.LOCAL_PENDING, items[0].kind)
        assertFalse(items[0].selectable)
    }

    @Test fun filtersTodosAparelhoNuvem() {
        val pending = loc(1, "new.jpg", 100)
        val backed = loc(2, "ok.jpg", 200)
        val only = cloud(99, "old.jpg", 50, "h-old")
        val items = merge(listOf(pending, backed), listOf(cloud(20, "ok.jpg", 200, "h-ok"), only), mapOf(backed.path to "h-ok"))
        assertEquals(3, FolderMedia.applyFilter(items, FolderMediaFilter.ALL).size)
        val device = FolderMedia.applyFilter(items, FolderMediaFilter.ON_DEVICE)
        assertEquals(2, device.size)
        assertTrue(device.none { it.kind == FolderItemKind.CLOUD_ONLY })
        val cloudOnly = FolderMedia.applyFilter(items, FolderMediaFilter.CLOUD_ONLY)
        assertEquals(listOf("old.jpg"), cloudOnly.map { it.name })
    }

    @Test fun summaryCountsOnlySelectedBackedItems() {
        val a = loc(1, "a.jpg", 6L * 1024 * 1024)
        val b = loc(2, "b.jpg", 6L * 1024 * 1024)
        val items = merge(
            listOf(a, b, loc(3, "c.jpg", 4L * 1024 * 1024)),
            listOf(cloud(11, "a.jpg", a.size, "ha"), cloud(12, "b.jpg", b.size, "hb")),
            mapOf(a.path to "ha", b.path to "hb"),
        )
        val selected = FolderMedia.selectAll(items)
        val (count, bytes) = FolderMedia.summary(items, selected)
        assertEquals(2, count)
        assertEquals(12L * 1024 * 1024, bytes)
    }

    @Test fun allowedToTrashKeepsOnlyVerifiedAndReportsSkipped() {
        val a = loc(1, "a.jpg", 10)
        val b = loc(2, "b.jpg", 20)
        val selected = listOf(a, b)
        val verified = listOf(a)
        val allowed = FolderMedia.allowedToTrash(selected, verified)
        assertEquals(listOf(a.path), allowed.map { it.path })
        assertEquals(1, FolderMedia.skippedCount(selected.size, verified.size))
        assertTrue(FolderMedia.allowedToTrash(selected, emptyList()).isEmpty())
        assertEquals(2, FolderMedia.skippedCount(2, 0))
    }

    @Test fun hasBackupWhenIncludedOrAlreadyOnTelegram() {
        assertTrue(FolderMedia.hasBackup(true, 0))
        assertTrue(FolderMedia.hasBackup(false, 3))
        assertTrue(FolderMedia.hasBackup(true, 3))
        assertFalse(FolderMedia.hasBackup(false, 0))
    }
}
