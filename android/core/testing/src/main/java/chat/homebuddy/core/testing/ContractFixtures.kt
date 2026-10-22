package chat.homebuddy.core.testing

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
}
