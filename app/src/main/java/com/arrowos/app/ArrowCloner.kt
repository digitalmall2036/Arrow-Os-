package com.arrowos.app

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Arrow Cloner is the assistant that does work *through* Arrow Os. It speaks the same
 * language a person does: every step is one Arrow Os command that starts with "tap" or
 * "search", plus "wait 5" to give a slow page time.
 *
 * There are two ways Arrow Cloner gets work:
 *  - **Handed a task**: a link `arrowos://run?name=...&steps=...` (from the Arrow Cloner
 *    page, or any website opened inside Arrow Os). Arrow Os shows the steps and asks
 *    before running them.
 *  - **Taught by watching**: "tap teach arrow cloner", do the work with normal commands,
 *    then "tap save skill as morning post". Later "tap do morning post" repeats it.
 *
 * Pure Kotlin, no Android types, so it is covered by plain JVM unit tests.
 */
data class ClonerTask(val name: String, val steps: List<String>) {

    /** The link that hands this task to Arrow Os. */
    fun toLink(): String =
        "$SCHEME://$HOST?name=${encode(name)}&steps=${encode(steps.joinToString("\n"))}"

    companion object {
        const val SCHEME = "arrowos"
        const val HOST = "run"
        const val MAX_STEPS = 60
        const val MAX_WAIT_SECONDS = 60

        /** True for `arrowos://run?...` links. */
        fun isTaskLink(link: String): Boolean = link.trim().lowercase().startsWith("$SCHEME://$HOST")

        /**
         * Reads a task link. Steps are separated by new lines (or ";" when a link is typed
         * by hand). Returns null when it isn't a task link or holds no steps.
         */
        fun fromLink(link: String): ClonerTask? {
            if (!isTaskLink(link)) return null
            val query = link.substringAfter('?', "").substringBefore('#')
            val params = query.split('&').filter { it.contains('=') }.associate { pair ->
                decode(pair.substringBefore('=')).lowercase() to decode(pair.substringAfter('='))
            }
            val name = params["name"]?.trim().orEmpty().ifEmpty { "Arrow Cloner task" }.take(80)
            val steps = splitSteps(params["steps"].orEmpty())
            if (steps.isEmpty()) return null
            return ClonerTask(name, steps)
        }

        /** One step per line; blank lines and "# notes" are skipped. */
        fun splitSteps(text: String): List<String> =
            text.split('\n', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .take(MAX_STEPS)

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        /** A raw "+" means a space (form style); a real plus sign arrives as %2B. */
        private fun decode(s: String) = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            s
        }
    }
}

/** One thing to do while running a task. [line] is the step as written, shown to the user. */
sealed class ClonerAction {
    abstract val line: String

    data class Do(override val line: String, val command: Command) : ClonerAction()
    data class Wait(override val line: String, val seconds: Int) : ClonerAction()
}

/** A step Arrow Os won't run, and why. */
data class ClonerProblem(val line: String, val reason: String)

/** A task turned into actions, ready to run, with any steps that were refused. */
data class ClonerPlan(val actions: List<ClonerAction>, val problems: List<ClonerProblem>) {
    val canRun: Boolean get() = actions.isNotEmpty()

    companion object {
        private val waitPattern = Regex("^(?:wait|pause for|sleep)(?: for)? (\\d{1,3})(?: ?(?:s|sec|secs|second|seconds))?$")

        /**
         * Checks every step. A step must start with "tap" or "search" (or be "wait N"),
         * exactly like a person's command. Steps that would change Arrow Cloner itself,
         * stop the mic, or wipe history are refused, so a link can't do that.
         */
        fun of(task: ClonerTask): ClonerPlan {
            val actions = mutableListOf<ClonerAction>()
            val problems = mutableListOf<ClonerProblem>()
            for (line in task.steps) {
                val normal = CommandParser.normalize(line)
                val wait = waitPattern.find(normal)
                if (wait != null) {
                    val seconds = wait.groupValues[1].toInt().coerceIn(1, ClonerTask.MAX_WAIT_SECONDS)
                    actions += ClonerAction.Wait(line, seconds)
                    continue
                }
                val commands = CommandParser.parseAll(line)
                val refused = commands.firstOrNull { !allowedInTask(it) }
                when {
                    commands.singleOrNull() is Command.Ignored ->
                        problems += ClonerProblem(line, "doesn't start with \"tap\" or \"search\"")
                    refused != null ->
                        problems += ClonerProblem(line, "Arrow Cloner can't do this in a task")
                    else -> commands.forEach { actions += ClonerAction.Do(line, it) }
                }
            }
            return ClonerPlan(actions, problems)
        }

        /** What a task (or a taught skill) may do. */
        fun allowedInTask(command: Command): Boolean = when (command) {
            is Command.Ignored, Command.StopListening, Command.ClearPrevious, Command.Help,
            Command.StartTeaching, is Command.StopTeaching, Command.ShowSkills, is Command.RunSkill,
            -> false
            else -> true
        }
    }
}

/**
 * Watches the user work while Arrow Cloner is being taught, and keeps each command
 * they gave, in order. Only commands a task may run are kept.
 */
class ClonerRecorder {
    private val steps = mutableListOf<String>()
    var isRecording = false
        private set

    val recorded: List<String> get() = steps.toList()

    fun start() {
        steps.clear()
        isRecording = true
    }

    /** Remembers [input] (what the user said or typed) when it is a command a task can repeat. */
    fun record(input: String): Boolean {
        if (!isRecording || steps.size >= ClonerTask.MAX_STEPS) return false
        val commands = CommandParser.parseAll(input)
        if (commands.isEmpty() || !commands.all { ClonerPlan.allowedInTask(it) }) return false
        steps += CommandParser.normalize(input)
        return true
    }

    /** Stops watching and hands back what was learned. */
    fun stop(): List<String> {
        isRecording = false
        return recorded
    }

    fun cancel() {
        isRecording = false
        steps.clear()
    }
}

/**
 * The skills Arrow Cloner has been taught, saved per account. Saving a skill under a
 * name it already has replaces it.
 */
class ClonerSkills {
    private val skills = mutableListOf<ClonerTask>()

    val all: List<ClonerTask> get() = skills.toList()
    val size: Int get() = skills.size
    fun isEmpty() = skills.isEmpty()

    fun save(task: ClonerTask) {
        val i = skills.indexOfFirst { simplify(it.name) == simplify(task.name) }
        if (i >= 0) skills[i] = task else skills += task
    }

    fun remove(name: String): Boolean = skills.removeAll { simplify(it.name) == simplify(name) }

    /** The skill the user named ("morning post"), whole-name matches first. */
    fun find(spoken: String): ClonerTask? {
        val words = simplify(spoken).removePrefix("the ").removeSuffix(" skill").trim()
        if (words.isEmpty()) return null
        return skills.firstOrNull { simplify(it.name) == words }
            ?: skills.firstOrNull { simplify(it.name).contains(words) || words.contains(simplify(it.name)) }
    }

    /** One skill per line: name, then its steps, separated by tabs (with \t \n \\ escaped). */
    fun encode(): String = skills.joinToString("\n") { task ->
        (listOf(task.name) + task.steps).joinToString("\t") { escape(it) }
    }

    companion object {
        fun decode(text: String?): ClonerSkills {
            val result = ClonerSkills()
            text.orEmpty().lineSequence().forEach { line ->
                val parts = line.split('\t').map { unescape(it) }
                if (parts.size >= 2 && parts[0].isNotBlank()) result.skills += ClonerTask(parts[0], parts.drop(1))
            }
            return result
        }

        private fun simplify(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unescape(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        else -> out.append(s[i + 1])
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
