package com.nousresearch.hermesmobile.data

/**
 * The plain-words headline of an approval card (Tzvi, 2026-11-15: "recursive delete" is jargon). Hermes' approval
 * request carries the command and the name of the pattern that stopped it (`description`: "recursive delete",
 * "stop/restart system service"…), not the agent's own words for the step, so the phone reads the command: what it
 * does, to what, and where. The UI turns a [Summary] into a localized sentence; the pattern name stays a small line.
 */
object CommandSummary {
    enum class Kind {
        DELETE_FOLDER, DELETE_FILE, DELETE_MANY, FORCE_PUSH, PUSH, DISCARD_CHANGES, DELETE_UNTRACKED,
        RESTART_SERVICE, STOP_SERVICE, START_SERVICE, DISABLE_SERVICE, RESTART_CONTAINER, STOP_CONTAINER, REMOVE_CONTAINER,
        STOP_PROCESS, CHANGE_PERMISSIONS, CHANGE_OWNER, MOVE, WRITE_DISK, FORMAT_DISK, RUN_REMOTE_SCRIPT, EDIT_FILE,
        OVERWRITE_FILE, REBOOT, SHUTDOWN, RUN
    }

    /** [subject] = what it acts on (a name, a count, a branch); [place] = where (a short folder, a remote, a host). */
    data class Summary(val kind: Kind, val subject: String = "", val place: String = "")

    /**
     * True when [description] is one of Hermes' pattern names rather than a sentence someone wrote for the user: the
     * pattern key itself, or a lowercase-first phrase ("recursive delete", "disk copy").
     */
    fun isPatternLabel(description: String, patternKey: String = ""): Boolean {
        val d = description.trim()
        if (d.isEmpty()) return true
        if (patternKey.isNotBlank() && d.equals(patternKey.trim(), ignoreCase = true)) return true
        return d.first().isLowerCase() && '\n' !in d && d.length <= 80
    }

    fun of(command: String, home: String = ""): Summary {
        val segments = split(command)
        // The step that matters: the first segment the patterns would care about, else the first real one.
        val words = segments.firstOrNull { classify(it, home) != null } ?: segments.firstOrNull()
            ?: return Summary(Kind.RUN, command.lineSequence().firstOrNull().orEmpty().trim().take(80))
        if (Regex("""\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(ba|z)?sh\b""").containsMatchIn(command)) {
            val host = Regex("""https?://([^/\s'"]+)""").find(command)?.groupValues?.get(1).orEmpty()
            return Summary(Kind.RUN_REMOTE_SCRIPT, place = host)
        }
        return classify(words, home) ?: Summary(Kind.RUN, words.joinToString(" ").take(80))
    }

    /** Shell segments (split on `&&`, `||`, `;`, `|`, newlines) as words, with `sudo`, `env X=y` and `cd dir` dropped. */
    private fun split(command: String): List<List<String>> =
        command.split(Regex("""&&|\|\||;|\||\n""")).map { words(it) }
            .map { w -> w.dropWhile { it == "sudo" || it == "env" || it == "command" || Regex("^[A-Za-z_][A-Za-z0-9_]*=").containsMatchIn(it) } }
            .filter { it.isNotEmpty() && it.first() != "cd" }

    private fun words(segment: String): List<String> =
        Regex("""'[^']*'|"[^"]*"|\S+""").findAll(segment.trim()).map { it.value.trim { c -> c == '\'' || c == '\u0022' } }.filter { it.isNotEmpty() }.toList()

    private fun operands(words: List<String>): List<String> = words.drop(1).filterNot { it.startsWith("-") }

    private fun flags(words: List<String>): String = words.drop(1).filter { it.startsWith("-") }.joinToString(" ")

    private fun classify(w: List<String>, home: String): Summary? {
        val program = w.first().substringAfterLast('/')
        val args = operands(w)
        val flags = flags(w)
        return when (program) {
            "rm", "rmdir" -> {
                val recursive = program == "rmdir" || Regex("""(^|\s)-[a-zA-Z]*[rR]|--recursive""").containsMatchIn(flags)
                when {
                    args.isEmpty() -> null
                    args.size > 1 -> Summary(Kind.DELETE_MANY, args.size.toString(), place(commonParent(args), home))
                    else -> Summary(if (recursive) Kind.DELETE_FOLDER else Kind.DELETE_FILE, name(args[0]), place(parent(args[0]), home))
                }
            }
            "git" -> gitSummary(w)
            "systemctl", "service" -> {
                val verb = if (program == "service") args.getOrNull(1) else args.firstOrNull()
                val unit = (if (program == "service") args.firstOrNull() else args.getOrNull(1)).orEmpty().removeSuffix(".service")
                when (verb) {
                    "restart", "reload", "try-restart" -> Summary(Kind.RESTART_SERVICE, unit)
                    "stop", "kill" -> Summary(Kind.STOP_SERVICE, unit)
                    "start" -> Summary(Kind.START_SERVICE, unit)
                    "disable", "mask" -> Summary(Kind.DISABLE_SERVICE, unit)
                    else -> null
                }
            }
            "docker", "podman" -> dockerSummary(args)
            "kill", "pkill", "killall", "taskkill" -> Summary(Kind.STOP_PROCESS, args.lastOrNull().orEmpty())
            "chmod" -> args.lastOrNull()?.let { Summary(Kind.CHANGE_PERMISSIONS, name(it), place(parent(it), home)) }
            "chown", "chgrp" -> args.lastOrNull()?.let { Summary(Kind.CHANGE_OWNER, name(it), place(parent(it), home)) }
            "mv" -> if (args.size >= 2) Summary(Kind.MOVE, name(args[0]), shortPath(args.last(), home)) else null
            "dd" -> Summary(Kind.WRITE_DISK, w.firstOrNull { it.startsWith("of=") }?.removePrefix("of=").orEmpty())
            "mkfs", "wipefs", "fdisk", "parted" -> Summary(Kind.FORMAT_DISK, args.lastOrNull().orEmpty())
            "sed" -> if (Regex("""(^|\s)-[a-zA-Z]*i""").containsMatchIn(flags)) args.lastOrNull()?.let { Summary(Kind.EDIT_FILE, name(it), place(parent(it), home)) } else null
            "tee" -> args.lastOrNull()?.let { Summary(Kind.OVERWRITE_FILE, name(it), place(parent(it), home)) }
            "reboot" -> Summary(Kind.REBOOT)
            "shutdown", "poweroff", "halt" -> if ("-r" in w) Summary(Kind.REBOOT) else Summary(Kind.SHUTDOWN)
            else -> if (program.startsWith("mkfs.")) Summary(Kind.FORMAT_DISK, args.lastOrNull().orEmpty()) else redirect(w, home)
        }
    }

