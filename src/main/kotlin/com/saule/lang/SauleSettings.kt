package com.saule.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-wide location of the Saule toolchain, for when it is not on `PATH`
 * or in `~/.saule/bin`.
 *
 * Set it in **Settings ▸ Languages & Frameworks ▸ Saule**.
 */
@Service(Service.Level.APP)
@State(name = "SauleSettings", storages = [Storage("saule.xml")])
class SauleSettings : PersistentStateComponent<SauleSettings.State> {

    class State {
        /** Folder containing the `saule` and `saule-lsp` binaries. */
        @JvmField var toolchainDir: String = ""
        /** Explicit path to `saule` (wins over [toolchainDir]). */
        @JvmField var saulePath: String = ""
        /** Explicit path to `saule-lsp` (wins over [toolchainDir]). */
        @JvmField var sauleLspPath: String = ""
        /** Run a project's own `target/` build. See [SauleToolchain]. */
        @JvmField var useProjectBuild: Boolean = false
        /** Reformat `.sau` files with `saule fmt` when they are saved. */
        @JvmField var formatOnSave: Boolean = true
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    var toolchainDir: String
        get() = myState.toolchainDir
        set(value) { myState.toolchainDir = value }

    var saulePath: String
        get() = myState.saulePath
        set(value) { myState.saulePath = value }

    var sauleLspPath: String
        get() = myState.sauleLspPath
        set(value) { myState.sauleLspPath = value }

    var useProjectBuild: Boolean
        get() = myState.useProjectBuild
        set(value) { myState.useProjectBuild = value }

    /** See [com.saule.lang.format.SauleFormatOnSave]. On by default. */
    var formatOnSave: Boolean
        get() = myState.formatOnSave
        set(value) { myState.formatOnSave = value }

    /** Explicit override configured for [base] (`"saule"` / `"saule-lsp"`), or "". */
    fun explicitPathFor(base: String): String = when (base) {
        "saule" -> saulePath
        "saule-lsp" -> sauleLspPath
        else -> ""
    }

    companion object {
        fun getInstance(): SauleSettings =
            ApplicationManager.getApplication().getService(SauleSettings::class.java)
    }
}
