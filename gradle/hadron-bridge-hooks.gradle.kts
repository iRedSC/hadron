// bridgeHooks / bridgeHooksCheck: docs/bridge-hooks.md's tables come from the @FiredBy on each event class, and
// bridgeHooksCheck (part of check) fails when the committed file differs. It also holds @FiredBy to the code: an
// event marked core = NONE fails if core constructs it or a subclass, and one marked FULL or PARTIAL fails if core
// never does. And docs/events.md's catalog must say "bridge" in the chokepoint column for exactly the NONE events.
// Reads class files only, like coverageCheck (gradle/hadron-coverage.gradle.kts), so it runs in milliseconds.
//
// Applied from neoforge/build.gradle.kts, the one project that sees api, common and the port.

import java.io.DataInputStream

object HadronBridgeHooks {
    // ---- Reading class files: names, supertypes, @FiredBy, and which constructors each class calls ---------------------

    class ClassInfo(val name: String, val access: Int, val superName: String?, val interfaces: List<String>,
                    val firedBy: Map<String, String>?, val constructs: Set<String>)

    const val ACC_PUBLIC = 0x0001
    const val ACC_INTERFACE = 0x0200
    const val ACC_ABSTRACT = 0x0400
    const val FIRED_BY = "Ldev/iredsc/hadron/api/event/FiredBy;"
    const val EVENT_ROOT = "dev/iredsc/hadron/api/event/Event"

