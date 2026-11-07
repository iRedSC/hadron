// coverageCheck: fails the build unless every shipped event and every public API method has a test that says it
// covers it with @Covers (test-api). Reads class files only (Hadron's api classes and the compiled tests), so it runs in
// seconds with no server. The rule it enforces is in docs/testing.md, "Coverage"; keep the two in step.
// Applied with apply(from = ...) for now and written against nothing but Gradle's own API, so the Hadron Gradle plugin
// can take it over.
//
// The applying project names what to read, as Files or providers of them:
//     extra["hadronCoverage.api"] = <directories of the api's compiled classes>
//     extra["hadronCoverage.tests"] = <directories of compiled test classes>   (missing ones are skipped)
//     extra["hadronCoverage.dependsOn"] = <tasks that compile them>

import java.io.DataInputStream

// Everything the check does, in one object: the task must not reach into this script (the configuration cache
// refuses that), and a plugin can lift it out whole.
object HadronCoverage {
    // ---- Reading class files: just the parts the rule needs ------------------------------------------------------------

    class CoversRef(val type: String, val method: String)

    // code: the method's bytecode, or null when it has none (abstract, native).
    class MethodInfo(val access: Int, val name: String, val desc: String, val annotations: List<String>,
                     val covers: List<CoversRef>, val code: ByteArray?)

    class ClassInfo(val name: String, val access: Int, val superName: String?, val interfaces: List<String>,
                    val methods: List<MethodInfo>, val recordComponents: Set<String>,
                    // Access flags of nested classes as declared in source, and each nested class's enclosing class.
                    val innerAccess: Map<String, Int>, val outer: Map<String, String?>, val annotations: List<String>,
                    // For each invokedynamic constant, the class of its bootstrap method.
                    val indyBootstrapOwner: Map<Int, String>)

    val ACC_PUBLIC = 0x0001
    val ACC_PROTECTED = 0x0004
    val ACC_STATIC = 0x0008
    val ACC_FINAL = 0x0010
    val ACC_BRIDGE = 0x0040
    val ACC_INTERFACE = 0x0200
    val ACC_ABSTRACT = 0x0400
    val ACC_SYNTHETIC = 0x1000
    val ACC_ANNOTATION = 0x2000

    val COVERS = "Ldev/iredsc/hadron/test/Covers;"
    val COVERS_LIST = "Ldev/iredsc/hadron/test/Covers\$List;"

    class ClassReader(bytes: ByteArray) {
        private val input = DataInputStream(bytes.inputStream())
        private lateinit var utf: Array<String?>
        private lateinit var classRef: IntArray
        // Raw references, resolved once the whole class is read: invokedynamic -> bootstrap index,
        // method handle -> member, member -> class.
        private lateinit var indyBootstrap: IntArray
        private lateinit var handleRef: IntArray
        private lateinit var memberClass: IntArray

        private fun u(index: Int) = utf[index]!!
        private fun cls(index: Int) = if (index == 0) null else u(classRef[index])

        // Element values: only class and string values are kept, as that's all @Covers holds.
        private fun readValue(out: MutableMap<String, Any>, key: String) {
            when (val tag = input.readUnsignedByte().toChar()) {
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> input.readUnsignedShort()
                's', 'c' -> out[key] = u(input.readUnsignedShort())
                'e' -> { input.readUnsignedShort(); input.readUnsignedShort() }
                '@' -> out[key] = readAnnotation()
                '[' -> out[key] = List(input.readUnsignedShort()) {
                    val one = mutableMapOf<String, Any>()
                    readValue(one, "v")
                    one["v"] ?: ""
                }
                else -> error("element value tag $tag")
            }
        }

        private fun readAnnotation(): Pair<String, Map<String, Any>> {
            val type = u(input.readUnsignedShort())
            val values = mutableMapOf<String, Any>()
            repeat(input.readUnsignedShort()) { readValue(values, u(input.readUnsignedShort())) }
            return type to values
        }

        private fun readAnnotations(into: MutableList<Pair<String, Map<String, Any>>>) {
            repeat(input.readUnsignedShort()) { into.add(readAnnotation()) }
        }

