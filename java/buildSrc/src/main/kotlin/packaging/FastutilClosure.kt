package packaging

/** Conservative class closure: every referenced class retains its complete nested-class family. */
internal object FastutilClosure {
    private val edge = Regex("""^\s*(\S+)\s+->\s+(it\.unimi\.dsi\.fastutil\.[\w$]+(?:\.[\w$]+)*)\s+""", RegexOption.MULTILINE)

    fun references(report: String): Map<String, Set<String>> = edge.findAll(report)
        .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
        .mapValues { (_, dependencies) -> dependencies.toSet() }

    fun select(classes: Set<String>, seeds: Set<String>, dependencies: Map<String, Set<String>>): Set<String> {
        require(seeds.isNotEmpty()) { "No Fastutil references found in the runtime" }
        val families = classes.groupBy { it.substringBefore('$') }
        val selected = sortedSetOf<String>()
        val pending = ArrayDeque(seeds)
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!selected.add(name)) continue
            require(name in classes) { "Fastutil class missing from source artifact: $name" }
            pending.addAll(dependencies[name].orEmpty())
            pending.addAll(families.getValue(name.substringBefore('$')).filterNot { it in selected })
        }
        return selected
    }
}
