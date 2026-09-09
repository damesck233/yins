package moe.damesck.yins.root

import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicyContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionApplierTest {
    private val pkg = "com.example.app"

    @Test
    fun blankGrantsRequestedRuntimePermissionsForReal() {
        val cmds = PermissionApplier.commands(
            pkg,
            listOf(PolicyContract.PERM_READ_MEDIA_IMAGES, "android.permission.CAMERA"),
            Mode.BLANK,
            forceStop = false,
        )
        assertEquals(listOf("pm grant $pkg ${PolicyContract.PERM_READ_MEDIA_IMAGES}"), cmds)
    }

    @Test
    fun allFilesAccessIsAnAppOp() {
        val cmds = PermissionApplier.commands(pkg, listOf(PolicyContract.PERM_MANAGE_EXTERNAL_STORAGE), Mode.FULL, forceStop = false)
        assertEquals(listOf("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow"), cmds)
    }

    @Test
    fun partialAlsoGrantsUserSelected() {
        val cmds = PermissionApplier.commands(pkg, listOf(PolicyContract.PERM_READ_MEDIA_IMAGES), Mode.PARTIAL, forceStop = false)
        assertTrue(cmds.contains("pm grant $pkg ${PolicyContract.PERM_READ_MEDIA_VISUAL_USER_SELECTED}"))
        assertTrue(cmds.contains("pm grant $pkg ${PolicyContract.PERM_READ_MEDIA_IMAGES}"))
    }

    @Test
    fun denyRevokes() {
        val cmds = PermissionApplier.commands(
            pkg,
            listOf(PolicyContract.PERM_READ_MEDIA_IMAGES, PolicyContract.PERM_MANAGE_EXTERNAL_STORAGE),
            Mode.DENY,
            forceStop = true,
        )
        assertEquals(
            listOf(
                "pm revoke $pkg ${PolicyContract.PERM_READ_MEDIA_IMAGES}",
                "appops set --uid $pkg MANAGE_EXTERNAL_STORAGE ignore",
                "am force-stop $pkg",
            ),
            cmds,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShellMetacharactersInPackageName() {
        PermissionApplier.commands("com.evil; rm -rf /", emptyList(), Mode.FULL, forceStop = false)
    }
}
