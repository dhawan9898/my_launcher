package dev.omalauncher.linux

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * A coding agent CLI, installed the way Omarchy's omarchy-default-agent does it: via mise, or with its
 * [own] installer when mise has no package for it.
 */
data class Agent(
    val bin: String,
    val name: String,
    @StringRes val description: Int,
    /** mise package spec; empty with its [own] installer. */
    val pkg: String,
    /** What starts it without a prompt, when that's more than [bin]. */
    val command: String = bin,
    /** Whether mise needs node to install it: npm packages run their postinstall scripts with it. */
    val node: Boolean = pkg.startsWith("npm:"),
    /** Set for an agent that installs itself instead of through mise. */
    val own: OwnInstaller? = null,
) {
    /** Its activity alias in the manifest (cursor-agent -> CursorAgentWindow), so Recents names its window after it. */
    val windowAlias get() = bin.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Window"
}

/** How an agent that mise has no package for installs, updates and removes itself; all run in the guest. */
data class OwnInstaller(
    val install: String,
    val update: String,
    val remove: String,
    /**
     * A file, from the guest home, that is there once the install is complete. The installer puts the
     * command in place before it builds what the command needs, so the command alone can be an install
     * that stopped halfway.
     */
    val done: String,
)

/**
 * Hermes keeps what its installer and updater downloaded (pnpm's store and uv's wheels): 140 MB after an install
 * and 600 MB after an update, needed by neither once they are done. What it runs from is linked out of them,
 * and stays.
 */
const val HERMES_CLEANUP = "rm -rf ~/.hermes/cache/pnpm ~/.hermes/cache/uv"

/**
 * Omarchy's agent list (omarchy-default-agent), limited to ones mise can install on arm64, plus Hermes,
 * which Omarchy also installs with Hermes's own installer.
 */
val AGENTS =
    listOf(
        Agent("agy", "Antigravity", R.string.linux_agent_antigravity, "antigravity-cli"),
    )

/**
 * Brings mise itself up to date. Which backend a tool installs from is built into mise, and an old mise
 * breaks when a tool moves: OpenCode 2 left GitHub releases for npm, and mise 2026.10.8 still looked for
 * it on GitHub (404). Best effort, since the old mise may still manage.
 */
const val MISE_SELF_UPDATE = "{ mise self-update -y || true; }"

/**
 * `mise prune -y`, but deleting the unused versions itself first: under proot, mise can't delete a version
 * with a hard link in it (OpenCode's npm postinstall makes one), stops at "Directory not empty", and leaves
 * 255 MB behind. `mise ls --prunable` keeps versions a running program started from, like prune does.
 * proot's stand-in for the hard link (`.l2s.*`) can come back while the first `rm -rf` empties the folder, so
 * it tries up to three times (#196).
 */
const val MISE_PRUNE =
    "{ mise ls --prunable --json | sed -n 's/.*\"install_path\": \"\\([^\"]*\\)\".*/\\1/p' | " +
        "while read -r p; do case \"\$p\" in */mise/installs/*) for i in 1 2 3; do rm -rf \"\$p\" 2>/dev/null && break; done ;; esac; done; " +
        "mise prune -y; }"

/**
 * Downloads and caches are only needed during installs and upgrades; storage on phones is tight. npm-backed
 * agents leave npm's cache behind too (125 MB after one update).
 */
const val MISE_CLEANUP = "mise cache clear && rm -rf ~/.local/share/mise/downloads ~/.npm/_cacache"

/** Updates the agents that installed themselves, each only if it's there. */
val OWN_UPDATES =
    AGENTS.mapNotNull { it.own }.joinToString(" && ") { "if [ -e ~/${it.done} ]; then ${it.update}; fi" }

/** Adds node to the global config when an installed agent needs it but was installed before it did (OpenCode 1). */
val MISE_NODE_FOR_AGENTS =
    AGENTS.filter { it.node }.joinToString(" ", prefix = "if [ -z \"\$(mise ls -g node)\" ] && [ -n \"\$(mise ls -g ", postfix = ")\" ]; then mise use -g node@lts; fi") {
        shellQuote(it.pkg)
    }

