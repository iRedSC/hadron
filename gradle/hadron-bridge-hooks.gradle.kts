// bridgeHooks / bridgeHooksCheck: docs/bridge-hooks.md's tables come from the @FiredBy on each event class, and
// bridgeHooksCheck (part of check) fails when the committed file differs. It also holds @FiredBy to the code: an
// event marked core = NONE fails if core allocates it or a subclass, one marked FULL or PARTIAL fails if core never
// does, and a FULL one fails if a subclass isn't FULL. And docs/events.md's catalog must say "bridge" in the
// chokepoint column for exactly the NONE events.
// bridgeHooksFixtureCheck runs these checks on small compiled examples (src/bridgeHooksFixture) that must fail.
// Reads class files only, like coverageCheck (gradle/hadron-coverage.gradle.kts), so it runs in milliseconds.
//
// Applied from neoforge/build.gradle.kts, the one project that sees api, common and the port.

import java.io.DataInputStream
import java.nio.ByteBuffer

object HadronBridgeHooks {
    // ---- Reading class files: names, supertypes, @FiredBy, and which classes each class allocates ---------------------

    class ClassInfo(val name: String, val access: Int, val superName: String?, val interfaces: List<String>,
                    val firedBy: Map<String, String>?, val allocates: Set<String>)

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
        val handleKind = IntArray(count)
        val handleRef = IntArray(count)
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf[i] = input.readUTF()
                7 -> classRef[i] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.readUnsignedShort()
                10 -> { methodClass[i] = input.readUnsignedShort(); input.readUnsignedShort() }
                9, 11 -> input.readInt()
                12 -> input.readInt()
                18 -> input.readInt()
                3, 4, 17 -> input.readInt()
                5, 6 -> { input.readLong(); i++ }
                15 -> { handleKind[i] = input.readUnsignedByte(); handleRef[i] = input.readUnsignedShort() }
                else -> error("constant pool tag $tag")
            }
            i++
        }
        fun u(index: Int) = utf[index]!!
        fun cls(index: Int) = if (index == 0) null else u(classRef[index])
        // A class allocates X where a method runs `new X` or it holds a handle to X's constructor (kind 8,
        // REF_newInvokeSpecial, which is how `X::new` compiles). A subclass's super(...) is neither.
        val allocates = (1 until count).filter { handleKind[it] == 8 }.mapNotNull { cls(methodClass[handleRef[it]]) }
                .toMutableSet()

        val access = input.readUnsignedShort()
        val name = cls(input.readUnsignedShort())!!
        val superName = cls(input.readUnsignedShort())
        val interfaces = List(input.readUnsignedShort()) { cls(input.readUnsignedShort())!! }
        fun skipAttributes() = repeat(input.readUnsignedShort()) { input.readUnsignedShort(); input.skipBytes(input.readInt()) }
        repeat(input.readUnsignedShort()) { input.skipBytes(6); skipAttributes() } // fields
        repeat(input.readUnsignedShort()) { // methods
            input.skipBytes(6)
            repeat(input.readUnsignedShort()) {
                val attr = u(input.readUnsignedShort())
                val body = ByteArray(input.readInt()).also { input.readFully(it) }
                if (attr == "Code") newOperands(body.copyOfRange(8, 8 + ByteBuffer.wrap(body, 4, 4).int)).mapTo(allocates) { cls(it)!! }
            }
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
        return ClassInfo(name, access, superName, interfaces, firedBy, allocates)
    }

    /** Operand lengths of each opcode; -1 for the variable-length ones and those that don't exist. */
    val OPERANDS = IntArray(256) { -1 }.also { n ->
        fun set(range: IntRange, length: Int) = range.forEach { n[it] = length }
        set(0x00..0xc3, 0)
        set(0x10..0x10, 1); set(0x11..0x11, 2); set(0x12..0x12, 1); set(0x13..0x14, 2) // bipush, sipush, ldc*
        set(0x15..0x19, 1); set(0x36..0x3a, 1); set(0x84..0x84, 2); set(0xa9..0xa9, 1) // locals, iinc, ret
        set(0x99..0xa8, 2); set(0xb2..0xb8, 2); set(0xb9..0xba, 4); set(0xbb..0xbb, 2) // jumps, fields, invokes, new
        set(0xbc..0xbc, 1); set(0xbd..0xbd, 2); set(0xc0..0xc1, 2); set(0xc5..0xc5, 3) // arrays, casts
        set(0xc6..0xc7, 2); set(0xc8..0xc9, 4)
        n[0xaa] = -1; n[0xab] = -1
    }

    /** The class operand of every `new` in a method's code, decoding each instruction so no operand reads as one. */
    fun newOperands(code: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(code)
        fun u2(at: Int) = buffer.getShort(at).toInt() and 0xFFFF
        val out = mutableListOf<Int>()
        var pc = 0
        while (pc < code.size) {
            val op = code[pc].toInt() and 0xFF
            val pad = (pc + 4) and 3.inv() // switch operands start at the next multiple of 4
            pc = when (op) {
                0xbb -> { out += u2(pc + 1); pc + 3 }
                0xaa -> pad + 12 + 4 * (buffer.getInt(pad + 8) - buffer.getInt(pad + 4) + 1) // tableswitch
                0xab -> pad + 8 + 8 * buffer.getInt(pad + 4) // lookupswitch
                0xc4 -> pc + if (code[pc + 1].toInt() and 0xFF == 0x84) 6 else 4 // wide
                else -> pc + 1 + OPERANDS[op].also { check(it >= 0) { "opcode $op at $pc" } }
            }
        }
        check(pc == code.size) { "code ran past its end" }
        return out
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
        val all = api + core
        fun supers(c: ClassInfo): Set<String> {
            val out = linkedSetOf<String>()
            val todo = ArrayDeque(listOfNotNull(c.superName) + c.interfaces)
            while (todo.isNotEmpty()) {
                val s = todo.removeFirst()
                if (out.add(s)) all[s]?.let { todo.addAll(listOfNotNull(it.superName) + it.interfaces) }
            }
            return out
        }
        fun isEvent(name: String) = api[name]?.let { EVENT_ROOT in supers(it) } == true
        // An instance of X is also each of X's supertypes, so allocating X fires X's api events and their parents.
        fun eventsOf(allocated: String) = (setOf(allocated) + (all[allocated]?.let(::supers) ?: emptySet())).filter(::isEvent)
        val events = api.values.filter {
            // Nested events count too (named Outer.Inner in the doc), so one can't ship unchecked by being nested.
            it.access and ACC_PUBLIC != 0 && it.access and (ACC_ABSTRACT or ACC_INTERFACE) == 0
                    && EVENT_ROOT in supers(it)
        }.sortedBy { simple(it.name) }
        // Firing is read as allocation: core code that allocates an event (`new X(...)` or `X::new`) is taken to post
        // it. Following each object to Hooks.post would need a data-flow analysis; an event core builds and never posts
        // would be its own bug, and @Covers tests prove the posting in game. Allocating a leaf fires its parents, and
        // so does allocating a subclass defined in common or neoforge, since its instance is the api event. A
        // subclass's super(...) call allocates nothing, so it doesn't count.
        val fired = core.values.flatMap { it.allocates }.flatMap(::eventsOf).toSet()
        // The core classes that allocate an event or one of its subclasses, for the error message.
        val where = { e: String ->
            core.values.filter { c -> c.allocates.any { e in eventsOf(it) } }.map { simple(it.name) }.sorted().joinToString()
        }

        val problems = mutableListOf<String>()
        // An api class that built events, a factory method included, would hide them from the check above, which only
        // reads core; api builds none.
        for (c in api.values) for (b in c.allocates.filter(::isEvent)) problems += "${simple(c.name)} in api " +
                "allocates ${simple(b)}: events are built by core (common or neoforge), so the @FiredBy check can see " +
                "who fires them. Move the `new ${simple(b)}(...)` there."
        for ((n, same) in events.groupBy { simple(it.name) }) if (same.size > 1) problems += "two events are named $n " +
                "(${same.joinToString { it.name.replace('/', '.') }}), and the docs name events by simple name: rename one."
        // A missing or empty class folder would otherwise check nothing and pass.
        if (events.isEmpty()) problems += "found no event classes in api's compiled classes, so nothing was checked: " +
                "build :api first (the check depends on :api:classes), or fix the folders it reads."
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
            // Trimmed, so a cell of only spaces counts as empty rather than filled in.
            val e = Event(n, c.name.removePrefix("dev/iredsc/hadron/api/event/").substringBefore('/'), f["core"]!!,
                    f["coreSees"]?.trim() ?: "", f["bridges"]?.trim() ?: "", f["fillIn"]?.trim() ?: "")
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
                    if (texts.any { it.isNotEmpty() }) problems += "$n is core = FULL, so bridges owe it nothing: " +
                            "remove coreSees, bridges and fillIn, or mark it PARTIAL if some path needs a bridge."
                    // A listener on X hears its subclasses, so X is only as covered as its least covered subclass.
                    val less = events.filter { c.name in supers(it) && it.firedBy?.get("core") != "FULL" }
                    if (less.isNotEmpty()) problems += "$n is core = FULL, but bridges fire its subclasses " +
                            "${less.joinToString { simple(it.name) }}, so they fire $n too: mark it PARTIAL with " +
                            "coreSees and bridges."
                }
            }
            if (e.core != "NONE" && c.name !in fired) problems += "$n is marked core = ${e.core}, but nothing in common " +
                    "or neoforge allocates it or a subclass: mark it core = NONE with bridges and fillIn, or fire it."
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
                    rows(events.filter { it.core == "PARTIAL" }) { listOf(it.coreSees, it.bridges) })

    /** The doc with each generated block replaced; the text outside the markers is kept as it is. */
    fun render(doc: String, tables: Map<String, List<String>>): String {
        var text = doc
        for ((block, lines) in tables) {
            val start = text.indexOf(begin(block))
            val stop = text.indexOf(end(block))
            // A second copy would stay stale and still pass, since the check compares the file as rendered.
            require(text.split(begin(block)).size <= 2 && text.split(end(block)).size <= 2) {
                "docs/bridge-hooks.md has more than one generated $block marker: keep one\n  ${begin(block)}\n  " +
                        "${end(block)}\npair, then run ./gradlew bridgeHooks."
            }
            require(start >= 0 && stop > start) {
                "docs/bridge-hooks.md has lost its generated $block table: put back the two lines\n  ${begin(block)}\n  " +
                        "${end(block)}\nwhere the table goes, then run ./gradlew bridgeHooks."
            }
            text = text.substring(0, start) + begin(block) + "\n" + lines.joinToString("\n") + "\n" + text.substring(stop)
        }
        return text
    }

    /**
     * Every shipped event has a catalog row in a table with a "Core chokepoint" column, so renaming the column fails
     * rather than silently checking nothing, and that cell starts with "bridge" for exactly the events core never fires.
     */
    fun catalogProblems(eventsMd: String, events: List<Event>): List<String> {
        val byName = events.associateBy { it.name }
        val problems = mutableListOf<String>()
        val listed = mutableSetOf<String>()
        val checked = mutableSetOf<String>()
        var chokepoint = -1
        var inTable = false
        for ((index, line) in eventsMd.lines().withIndex()) {
            if (!line.startsWith("|")) { chokepoint = -1; inTable = false; continue }
            val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            if (cells.firstOrNull() == "Event") { chokepoint = cells.indexOf("Core chokepoint"); inTable = true; continue }
            if (!inTable || cells[0].startsWith("---")) continue
            val names = Regex("`([\\w.]+Event)`").findAll(cells[0]).map { it.groupValues[1] }.filter { it in byName }.toList()
            listed += names
            if (chokepoint < 0) continue
            checked += names
            if (names.isEmpty()) continue
            if (cells.getOrNull(chokepoint).isNullOrEmpty()) {
                problems += "docs/events.md:${index + 1}: ${names.joinToString()} has an empty Core chokepoint cell: " +
                        "name the code core fires it from, or write \"bridge\" if only bridges fire it."
                continue
            }
            val says = cells[chokepoint].lowercase().startsWith("bridge")
            for (name in names) {
                val e = byName.getValue(name)
                val bridgeOnly = e.core == "NONE"
                if (bridgeOnly && !says) problems += "docs/events.md:${index + 1}: $name is @FiredBy(core = NONE), " +
                        "so its chokepoint cell must start with \"bridge\" (examples in brackets are fine)."
                if (!bridgeOnly && says) problems += "docs/events.md:${index + 1}: $name is @FiredBy(core = ${e.core}), " +
                        "so its chokepoint cell must name core's chokepoint, not start with \"bridge\"."
            }
        }
        for (e in events) {
            if (e.name !in listed) problems += "docs/events.md has no catalog row for ${e.name}: add one to its family's " +
                    "table (a row may name several events, each in backticks)."
            else if (e.name !in checked) problems += "docs/events.md lists ${e.name} outside a table with a " +
                    "\"Core chokepoint\" column: put it in one (or the column was renamed: name it \"Core chokepoint\" again)."
        }
        return problems.distinct() // two events sharing a name, already refused by facts(), would repeat each line
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

// ---- The checker's own test ---------------------------------------------------------------------------------------

// src/bridgeHooksFixture: a tiny api (fixture/api) and core (fixture/core), each example named for what it must do.
val compileBridgeHooksFixture = tasks.register<JavaCompile>("compileBridgeHooksFixture") {
    dependsOn(":api:classes")
    source(file("src/bridgeHooksFixture/java"))
    classpath = bridgeHooksApi
    destinationDirectory.set(layout.buildDirectory.dir("bridge-hooks-fixture/classes"))
    options.release.set(21)
}

val bridgeHooksFixtureCheck = tasks.register("bridgeHooksFixtureCheck") {
    group = "verification"
    description = "Runs the @FiredBy checks on src/bridgeHooksFixture and fails unless each gives its expected problems."
    val classes = compileBridgeHooksFixture.flatMap { it.destinationDirectory }
    inputs.files(classes)
    val marker = layout.buildDirectory.file("bridge-hooks-fixture/ok.txt")
    outputs.file(marker)
    doLast {
        val failures = mutableListOf<String>()
        // Each wanted text must match exactly one problem, and every problem must be wanted.
        fun expect(case: String, got: List<String>, want: List<String>) {
            if (want.all { w -> got.count { w in it } == 1 } && got.all { g -> want.any { it in g } }) return
            failures += "$case: wanted ${want.ifEmpty { listOf("no problems") }}, got ${got.ifEmpty { listOf("none") }}"
        }
        val dir = classes.get().asFile
        val (events, problems) = HadronBridgeHooks.facts(HadronBridgeHooks.readClasses(listOf(dir.resolve("fixture/api"))),
                HadronBridgeHooks.readClasses(listOf(dir.resolve("fixture/core"))))
        expect("events", problems, listOf("Outer.BareEvent has no @FiredBy", "FactoryEvent in api allocates FactoryEvent",
                "two events are named SameEvent", "AllocatedNoneEvent is marked core = NONE, but core fires it",
                "UnfiredEvent is marked core = FULL, but nothing", "WideEvent is core = FULL, but bridges fire its subclasses"))

        val rows = listOf("| `Outer.InnerEvent`, `FactoryEvent`, `UnfiredEvent`, `WideEvent` | `Fire` |", "| `ParentEvent` | `CoreChildEvent` |",
                "| `RefEvent` | `RefEvent::new` |", "| `SuperOnlyEvent`, `SameEvent`, `AllocatedNoneEvent`, `NarrowEvent` | Bridge (a mod) |")
        fun catalog(column: String, vararg rows: String) = "| Event | $column |\n|---|---|\n" + rows.joinToString("\n")
        expect("catalog", HadronBridgeHooks.catalogProblems(catalog("Core chokepoint", *rows.toTypedArray()), events), listOf())
        expect("renamed column", HadronBridgeHooks.catalogProblems(catalog("Chokepoint", *rows.toTypedArray()), events),
                events.map { "lists ${it.name} outside" }.distinct())
        expect("wrong cells", HadronBridgeHooks.catalogProblems(catalog("Core chokepoint", rows[0], rows[3],
                "| `ParentEvent` | BRIDGE |", "| `RefEvent` | |", "| `SuperOnlyEvent` | `Fire` |"), events),
                listOf("ParentEvent is @FiredBy(core = FULL)", "RefEvent has an empty Core chokepoint cell",
                        "SuperOnlyEvent is @FiredBy(core = NONE)"))

        val tables = HadronBridgeHooks.tables(events)
        val doc = tables.keys.joinToString("\n") { HadronBridgeHooks.begin(it) + "\n" + HadronBridgeHooks.end(it) }
        fun render(doc: String) = listOfNotNull(runCatching { HadronBridgeHooks.render(doc, tables) }.exceptionOrNull()?.message)
        expect("markers", render(doc), listOf())
        expect("missing marker", render(doc.replace(HadronBridgeHooks.end("core-partial"), "")), listOf("lost its generated core-partial"))
        expect("duplicated marker", render(doc + "\n" + doc), listOf("more than one generated bridge-only"))

        if (failures.isNotEmpty()) throw GradleException("The @FiredBy checks no longer catch what " +
                "src/bridgeHooksFixture shows: fix gradle/hadron-bridge-hooks.gradle.kts\n" + failures.joinToString("\n") { "  $it" })
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("the @FiredBy checks catch every fixture case\n") }
    }
}

tasks.named("check") { dependsOn(bridgeHooksCheck, bridgeHooksFixtureCheck) }