    fun read(bytes: ByteArray): ClassInfo {
        val input = DataInputStream(bytes.inputStream())
        check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        input.readUnsignedShort(); input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf = arrayOfNulls<String>(count)
        val classRef = IntArray(count)
        val methodClass = IntArray(count)
        val methodNat = IntArray(count)
        val natName = IntArray(count)
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf[i] = input.readUTF()
                7 -> classRef[i] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.readUnsignedShort()
                10 -> { methodClass[i] = input.readUnsignedShort(); methodNat[i] = input.readUnsignedShort() }
                9, 11 -> input.readInt()
                12 -> { natName[i] = input.readUnsignedShort(); input.readUnsignedShort() }
                18 -> input.readInt()
                3, 4, 17 -> input.readInt()
                5, 6 -> { input.readLong(); i++ }
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                else -> error("constant pool tag $tag")
            }
            i++
        }
        fun u(index: Int) = utf[index]!!
        fun cls(index: Int) = if (index == 0) null else u(classRef[index])
        // A Methodref to <init> is a constructor call: `new X(...)`, `X::new`, or a subclass's super(...).
        val constructs = (1 until count).filter { methodClass[it] != 0 && u(natName[methodNat[it]]) == "<init>" }
                .mapNotNull { cls(methodClass[it]) }.toSet()

        val access = input.readUnsignedShort()
        val name = cls(input.readUnsignedShort())!!
        val superName = cls(input.readUnsignedShort())
        val interfaces = List(input.readUnsignedShort()) { cls(input.readUnsignedShort())!! }
        fun skipAttributes() = repeat(input.readUnsignedShort()) { input.readUnsignedShort(); input.skipBytes(input.readInt()) }
        repeat(2) { // fields, then methods
            repeat(input.readUnsignedShort()) { input.skipBytes(6); skipAttributes() }
        }
        // Element values: strings and enum constant names are kept, as that's all @FiredBy holds.
        fun value(): String? = when (val tag = input.readUnsignedByte().toChar()) {
            's' -> u(input.readUnsignedShort())
            'e' -> { input.readUnsignedShort(); u(input.readUnsignedShort()) }
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'c' -> { input.readUnsignedShort(); null }
            '@' -> { input.readUnsignedShort(); repeat(input.readUnsignedShort()) { input.readUnsignedShort(); value() }; null }
            '[' -> { repeat(input.readUnsignedShort()) { value() }; null }
            else -> error("element value tag $tag")
        }
        var firedBy: Map<String, String>? = null
        repeat(input.readUnsignedShort()) {
            val attr = u(input.readUnsignedShort())
            val length = input.readInt()
            if (attr == "RuntimeVisibleAnnotations" || attr == "RuntimeInvisibleAnnotations") {
                repeat(input.readUnsignedShort()) {
                    val type = u(input.readUnsignedShort())
                    val values = mutableMapOf<String, String>()
                    repeat(input.readUnsignedShort()) {
                        val key = u(input.readUnsignedShort())
                        value()?.let { values[key] = it }
                    }
                    if (type == FIRED_BY) firedBy = values
                }
            } else input.skipBytes(length)
        }
        return ClassInfo(name, access, superName, interfaces, firedBy, constructs)
    }

    fun readClasses(dirs: Iterable<File>): Map<String, ClassInfo> = dirs.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                .map { read(it.readBytes()) }.toList()
    }.associateBy { it.name }

    // ---- The facts ------------------------------------------------------------------------------------------------------

    class Event(val name: String, val family: String, val core: String, val coreSees: String, val bridges: String,
                val fillIn: String)

    val FAMILIES = listOf("block", "entity", "item", "inventory", "resource", "world", "player", "server")

    fun simple(internal: String) = internal.substringAfterLast('/').replace('$', '.')

    /** The events and their @FiredBy, or the problems that stop the doc being generated. */
    fun facts(api: Map<String, ClassInfo>, core: Map<String, ClassInfo>): Pair<List<Event>, List<String>> {
        fun supers(c: ClassInfo): Set<String> {
            val out = linkedSetOf<String>()
            val todo = ArrayDeque(listOfNotNull(c.superName) + c.interfaces)
            while (todo.isNotEmpty()) {
                val s = todo.removeFirst()
                if (out.add(s)) api[s]?.let { todo.addAll(listOfNotNull(it.superName) + it.interfaces) }
            }
            return out
        }
        val events = api.values.filter {
            it.access and ACC_PUBLIC != 0 && it.access and (ACC_ABSTRACT or ACC_INTERFACE) == 0 && '$' !in it.name
                    && EVENT_ROOT in supers(it)
        }.sortedBy { simple(it.name) }
        // Every api class core constructs, and the classes it extends: a parent counts when core fires a leaf.
        val built = core.values.flatMap { it.constructs }.filter { it in api }.toSet()
        val fired = built + built.flatMap { supers(api[it]!!) }
        // The core classes that construct an event or one of its leaves, for the error message.
        val where = { e: String ->
            core.values.filter { c -> c.constructs.any { it == e || api[it]?.let { a -> e in supers(a) } == true } }
                    .map { simple(it.name) }.sorted().joinToString()
        }

        val problems = mutableListOf<String>()
        val out = mutableListOf<Event>()
        for (c in events) {
            val n = simple(c.name)
            val f = c.firedBy
            if (f == null) {
                problems += "$n has no @FiredBy: add @FiredBy(core = FULL) if core fires it on every path, " +
                        "PARTIAL with coreSees and bridges if bridges fire it for their mods' paths too, or NONE with " +
                        "bridges and fillIn if only bridges fire it."
                continue
            }
            val e = Event(n, c.name.removePrefix("dev/iredsc/hadron/api/event/").substringBefore('/'), f["core"]!!,
                    f["coreSees"] ?: "", f["bridges"] ?: "", f["fillIn"] ?: "")
            val texts = listOf(e.coreSees, e.bridges, e.fillIn)
            if (texts.any { '|' in it || '\n' in it }) problems += "$n's @FiredBy has a | or a line break, which breaks " +
                    "the table in docs/bridge-hooks.md: rephrase without it."
            if (texts.any { it.endsWith(".") }) problems += "$n's @FiredBy texts are table cells: drop the trailing period."
            when (e.core) {
                "NONE" -> {
                    if (e.coreSees.isNotEmpty()) problems += "$n is core = NONE but has coreSees: remove coreSees, or mark it PARTIAL."
                    if (e.bridges.isEmpty() || e.fillIn.isEmpty()) problems += "$n is core = NONE, so bridges must " +
                            "fire it: say what the mod does (bridges = \"...\") and what the bridge fills in (fillIn = \"...\")."
                    if (c.name in fired) problems += "$n is marked core = NONE, but core fires it " +
                            "(${where(c.name)}): " +
                            "mark it FULL or PARTIAL with coreSees."
                }
                "PARTIAL" -> {
                    if (e.coreSees.isEmpty() || e.bridges.isEmpty()) problems += "$n is core = PARTIAL: say what core " +
                            "fires it for (coreSees = \"...\") and what bridges fire it for (bridges = \"...\")."
                    if (e.fillIn.isNotEmpty()) problems += "$n: fillIn is for core = NONE events; put what to fill in into bridges."
                }
                "FULL" -> {
                    if (e.fillIn.isNotEmpty()) problems += "$n: fillIn is for core = NONE events; remove it."
                    if (e.bridges.isNotEmpty() && e.coreSees.isEmpty()) problems += "$n is core = FULL with bridges " +
                            "advice: say what core fires it for too (coreSees = \"...\"), as that row shows both."
                }
            }
            if (e.core != "NONE" && c.name !in fired) problems += "$n is marked core = ${e.core}, but nothing in common " +
                    "or neoforge constructs it or a subclass: mark it core = NONE with bridges and fillIn, or fire it."
            out += e
        }
        return out to problems
    }

    // ---- Writing the doc ------------------------------------------------------------------------------------------------

    fun begin(block: String) = "<!-- generated: $block, from @FiredBy on each event class. " +
            "Edit those and run ./gradlew bridgeHooks; edits here are overwritten. -->"
    fun end(block: String) = "<!-- end generated: $block -->"

    /** Rows with the same texts become one row naming each event. */
    fun rows(events: List<Event>, cells: (Event) -> List<String>): List<String> {
        val ordered = events.sortedWith(compareBy({ FAMILIES.indexOf(it.family).let { i -> if (i < 0) FAMILIES.size else i } }, { it.name }))
        val groups = linkedMapOf<List<String>, MutableList<String>>()
        for (e in ordered) groups.getOrPut(cells(e)) { mutableListOf() } += "`${e.name}`"
        return groups.map { (c, names) -> "| ${names.joinToString(", ")} | ${c.joinToString(" | ")} |" }
    }

    fun tables(events: List<Event>): Map<String, List<String>> = mapOf(
            "bridge-only" to listOf("| Event | Fire when the mod… | Must fill in |", "|---|---|---|") +
                    rows(events.filter { it.core == "NONE" }) { listOf(it.bridges, it.fillIn) },
            "core-partial" to listOf("| Event | Core sees | The bridge fires it for |", "|---|---|---|") +
                    rows(events.filter { it.core != "NONE" && it.bridges.isNotEmpty() }) { listOf(it.coreSees, it.bridges) })

    /** The doc with each generated block replaced; the text outside the markers is kept as it is. */
    fun render(doc: String, tables: Map<String, List<String>>): String {
        var text = doc
        for ((block, lines) in tables) {
            val start = text.indexOf(begin(block))
            val stop = text.indexOf(end(block))
            require(start >= 0 && stop > start) {
                "docs/bridge-hooks.md has lost its generated $block table: put back the two lines\n  ${begin(block)}\n  " +
                        "${end(block)}\nwhere the table goes, then run ./gradlew bridgeHooks."
            }
            text = text.substring(0, start) + begin(block) + "\n" + lines.joinToString("\n") + "\n" + text.substring(stop)
        }
        return text
    }

    /** The catalog's chokepoint column says "bridge" for exactly the events core never fires. */
    fun catalogProblems(eventsMd: String, events: List<Event>): List<String> {
        val byName = events.associateBy { it.name }
        val problems = mutableListOf<String>()
        var chokepoint = -1
        for ((index, line) in eventsMd.lines().withIndex()) {
            if (!line.startsWith("|")) { chokepoint = -1; continue }
            val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            if (cells.firstOrNull() == "Event") { chokepoint = cells.indexOf("Core chokepoint"); continue }
            if (chokepoint < 0 || cells.size <= chokepoint || cells[0].startsWith("---")) continue
            val says = cells[chokepoint].startsWith("bridge")
            for (name in Regex("`(\\w+Event)`").findAll(cells[0]).map { it.groupValues[1] }) {
                val e = byName[name] ?: continue // not shipped yet
                val bridgeOnly = e.core == "NONE"
                if (bridgeOnly && !says) problems += "docs/events.md:${index + 1}: $name is @FiredBy(core = NONE), " +
                        "so its chokepoint cell must start with \"bridge\" (examples in brackets are fine)."
                if (!bridgeOnly && says) problems += "docs/events.md:${index + 1}: $name is @FiredBy(core = ${e.core}), " +
                        "so its chokepoint cell must name core's chokepoint, not start with \"bridge\"."
            }
        }
        return problems
    }

    class Result(val expected: String, val problems: List<String>) {
        fun failIfProblems() {
            if (problems.isNotEmpty()) throw GradleException("@FiredBy: ${problems.size} problem(s)\n" +
                    problems.joinToString("\n") { "  $it" })
        }
    }

    fun run(apiDirs: Iterable<File>, coreDirs: Iterable<File>, doc: File, eventsMd: File): Result {
        val (events, problems) = facts(readClasses(apiDirs), readClasses(coreDirs))
        val tables = tables(events)
        val expected = render(doc.readText(), tables)
        check(render(expected, tables) == expected) { "bridgeHooks isn't idempotent: a second run changes the doc" }
        return Result(expected, problems + catalogProblems(eventsMd.readText(), events))
    }

    /** What a reader needs to see why the check failed: the lines that would go and come, at most 20 of each. */
    fun diffSummary(committed: String, expected: String): String {
        val old = committed.lines()
        val new = expected.lines()
        val gone = old.filter { it !in new.toSet() }
        val come = new.filter { it !in old.toSet() }
        fun show(sign: String, lines: List<String>) = lines.take(20).joinToString("") { "\n  $sign $it" } +
                if (lines.size > 20) "\n  $sign … ${lines.size - 20} more" else ""
        return show("-", gone) + show("+", come)
    }
}