        private fun coversOf(annotations: List<Pair<String, Map<String, Any>>>): List<CoversRef> {
            val all = annotations.filter { it.first == COVERS }.map { it.second }.toMutableList()
            annotations.filter { it.first == COVERS_LIST }.forEach { list ->
                (list.second["value"] as? List<*>)?.forEach { item ->
                    @Suppress("UNCHECKED_CAST")
                    all.add((item as Pair<String, Map<String, Any>>).second)
                }
            }
            return all.map { v ->
                val desc = v["value"] as String
                CoversRef(desc.removePrefix("L").removeSuffix(";"), (v["method"] as String?) ?: "")
            }
        }

        private fun skipAttributes() {
            repeat(input.readUnsignedShort()) { input.readUnsignedShort(); input.skipBytes(input.readInt()) }
        }

        fun read(): ClassInfo {
            check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
            input.readUnsignedShort(); input.readUnsignedShort()
            val count = input.readUnsignedShort()
            utf = arrayOfNulls(count)
            classRef = IntArray(count)
            indyBootstrap = IntArray(count) { -1 }
            handleRef = IntArray(count)
            memberClass = IntArray(count)
            var i = 1
            while (i < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf[i] = input.readUTF()
                    7 -> classRef[i] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.readUnsignedShort()
                    10, 11 -> { memberClass[i] = input.readUnsignedShort(); input.readUnsignedShort() }
                    18 -> { indyBootstrap[i] = input.readUnsignedShort(); input.readUnsignedShort() }
                    3, 4, 9, 12, 17 -> input.readInt()
                    5, 6 -> { input.readLong(); i++ }
                    15 -> { input.readUnsignedByte(); handleRef[i] = input.readUnsignedShort() }
                    else -> error("constant pool tag $tag")
                }
                i++
            }
            val access = input.readUnsignedShort()
            val name = cls(input.readUnsignedShort())!!
            val superName = cls(input.readUnsignedShort())
            val interfaces = List(input.readUnsignedShort()) { cls(input.readUnsignedShort())!! }
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort(); input.readUnsignedShort(); input.readUnsignedShort()
                skipAttributes()
            }
            val methods = List(input.readUnsignedShort()) {
                val mAccess = input.readUnsignedShort()
                val mName = u(input.readUnsignedShort())
                val mDesc = u(input.readUnsignedShort())
                val annotations = mutableListOf<Pair<String, Map<String, Any>>>()
                var code: ByteArray? = null
                repeat(input.readUnsignedShort()) {
                    val attr = u(input.readUnsignedShort())
                    val length = input.readInt()
                    when (attr) {
                        "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> readAnnotations(annotations)
                        "Code" -> {
                            input.readUnsignedShort(); input.readUnsignedShort()
                            val codeLength = input.readInt()
                            code = ByteArray(codeLength).also { input.readFully(it) }
                            input.skipBytes(length - 8 - codeLength)
                        }
                        else -> input.skipBytes(length)
                    }
                }
                MethodInfo(mAccess, mName, mDesc, annotations.map { it.first }, coversOf(annotations), code)
            }
            val components = mutableSetOf<String>()
            val innerAccess = mutableMapOf<String, Int>()
            val outer = mutableMapOf<String, String?>()
            val classAnnotations = mutableListOf<Pair<String, Map<String, Any>>>()
            var bootstrapMethods = IntArray(0)
            repeat(input.readUnsignedShort()) {
                val attr = u(input.readUnsignedShort())
                val length = input.readInt()
                when (attr) {
                    "Record" -> repeat(input.readUnsignedShort()) {
                        components.add(u(input.readUnsignedShort())); input.readUnsignedShort()
                        skipAttributes()
                    }
                    "InnerClasses" -> repeat(input.readUnsignedShort()) {
                        val inner = cls(input.readUnsignedShort())!!
                        val outerName = cls(input.readUnsignedShort())
                        input.readUnsignedShort()
                        innerAccess[inner] = input.readUnsignedShort()
                        outer[inner] = outerName
                    }
                    "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> readAnnotations(classAnnotations)
                    "BootstrapMethods" -> bootstrapMethods = IntArray(input.readUnsignedShort()) {
                        val handle = input.readUnsignedShort()
                        input.skipBytes(2 * input.readUnsignedShort())
                        handle
                    }
                    else -> input.skipBytes(length)
                }
            }
            val indyOwners = mutableMapOf<Int, String>()
            for (index in indyBootstrap.indices) {
                val bsm = indyBootstrap[index]
                if (bsm < 0 || bsm >= bootstrapMethods.size) continue
                cls(memberClass[handleRef[bootstrapMethods[bsm]]])?.let { indyOwners[index] = it }
            }
            return ClassInfo(name, access, superName, interfaces, methods, components, innerAccess, outer,
                    classAnnotations.map { it.first }, indyOwners)
        }
    }

    fun readClasses(dirs: Iterable<File>): Map<String, ClassInfo> = dirs.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                .map { ClassReader(it.readBytes()).read() }.toList()
    }.associateBy { it.name }

    // ---- The rule -------------------------------------------------------------------------------------------------------

    val EVENT_ROOT = "dev/iredsc/hadron/api/event/Event"
    val EVENT_PACKAGE = "dev/iredsc/hadron/api/event/"
    // Types only the loader port and the plugin host use; their Javadoc says plugins never do (docs/testing.md).
    val PORT_TYPES = setOf("Port", "Lifecycle", "Backend", "NetBackend", "Memory", "PluginContext")
    val TEST_ANNOTATIONS = setOf(
            "Ldev/iredsc/hadron/test/HadronTest;", "Lorg/junit/jupiter/api/Test;", "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/params/ParameterizedTest;", "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/TestTemplate;", "Lorg/junit/Test;")
    val DISABLED = setOf("Lorg/junit/jupiter/api/Disabled;", "Lorg/junit/Ignore;")

    fun simple(internal: String) = internal.substringAfterLast('/').replace('$', '.')

    class Coverage(val api: Map<String, ClassInfo>) {
        fun access(c: ClassInfo) = c.innerAccess[c.name] ?: c.access
        fun outerOf(c: ClassInfo) = c.outer[c.name]
        fun isNested(c: ClassInfo) = c.innerAccess.containsKey(c.name)

        /** Public, and so is every class around it; anonymous and local classes never are. */
        fun visible(c: ClassInfo): Boolean {
            if (access(c) and ACC_PUBLIC == 0) return false
            if (!isNested(c)) return true
            val o = outerOf(c) ?: return false
            return api[o]?.let { visible(it) } ?: false
        }

        fun supers(c: ClassInfo): List<ClassInfo> = (listOfNotNull(c.superName) + c.interfaces).mapNotNull { api[it] }

        fun allSupers(c: ClassInfo): Set<ClassInfo> {
            val out = linkedSetOf<ClassInfo>()
            val todo = ArrayDeque(supers(c))
            while (todo.isNotEmpty()) { val s = todo.removeFirst(); if (out.add(s)) todo.addAll(supers(s)) }
            return out
        }

        fun isEvent(c: ClassInfo): Boolean = c.name == EVENT_ROOT || allSupers(c).any { it.name == EVENT_ROOT }
        fun concreteEvent(c: ClassInfo) = visible(c) && isEvent(c) && access(c) and (ACC_ABSTRACT or ACC_INTERFACE) == 0

        fun portType(c: ClassInfo): Boolean {
            if (simple(c.name).substringAfterLast('.') in PORT_TYPES) return true
            val o = if (isNested(c)) outerOf(c) else null
            return o != null && api[o]?.let { portType(it) } == true
        }

        /** The non-event types whose methods need tests. */
        fun apiType(c: ClassInfo) = visible(c) && !c.name.startsWith(EVENT_PACKAGE) && !portType(c)
                && c.access and ACC_ANNOTATION == 0

        fun callable(c: ClassInfo, m: MethodInfo): Boolean {
            if (m.access and (ACC_SYNTHETIC or ACC_BRIDGE) != 0) return false
            if (m.name == "<init>" || m.name == "<clinit>") return false
            val public = m.access and ACC_PUBLIC != 0
            val protected = m.access and ACC_PROTECTED != 0 && access(c) and ACC_FINAL == 0
            return public || protected
        }

        /**
         * Written by the compiler, not by hand: a record's own equals, hashCode and toString (which hand the work to
         * ObjectMethods) and its plain accessors (which only read the field), and an enum's values and valueOf. Only
         * the bytecode can tell: a record method written in source, with the same name, is Hadron's code.
         */
        fun generated(c: ClassInfo, m: MethodInfo): Boolean {
            val code = m.code
            val record = c.superName == "java/lang/Record"
            if (record && code != null && ((m.name == "toString" && m.desc == "()Ljava/lang/String;")
                            || (m.name == "hashCode" && m.desc == "()I")
                            || (m.name == "equals" && m.desc == "(Ljava/lang/Object;)Z"))) {
                // aload_0, [aload_1,] invokedynamic #n 0 0, return: nothing but the ObjectMethods call.
                val at = if (code.size > 1 && code[1] == 0x2B.toByte()) 2 else 1
                return code.size == at + 6 && code[0] == 0x2A.toByte() && code[at] == 0xBA.toByte()
                        && c.indyBootstrapOwner[(code[at + 1].toInt() and 0xFF shl 8) or (code[at + 2].toInt() and 0xFF)] ==
                        "java/lang/runtime/ObjectMethods" && (code[at + 5].toInt() and 0xFF) in 0xAC..0xB0
            }
            if (record && code != null && m.name in c.recordComponents && m.desc.startsWith("()")) {
                // aload_0, getfield #n, return: the field and nothing else.
                return code.size == 5 && code[0] == 0x2A.toByte() && code[1] == 0xB4.toByte()
                        && (code[4].toInt() and 0xFF) in 0xAC..0xB0
            }
            if (c.superName == "java/lang/Enum" && (m.name == "values" && m.desc.startsWith("()")
                            || m.name == "valueOf" && m.desc.startsWith("(Ljava/lang/String;)"))) return true
            return false
        }

        fun overridesApi(c: ClassInfo, m: MethodInfo) = m.access and ACC_STATIC == 0 && allSupers(c).any { s ->
            apiType(s) && s.methods.any { it.name == m.name && it.desc == m.desc && callable(s, it) && it.access and ACC_STATIC == 0 }
        }

        val events: List<ClassInfo> = api.values.filter { concreteEvent(it) }.sortedBy { simple(it.name) }

        /** Each required method as (type, name, descriptor); an override counts as the method it overrides. */
        val methods: List<Triple<ClassInfo, String, String>> = api.values.filter { apiType(it) }.flatMap { c ->
            c.methods.filter { callable(c, it) && !generated(c, it) && !overridesApi(c, it) }.map { Triple(c, it.name, it.desc) }
        }.sortedWith(compareBy({ simple(it.first.name) }, { it.second }, { it.third }))
    }

    /** The class and every class it is nested in: JUnit skips a @Nested class's tests when any of them is @Disabled. */
    fun enclosing(c: ClassInfo, all: Map<String, ClassInfo>): List<ClassInfo> {
        val out = mutableListOf(c)
        var at = c
        while (true) {
            at = at.outer[at.name]?.let { all[it] } ?: return out
            if (at in out) return out
            out += at
        }
    }

    fun runCoverage(apiDirs: Iterable<File>, testDirs: Iterable<File>): List<String> {
        val api = readClasses(apiDirs)
        val tests = readClasses(testDirs)
        val rule = Coverage(api)
        val coveredEvents = mutableSetOf<String>()
        val coveredMethods = mutableSetOf<Triple<String, String, String>>()
        val problems = mutableListOf<String>()

        for (test in tests.values.sortedBy { it.name }) for (m in test.methods) {
            if (m.covers.isEmpty()) continue
            val where = "${simple(test.name)}.${m.name}"
            if (m.annotations.none { it in TEST_ANNOTATIONS }) {
                problems += "$where has @Covers but isn't a test: put @Covers on the @HadronTest or @Test method that proves it."
                continue
            }
            if (m.annotations.any { it in DISABLED } || enclosing(test, tests).any { c -> c.annotations.any { it in DISABLED } }) {
                problems += "$where is disabled, so it proves nothing: enable it or move its @Covers to a test that runs."
                continue
            }
            for (ref in m.covers) {
                val target = api[ref.type]
                val shown = simple(ref.type)
                if (target == null || !rule.visible(target)) {
                    problems += "$where: @Covers($shown.class) names a type outside Hadron's public API: name the Hadron event or API type the test proves."
                    continue
                }
                if (ref.method.isEmpty()) {
                    when {
                        rule.concreteEvent(target) -> coveredEvents += target.name
                        rule.isEvent(target) -> problems += "$where: @Covers($shown.class) names an abstract event, which never fires itself: name the concrete events the test fires (${rule.events.filter { e -> rule.allSupers(e).contains(target) }.joinToString { simple(it.name) }})."
                        else -> problems += "$where: @Covers($shown.class) names no method, and $shown isn't an event: write @Covers(value = $shown.class, method = \"...\") for each method the test proves."
                    }
                    continue
                }
                val hierarchy = listOf(target) + rule.allSupers(target)
                val found = hierarchy.flatMap { c -> c.methods.filter { it.name == ref.method && rule.callable(c, it) }.map { c to it } }
                if (found.isEmpty()) {
                    val names = hierarchy.flatMap { c -> c.methods.filter { rule.callable(c, it) && !rule.generated(c, it) }.map { it.name } }
                            .toSortedSet().joinToString()
                    problems += "$where: @Covers($shown.class, method = \"${ref.method}\") names a method $shown doesn't have: use one of $names."
                    continue
                }
                if (rule.concreteEvent(target)) coveredEvents += target.name
                found.forEach { (c, it) -> coveredMethods += Triple(c.name, it.name, it.desc) }
            }
        }

        for (e in rule.events) if (e.name !in coveredEvents) {
            val n = simple(e.name)
            problems += "$n has no test: add @Covers($n.class) to a test that fires it (as core does, or as a bridge would) and checks a listener receives it."
        }
        // One line per uncovered method name: a test covering a name covers its overloads.
        rule.methods.filter { (c, n, d) -> Triple(c.name, n, d) !in coveredMethods }
                .map { simple(it.first.name) to it.second }.distinct().forEach { (t, n) ->
                    problems += "$t.$n has no test: add @Covers(value = $t.class, method = \"$n\") to a test that calls it and checks what it did."
                }
        return problems
    }
}