    /** `… > file` (not `>>`, not `/dev/null`): the file is overwritten. */
    private fun redirect(w: List<String>, home: String): Summary? {
        val at = w.indexOfFirst { it == ">" || (it.startsWith(">") && !it.startsWith(">>") && it.length > 1) }
        if (at < 0) return null
        val target = if (w[at] == ">") w.getOrNull(at + 1) else w[at].removePrefix(">")
        if (target.isNullOrBlank() || target.startsWith("/dev/") || target.startsWith("&")) return null
        return Summary(Kind.OVERWRITE_FILE, name(target), place(parent(target), home))
    }

    private fun gitSummary(w: List<String>): Summary? {
        val args = operands(w)
        val sub = args.firstOrNull() ?: return null
        val flags = flags(w)
        return when (sub) {
            "push" -> {
                val remote = args.getOrNull(1).orEmpty()
                val branch = args.drop(2).joinToString(" ")
                val force = Regex("""(^|\s)(-f|--force|--force-with-lease)\b""").containsMatchIn(flags) || args.drop(2).any { it.startsWith("+") }
                Summary(if (force) Kind.FORCE_PUSH else Kind.PUSH, branch.removePrefix("+"), remote)
            }
            "reset" -> if ("--hard" in flags) Summary(Kind.DISCARD_CHANGES) else null
            "checkout", "restore" -> if (args.drop(1).contains(".") || "--" in w) Summary(Kind.DISCARD_CHANGES) else null
            "clean" -> Summary(Kind.DELETE_UNTRACKED)
            "branch" -> if (Regex("""(^|\s)-[dD]\b|--delete""").containsMatchIn(flags)) Summary(Kind.DELETE_FILE, args.drop(1).joinToString(" ")) else null
            else -> null
        }
    }

    private fun dockerSummary(args: List<String>): Summary? {
        val sub = args.firstOrNull() ?: return null
        if (sub == "compose") {
            return when (args.getOrNull(1)) {
                "down", "stop", "kill" -> Summary(Kind.STOP_CONTAINER, args.drop(2).joinToString(" "))
                "restart", "up" -> Summary(Kind.RESTART_CONTAINER, args.drop(2).joinToString(" "))
                "rm" -> Summary(Kind.REMOVE_CONTAINER, args.drop(2).joinToString(" "))
                else -> null
            }
        }
        val target = args.drop(1).joinToString(" ")
        return when (sub) {
            "restart" -> Summary(Kind.RESTART_CONTAINER, target)
            "stop", "kill" -> Summary(Kind.STOP_CONTAINER, target)
            "rm", "rmi" -> Summary(Kind.REMOVE_CONTAINER, target)
            "container" -> dockerSummary(args.drop(1))
            else -> null
        }
    }

    private fun name(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    private fun parent(path: String): String {
        val trimmed = path.trimEnd('/')
        return if ('/' in trimmed) trimmed.substringBeforeLast('/').ifBlank { "/" } else ""
    }

    private fun commonParent(paths: List<String>): String {
        val parents = paths.map(::parent).distinct()
        return if (parents.size == 1) parents.first() else ""
    }

    /** A folder as the card names it: home as `~`, and only its last two parts after that (`…/ezra/scratch`). */
    private fun place(dir: String, home: String): String = if (dir.isBlank()) "" else shortPath(dir, home)

    internal fun shortPath(path: String, home: String): String {
        var p = path.trimEnd('/').ifBlank { "/" }
        val homeDir = home.trimEnd('/').ifBlank { Regex("^/(home|Users)/[^/]+").find(p)?.value.orEmpty() }
        if (homeDir.isNotBlank() && (p == homeDir || p.startsWith("$homeDir/"))) p = "~" + p.removePrefix(homeDir)
        val parts = p.split('/').filter { it.isNotEmpty() }
        val root = if (p.startsWith("~")) "~" else ""
        val rest = if (root.isNotEmpty()) parts.drop(1) else parts
        return when {
            rest.size <= 2 -> if (root.isNotEmpty()) (listOf(root) + rest).joinToString("/") else "/" + rest.joinToString("/")
            else -> "…/" + rest.takeLast(2).joinToString("/")
        }
    }
}