/**
 * Starts [agentBin] with a first prompt; OpenCode takes it as --prompt, Antigravity and Copilot as -i (they
 * refuse a bare argument), the others as an argument. Hermes takes it as its chat's --query, which in a
 * terminal stays open after the answer, with --yolo as Omarchy starts it.
 * Ori's agent is `ori code`, which stays open only with --interactive. Kimi takes a prompt only in
 * print mode, so the session it ran in is continued after the answer. Qwen Code and Kilo bind it to
 * an option, MiniMax Code, Kiro, Devin and Command Code take it after --, and Amp reads its first
 * message from stdin (an argument would be one turn that exits), as Omarchy's omarchy-agent starts them.
 * [readOnly], for reading something untrusted: Claude Code and Antigravity start in plan mode, where they
 * can read but not run commands or change files whatever the user's settings say. Hermes starts with its
 * file tools alone (-t file): no terminal, web or browser, so it can read and write files but not run or
 * send anything. Leaving out --yolo isn't enough: its approvals default to "smart", where its own model
 * approves a dangerous command, and other commands never ask. The others have no flag for that we can
 * rely on inside proot, and only have the prompt.
 */
fun agentInvocation(
    agentBin: String = "agy",
    prompt: String,
    readOnly: Boolean = false,
): String {
    val quoted = shellQuote(if (prompt.startsWith("-")) " $prompt" else prompt)
    return if (readOnly) "agy --mode plan -i $quoted" else "agy -i $quoted"
}

