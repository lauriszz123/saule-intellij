package com.saule.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Which binary gets run, and above all which one does not. */
class SauleToolchainTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val exe = "saule-lsp"

    private fun file(relative: String): Path {
        val path = temp.root.toPath().resolve(relative)
        Files.createDirectories(path.parent)
        Files.createFile(path)
        return path
    }

    private fun dir(relative: String): Path =
        Files.createDirectories(temp.root.toPath().resolve(relative))

    private fun find(
        roots: List<Path>,
        allowProjectBuild: Boolean = false,
        pathEnv: String? = null,
        installDir: Path? = null,
    ) = SauleToolchain.discover(exe, roots, allowProjectBuild, pathEnv, installDir)

    @Test
    fun `a binary inside the opened project is not run by default`() {
        file("project/target/debug/$exe")
        assertNull(find(listOf(dir("project"))))
    }

    @Test
    fun `the installed toolchain wins over a binary the project ships`() {
        file("project/target/debug/$exe")
        val installed = file("home/bin/$exe")
        val found = find(listOf(dir("project")), installDir = installed.parent)
        assertEquals(installed, found?.first)
    }

    @Test
    fun `opting in finds the project's build, walking up from the root`() {
        val planted = file("project/target/debug/$exe")
        val onPath = file("bin/$exe")
        val found = find(
            listOf(dir("project/examples/demo")),
            allowProjectBuild = true,
            pathEnv = onPath.parent.toString(),
        )
        assertEquals(planted, found?.first)
        assertEquals(dir("project"), found?.second)
    }

    @Test
    fun `PATH comes before the install directory`() {
        val onPath = file("bin/$exe")
        val installed = file("home/bin/$exe")
        val found = find(emptyList(), pathEnv = onPath.parent.toString(), installDir = installed.parent)
        assertEquals(onPath, found?.first)
    }

    @Test
    fun `a relative PATH entry is not searched`() {
        file("project/$exe")
        assertNull(find(emptyList(), pathEnv = ".${File.pathSeparator}project"))
    }
}