// ---- The task -------------------------------------------------------------------------------------------------------

val coverageApi = files(extra["hadronCoverage.api"]!!)
val coverageTests = files(extra["hadronCoverage.tests"]!!)
val coverageDependsOn = extra["hadronCoverage.dependsOn"]!!
val coverageMarker = layout.buildDirectory.file("hadron-coverage/ok.txt")

val coverageCheck = tasks.register("coverageCheck") {
    group = "verification"
    description = "Fails unless every shipped event and public API method has a test with @Covers naming it."
    dependsOn(coverageDependsOn)
    inputs.files(coverageApi)
    inputs.files(coverageTests)
    outputs.file(coverageMarker)
    val apiDirs = coverageApi
    val testDirs = coverageTests
    val marker = coverageMarker
    doLast {
        val started = System.nanoTime()
        val problems = HadronCoverage.runCoverage(apiDirs.files, testDirs.files)
        val millis = (System.nanoTime() - started) / 1_000_000
        if (problems.isNotEmpty()) {
            throw GradleException("Coverage: ${problems.size} problem(s), found in $millis ms\n"
                    + problems.joinToString("\n") { "  $it" })
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("every event and API method is covered\n") }
        logger.lifecycle("Coverage: every event and API method has a test ($millis ms).")
    }
}

tasks.named("check") { dependsOn(coverageCheck) }