// ---- The tasks ------------------------------------------------------------------------------------------------------

val bridgeHooksApi = files(project(":api").layout.buildDirectory.dir("classes/java/main"))
val bridgeHooksCore = files(project(":common").layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/main"))
val bridgeHooksDoc = rootProject.file("docs/bridge-hooks.md")
val bridgeHooksEvents = rootProject.file("docs/events.md")
val bridgeHooksCompile = listOf(":api:classes", ":common:classes", "classes")

tasks.register("bridgeHooks") {
    group = "documentation"
    description = "Writes docs/bridge-hooks.md's tables from the @FiredBy on each event class."
    dependsOn(bridgeHooksCompile)
    val apiDirs = bridgeHooksApi
    val coreDirs = bridgeHooksCore
    val doc = bridgeHooksDoc
    val eventsMd = bridgeHooksEvents
    doLast {
        val result = HadronBridgeHooks.run(apiDirs.files, coreDirs.files, doc, eventsMd)
        result.failIfProblems()
        if (doc.readText() == result.expected) logger.lifecycle("docs/bridge-hooks.md is already up to date.")
        else { doc.writeText(result.expected); logger.lifecycle("Wrote docs/bridge-hooks.md.") }
    }
}

val bridgeHooksCheck = tasks.register("bridgeHooksCheck") {
    group = "verification"
    description = "Fails when docs/bridge-hooks.md differs from what @FiredBy says, or @FiredBy from what core fires."
    dependsOn(bridgeHooksCompile)
    inputs.files(bridgeHooksApi, bridgeHooksCore)
    inputs.files(bridgeHooksDoc, bridgeHooksEvents)
    val marker = layout.buildDirectory.file("bridge-hooks/ok.txt")
    outputs.file(marker)
    val apiDirs = bridgeHooksApi
    val coreDirs = bridgeHooksCore
    val doc = bridgeHooksDoc
    val eventsMd = bridgeHooksEvents
    doLast {
        val result = HadronBridgeHooks.run(apiDirs.files, coreDirs.files, doc, eventsMd)
        result.failIfProblems()
        val committed = doc.readText()
        if (committed != result.expected) throw GradleException("docs/bridge-hooks.md is out of date with the " +
                "@FiredBy on the event classes:" + HadronBridgeHooks.diffSummary(committed, result.expected) +
                "\nRun ./gradlew bridgeHooks to regenerate it, and commit the result.")
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("docs/bridge-hooks.md matches @FiredBy\n") }
    }
}

tasks.named("check") { dependsOn(bridgeHooksCheck) }
