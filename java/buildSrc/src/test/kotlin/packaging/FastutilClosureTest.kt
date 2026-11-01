package packaging

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FastutilClosureTest {
    private val prefix = "it.unimi.dsi.fastutil."

    @Test
    fun retainsTransitiveAndNestedDependenciesButDropsUnrelatedClasses() {
        val classes = setOf("Map", "Map\$Iterator", "Map\$UnusedNested", "Base", "Helper", "Unrelated")
            .map { prefix + it }.toSet()
        val graph = mapOf(
            prefix + "Map" to setOf(prefix + "Base"),
            prefix + "Base" to setOf(prefix + "Map"),
            prefix + "Map\$UnusedNested" to setOf(prefix + "Helper"))
        assertEquals(classes - (prefix + "Unrelated"), FastutilClosure.select(classes, setOf(prefix + "Map"), graph))
    }

    @Test
    fun readsJdepsEdgesWithoutTreatingArchiveSummariesAsClasses() {
        val report = """
            renderer.jar -> not found
               example.Renderer -> it.unimi.dsi.fastutil.ints.IntList not found
               example.Renderer -> it.unimi.dsi.fastutil.ints.IntList${'$'}Iterator fastutil.jar
               example.Renderer -> java.lang.Object java.base
        """.trimIndent()
        assertEquals(mapOf("example.Renderer" to setOf(prefix + "ints.IntList", prefix + "ints.IntList${'$'}Iterator")),
            FastutilClosure.references(report))
    }

    @Test
    fun rejectsMissingTransitiveClassesInsteadOfProducingAnIncompleteArtifact() {
        val name = prefix + "Map"
        assertThrows(IllegalArgumentException::class.java) {
            FastutilClosure.select(setOf(name), setOf(name), mapOf(name to setOf(prefix + "Missing")))
        }
    }

    @Test
    fun rejectsEmptyAnalysisInsteadOfDroppingTheLibrarySilently() {
        assertThrows(IllegalArgumentException::class.java) {
            FastutilClosure.select(setOf(prefix + "Map"), emptySet(), emptyMap())
        }
    }
}
