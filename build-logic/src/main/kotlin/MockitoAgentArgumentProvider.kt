package buildlogic

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

/** Supplies instrumentation before tests start, without Mockito's timed self-attachment. */
abstract class MockitoAgentArgumentProvider : CommandLineArgumentProvider {
    @get:Classpath
    abstract val agentClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        listOf("-javaagent:${agentClasspath.singleFile.absolutePath}")
}
