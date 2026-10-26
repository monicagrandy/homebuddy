package chat.homebuddy.core.testing

import chat.homebuddy.core.network.SseParser
import chat.homebuddy.core.network.StreamEvent
import chat.homebuddy.core.network.parseStreamEvent
import java.io.File

/**
 * The backend's recorded responses (contracts/fixtures, written by scripts/export_contract.py).
 * Testing against them means a backend change that breaks the app fails these tests.
 */
object ContractFixtures {
    private val dir: File by lazy {
        val path = System.getProperty("homebuddy.contracts")
            ?: error("homebuddy.contracts isn't set; run tests through Gradle.")
        File(path, "fixtures").also { check(it.isDirectory) { "No contract fixtures at $it" } }
    }

    fun read(name: String): String = File(dir, name).readText()

    fun names(): List<String> = dir.list().orEmpty().sorted()

    /** A recorded answer stream (an .sse transcript), as the events the app receives. */
    fun streamEvents(name: String): List<StreamEvent> {
        val parser = SseParser()
        val data = read(name).lines().mapNotNull(parser::feed) + listOfNotNull(parser.finish())
        return data.mapNotNull(::parseStreamEvent)
    }
}