class AgentStore(
    private val proot: Proot,
    /** A string resource with one argument, in the app's language. */
    private val text: (Int, String) -> String,
    /** Called when the set of installed agents changed, so their desktop entries follow. */
    private val onChanged: () -> Unit,
) {
    private val mise get() = File(proot.home, ".local/bin/mise")
    private val shims get() = File(proot.home, ".local/share/mise/shims")

    val states = mutableStateMapOf<String, JobState>()

    /**
     * Shims are symlinks to a guest path (/root/.local/bin/mise), so don't follow them from the host. An agent
     * with its own installer is there once that finished.
     */
    fun isInstalled(agent: Agent) =
        if (agent.own != null) File(proot.home, agent.own.done).exists() else Files.exists(File(shims, agent.bin).toPath(), LinkOption.NOFOLLOW_LINKS)

    /** The agent picked in the prompt bar, kept in the guest home where scripts can read it too. */
    private val preferredFile get() = File(proot.launcherConfig, "agent")
    private var preferred by mutableStateOf<String?>(null)

    /** Reads what's on disk; call off the main thread before the UI needs [default]. */
    fun load() {
        preferred = runCatching { preferredFile.readText().trim() }.getOrNull()
        refresh()
    }

    /** Installed agents in Omarchy's order; re-read from disk after installs and removals. */
    var installed by mutableStateOf<List<Agent>>(emptyList())
        private set

    private fun refresh() {
        installed = AGENTS.filter { isInstalled(it) }
    }

    /** The agent the launcher opens for prompts and widget work: the picked one, else the first installed in Omarchy's order. */
    val default: Agent? get() = installed.firstOrNull { it.bin == preferred } ?: installed.firstOrNull()

    fun choose(agent: Agent) {
        preferred = agent.bin
        runCatching {
            preferredFile.parentFile?.mkdirs()
            preferredFile.writeText(agent.bin + "\n")
        }.onFailure { Log.w("agents", "cannot save the default agent", it) }
    }

    /**
     * The install runs on after its screen closes, which cancels this (the blocking job ignores that), so what
     * mise left is read in finally: a finished install shows up without a restart.
     */
    suspend fun install(agent: Agent): Boolean =
        try {
            run(agent, R.string.linux_job_installing) { installScript(agent) }
        } finally {
            if (isInstalled(agent)) onInstalled(agent)
        }

    /** Installs [agent] with its own installer, or with mise, bootstrapping mise itself if needed. */
    fun installScript(agent: Agent) =
        agent.own?.install
            ?: buildString {
                append(if (mise.exists()) "$MISE_SELF_UPDATE && " else "curl -fsSL https://mise.run | sh && ")
                if (agent.node) append("mise use -g node@lts && ")
                append("mise use -g ${shellQuote(agent.pkg)} && $MISE_CLEANUP")
            }

    /** Call after [installScript] succeeded: makes the agent show up in the drawer. */
    fun onInstalled(agent: Agent) {
        refresh()
        onChanged()
    }

    /**
     * mise locks its config and its installs while it works, and a second `mise use` at the same time fails on
     * the lock: installs, removals and system updates take this first, one at a time, the others waiting.
     */
    val lock = Mutex()

    /** Stops what the launcher itself keeps running of an agent, such as Voice's; whether something was stopped. */
    var release: (Agent) -> Boolean = { false }

    /**
     * mise keeps a version that a running program started from, so an agent still open in a window or terminal
     * stays installed, out of mise's config; the row then says to close it, instead of looking untouched. What
     * the launcher runs itself (Voice) is stopped first.
     */
    suspend fun remove(agent: Agent): Boolean {
        // Its process ends on another thread: a moment, so mise doesn't find it still running.
        if (release(agent)) delay(1500)
        val ok =
            try {
                run(agent, R.string.linux_job_removing) { agent.own?.remove ?: "mise unuse -g ${shellQuote(agent.pkg)} && $MISE_PRUNE" }
            } finally {
                // Also when its screen closed while mise worked, as with install.
                refresh()
                onChanged()
            }
        if (ok && isInstalled(agent)) states[agent.bin] = JobState.Failed(listOf(text(R.string.agents_still_running, agent.name)))
        return ok && !isInstalled(agent)
    }

    /** Drawer entries for the installed agents, with their logos (see [bundledIcons]); each opens in a terminal in the home directory. */
    fun managed(): List<Managed> =
        installed.map { agent ->
            Managed.text(File(proot.applications, "agent-${agent.bin}.desktop"), Policy.Owned) {
                "[Desktop Entry]\nType=Application\nName=${agent.name}\nTerminal=true\nExec=${agent.command}\nIcon=${bundledIcon("agent-${agent.bin}")}\n"
            }
        } + codexConfig()

    /**
     * Codex starts a shared background server and reads its start time with `ps`; when that fails, Codex
     * refuses to start ("failed to read start time for pid-managed app server", rerun with --no-daemon).
     * Proot kills that server with its window anyway, so don't start it. Codex's sandbox can't run either:
     * before calling bwrap it looks up its socket directory in /proc/self/mountinfo, which is Android's
     * under proot, and fails every command ("cannot establish app-server socket mount isolation"). The
     * bwrap shim drops the sandbox anyway, so full access is what Codex already had. A system layer, so
     * the user's ~/.codex/config.toml stays theirs and can change both.
     */
    private fun codexConfig() =
        if (installed.none { it.bin == "codex" }) {
            emptyList()
        } else {
            listOf(
                Managed.text(File(proot.rootfs, "etc/codex/config.toml"), Policy.Owned) {
                    "# Written by the launcher.\nsandbox_mode = \"danger-full-access\"\n\n[features]\ndaemon_auto_start = false\n"
                },
            )
        }

    private suspend fun run(
        agent: Agent,
        @StringRes action: Int,
        script: () -> String,
    ): Boolean {
        val publish: (JobState) -> Unit = { states[agent.bin] = it }
        val waiting = JobState.Working(R.string.linux_job_waiting, emptyList())
        publish(waiting)
        // The script is made once it's this job's turn: the one before may have installed mise.
        val (ok, _) =
            try {
                lock.withLock { proot.trackedJob(action, withContext(Dispatchers.IO) { script() }, publish = publish) }
            } catch (e: CancellationException) {
                // Its screen closed before its turn: the row isn't left "waiting…", which also blocks installing it again.
                if (states[agent.bin] === waiting) states.remove(agent.bin)
                throw e
            }
        Log.i("agents", "${agent.bin}: ${if (ok) "ok" else "failed"}")
        return ok
    }
}
