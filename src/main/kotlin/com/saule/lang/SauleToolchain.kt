package com.saule.lang

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Locates the Saule toolchain binaries (`saule`, `saule-lsp`) and the workspace
 * root to run them from.
 *
 * Normally that is the installed toolchain, on `PATH` or in `~/.saule/bin`. A
 * project's own Cargo build output is only considered when the user has allowed
 * it in Settings, because a project's files are not trusted just because it was
 * opened: nothing stops a repository committing `target/debug/saule-lsp`, and
 * starting it automatically would hand that repository the user's machine.
 */
object SauleToolchain {
    private val LOG = logger<SauleToolchain>()

    /**
     * @property command    the executable path (or bare name if nothing was found).
     * @property workingDir where to run it (may be null).
     * @property found      true if [command] resolved to a real file / PATH entry;
     *                      false means we fell back to the bare name.
     */
    data class Located(val command: String, val workingDir: String?, val found: Boolean)

    /** Windows needs the `.exe` suffix; Unix does not. */
    fun exeName(base: String): String = if (SystemInfo.isWindows) "$base.exe" else base

    /**
     * @param base           bare binary name, e.g. `"saule"` or `"saule-lsp"`.
     * @param overrideEnv    env var that, if set, pins the executable path.
     * @param overrideProp   system property that, if set, pins the executable path.
     */
    fun locate(
        project: Project,
        base: String,
        overrideEnv: String? = null,
        overrideProp: String? = null,
    ): Located {
        val exe = exeName(base)

        // 1. Environment / system-property override.
        explicitOverride(overrideEnv, overrideProp)?.let {
            return Located(it, project.basePath, found = true)
        }

        // 2. Configured toolchain in Settings ▸ Languages & Frameworks ▸ Saule.
        val settings = SauleSettings.getInstance()
        settings.explicitPathFor(base).trim().ifEmpty { null }?.let {
            return Located(it, project.basePath, found = File(it).isFile)
        }
        settings.toolchainDir.trim().ifEmpty { null }?.let { dir ->
            val candidate = Paths.get(dir, exe)
            if (Files.isRegularFile(candidate)) {
                return Located(candidate.toString(), project.basePath, found = true)
            }
        }

        // 3–5. The project's build if allowed, then PATH, then the install directory.
        discover(
            exe,
            candidateRoots(project),
            settings.useProjectBuild,
            System.getenv("PATH"),
            installDir(),
        )?.let { (path, workingDir) ->
            LOG.info("Using $base from $path")
            return Located(path.toString(), workingDir?.toString() ?: project.basePath, found = true)
        }

        // 6. Nothing found — bare name, so callers can surface a helpful error.
        LOG.warn("$base not found via settings, PATH, or the install directory")
        return Located(exe, project.basePath, found = false)
    }

    /**
     * The search once explicit choices are out of the way, from plain inputs so
     * the rule can be tested without an IDE. The second value is the working
     * directory, when the location implies one.
     */
    internal fun discover(
        exe: String,
        roots: List<Path>,
        allowProjectBuild: Boolean,
        pathEnv: String?,
        installDir: Path?,
    ): Pair<Path, Path?>? {
        if (allowProjectBuild) {
            for (root in roots) {
                findUpward(root, exe)?.let { (path, workspace) -> return path to workspace }
            }
        }
        findOnPath(exe, pathEnv)?.let { return it to null }
        installDir?.resolve(exe)?.takeIf { Files.isRegularFile(it) }?.let { return it to null }
        return null
    }

    /** `$SAULE_HOME/bin`, else `~/.saule/bin`. */
    private fun installDir(): Path? {
        val home = System.getenv("SAULE_HOME")?.trim()?.ifEmpty { null }
            ?: System.getProperty("user.home")?.let { Paths.get(it, ".saule").toString() }
            ?: return null
        return runCatching { Paths.get(home, "bin") }.getOrNull()
    }

    /** Walk up from [start], returning the `target/{release,debug}/exe` and the
     *  directory that contains that `target/` folder (the workspace root). */
    private fun findUpward(start: Path, exe: String): Pair<Path, Path>? {
        var dir: Path? = start
        var depth = 0
        while (dir != null && depth < 24) {
            for (profile in listOf("release", "debug")) {
                val candidate = dir.resolve("target").resolve(profile).resolve(exe)
                if (Files.isRegularFile(candidate)) return candidate to dir
            }
            dir = dir.parent
            depth++
        }
        return null
    }

    private fun candidateRoots(project: Project): List<Path> {
        val roots = LinkedHashSet<Path>()
        project.basePath?.let { runCatching { roots.add(Paths.get(it)) } }
        runCatching {
            ProjectRootManager.getInstance(project).contentRoots.forEach { vf ->
                runCatching { roots.add(Paths.get(vf.path)) }
            }
        }
        return roots.toList()
    }

    private fun explicitOverride(env: String?, prop: String?): String? {
        val raw = prop?.let { System.getProperty(it) }
            ?: env?.let { System.getenv(it) }
            ?: return null
        return raw.trim().ifEmpty { null }
    }

    /** A relative entry such as `.` is skipped: it would resolve against the open project. */
    private fun findOnPath(exe: String, pathEnv: String?): Path? {
        return (pathEnv ?: return null).split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Paths.get(it) }.getOrNull() }
            .filter { it.isAbsolute }
            .map { it.resolve(exe) }
            .firstOrNull { Files.isRegularFile(it) }
    }
}
