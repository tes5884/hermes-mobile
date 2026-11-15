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
        OVERWRITE_FILE, REBOOT, SHUTDOWN, RUN, SEVERAL
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

    /**
     * What the approval is for. A plain-words reading (a [Kind] other than [Kind.RUN]/[Kind.SEVERAL]) is given only
     * for ONE simple command, because Approve authorizes the whole command line (Shomer, P4): two or more steps
     * (`&&`, `||`, `;`, `|`, `&`, a newline, outside quotes) are [Kind.SEVERAL] with the step count, and anything the
     * phone cannot read with certainty (substitution, globs, braces, subshells, here-docs, escapes, unbalanced quotes,
     * wrappers like `sudo -u`, a redirect beside the action) is [Kind.RUN] with the full, untruncated command. Both
     * make the card show the exact command up front ([showsCommandUpFront]). The one compound reading kept is
     * `curl|wget … | sh`, exactly those two steps and nothing else.
     */
    fun of(command: String, home: String = ""): Summary {
        val full = command.trim()
        val shape = shape(full)
        if (!shape.balanced || shape.steps.isEmpty()) return Summary(Kind.RUN, full)
        if (shape.steps.size > 1) return remoteScript(shape) ?: Summary(Kind.SEVERAL, shape.steps.size.toString())
        if (shape.ambiguous) return Summary(Kind.RUN, full)
        val words = prepared(words(shape.steps.single()))
        if (words.isEmpty() || words.first().startsWith("-") || words.first() == "cd") return Summary(Kind.RUN, full)
        return classify(words, home) ?: Summary(Kind.RUN, full)
    }

    /**
     * True when the card must show the exact command up front, not behind Details: the phone could not read it as one
     * named action ([Kind.RUN]), it has several steps ([Kind.SEVERAL]), or it deletes several things ([Kind.DELETE_MANY]).
     */
    fun showsCommandUpFront(command: String): Boolean =
        command.isNotBlank() && of(command).kind.let { it == Kind.RUN || it == Kind.SEVERAL || it == Kind.DELETE_MANY }

    /** A command line as the shell splits it, outside quotes. [ambiguous] = something only the shell can resolve. */
    internal data class Shape(val steps: List<String>, val separators: List<String>, val ambiguous: Boolean, val balanced: Boolean)

    internal fun shape(command: String): Shape {
        val steps = mutableListOf<String>()
        val separators = mutableListOf<String>()
        val cur = StringBuilder()
        var ambiguous = false
        var single = false
        var double = false
        fun cut(sep: String) { steps += cur.toString(); separators += sep; cur.clear() }
        var i = 0
        while (i < command.length) {
            val c = command[i]
            val next = command.getOrNull(i + 1)
            when {
                single -> { if (c == '\'') single = false; cur.append(c) }
                double -> {
                    if (c == '"') double = false else if (c == '$' || c == '`' || c == '\\') ambiguous = true
                    cur.append(c)
                }
                // A quote inside a word (`--x='a b'`, `/a"b c"`) is joined by the shell in ways the word reader skips.
                c == '\'' || c == '"' -> {
                    if (cur.isNotEmpty() && !cur.last().isWhitespace()) ambiguous = true
                    if (c == '\'') single = true else double = true
                    cur.append(c)
                }
                c == '&' && next == '&' -> { cut("&&"); i++ }
                c == '|' && next == '|' -> { cut("||"); i++ }
                c == '|' -> if (next == '&') { cut("|&"); i++ } else cut("|")
                c == ';' || c == '\n' || c == '\r' -> cut(";")
                c == '&' -> {
                    val prev = cur.lastOrNull()
                    if (prev == '>' || prev == '<' || next == '>') cur.append(c) else cut("&")
                }
                c == '<' && next == '<' -> { ambiguous = true; cur.append(c) }
                c in "$`\\*?[]{}()#" -> { ambiguous = true; cur.append(c) }
                else -> cur.append(c)
            }
            i++
        }
        steps += cur.toString()
        // A separator with nothing after it (`a;`, `a &`) adds no step: the steps are what runs.
        return Shape(steps.map { it.trim() }.filter { it.isNotEmpty() }, separators, ambiguous, balanced = !single && !double)
    }

    /** `curl|wget <url> | [sudo] sh|bash|zsh [flags]`, and nothing else on the line. */
    private fun remoteScript(shape: Shape): Summary? {
        if (shape.ambiguous || shape.steps.size != 2 || shape.separators != listOf("|")) return null
        val fetch = prepared(words(shape.steps[0]))
        val run = prepared(words(shape.steps[1]))
        if (fetch.firstOrNull()?.substringAfterLast('/') !in setOf("curl", "wget")) return null
        if (run.firstOrNull()?.substringAfterLast('/') !in setOf("sh", "bash", "zsh") || run.drop(1).any { !it.startsWith("-") }) return null
        if ((fetch + run).any { '>' in it || '<' in it }) return null
        val host = Regex("""https?://([^/\s'"]+)""").find(shape.steps[0])?.groupValues?.get(1).orEmpty()
        return Summary(Kind.RUN_REMOTE_SCRIPT, place = host)
    }

    /** A step's words with a leading `sudo`, `env`, `command` and `X=y` assignments dropped. */
    private fun prepared(words: List<String>): List<String> =
        words.dropWhile { it == "sudo" || it == "env" || it == "command" || Regex("^[A-Za-z_][A-Za-z0-9_]*=").containsMatchIn(it) }

    private fun words(segment: String): List<String> =
        Regex("""'[^']*'|"[^"]*"|\S+""").findAll(segment.trim()).map { it.value.trim { c -> c == '\'' || c == '\u0022' } }.filter { it.isNotEmpty() }.toList()

    private fun operands(words: List<String>): List<String> = words.drop(1).filterNot { it.startsWith("-") }

    private fun flags(words: List<String>): String = words.drop(1).filter { it.startsWith("-") }.joinToString(" ")

    private fun classify(w: List<String>, home: String): Summary? {
        // Redirect words are not operands (`rm -rf x 2>&1` deletes one folder). A redirect beside a named action is a
        // second effect the headline would not name, so the command is shown instead; the only redirect a headline
        // may stand on is one it names itself (`echo … > /etc/hosts`).
        val plain = withoutRedirects(w)
        if (plain.isEmpty()) return null
        val n = redirects(w)
        val action = action(plain, home)
        if (action != null) return action.takeIf { n == 0 }
        return if (n == 1) redirect(w, home) else null
    }

    /** [w] without redirect words and the target after a bare `>`, `>>`, `2>`, `<`. */
    private fun withoutRedirects(w: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < w.size) {
            val word = w[i]
            when {
                Regex("""^[0-9&]?(>>?|<)$""").matches(word) -> i += 2
                '>' in word || '<' in word -> i++
                else -> { out += word; i++ }
            }
        }
        return out
    }

    /** `<`/`>` words other than output thrown away (`>/dev/null`, `2>&1`, `> /dev/null`). */
    private fun redirects(w: List<String>): Int {
        var n = 0
        var i = 0
        while (i < w.size) {
            val word = w[i]
            if (Regex("""^[0-9&]?>>?$""").matches(word) && w.getOrNull(i + 1) == "/dev/null") { i += 2; continue }
            if (Regex("""^[0-9&]?>>?(/dev/null|&[0-9])$""").matches(word)) { i++; continue }
            if ('>' in word || '<' in word) n++
            i++
        }
        return n
    }

    private fun action(w: List<String>, home: String): Summary? {
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
            else -> if (program.startsWith("mkfs.")) Summary(Kind.FORMAT_DISK, args.lastOrNull().orEmpty()) else null
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
