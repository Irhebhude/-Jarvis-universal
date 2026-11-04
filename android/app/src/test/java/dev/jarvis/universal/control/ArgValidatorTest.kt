package dev.jarvis.universal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgValidatorTest {

    private val specs = listOf(
        ArgSpec("percent", ArgType.INTEGER, required = true, min = 0.0, max = 100.0),
        ArgSpec("stream", ArgType.ENUM, required = false, enumValues = listOf("music", "ring")),
        ArgSpec("confirm", ArgType.BOOLEAN, required = false),
        ArgSpec("note", ArgType.STRING, required = false, maxLength = 10)
    )

    @Test
    fun acceptsAValidRequest() {
        val args = Args.of("percent" to 50, "stream" to "music", "confirm" to true, "note" to "short")
        assertTrue(ArgValidator.validate(specs, args).isEmpty())
    }

    @Test
    fun rejectsUnknownArguments() {
        val args = Args.of("percent" to 10, "shell" to "rm -rf /")
        val problems = ArgValidator.validate(specs, args)
        assertEquals(1, problems.size)
        assertTrue(problems.first().contains("unexpected argument 'shell'"))
    }

    @Test
    fun rejectsMissingRequiredArgument() {
        val problems = ArgValidator.validate(specs, Args.of("stream" to "music"))
        assertTrue(problems.any { it.contains("missing required argument 'percent'") })
    }

    @Test
    fun rejectsOutOfRangeNumbers() {
        assertTrue(
            ArgValidator.validate(specs, Args.of("percent" to 140))
                .any { it.contains("between 0 and 100") }
        )
        assertTrue(
            ArgValidator.validate(specs, Args.of("percent" to -1))
                .any { it.contains("between 0 and 100") }
        )
    }

    @Test
    fun rejectsWrongTypes() {
        assertTrue(
            ArgValidator.validate(specs, Args.of("percent" to "abc"))
                .any { it.contains("whole number") }
        )
        assertTrue(
            ArgValidator.validate(specs, Args.of("percent" to 10, "confirm" to "maybe"))
                .any { it.contains("true or false") }
        )
    }

    @Test
    fun enforcesEnumAndLength() {
        assertTrue(
            ArgValidator.validate(specs, Args.of("percent" to 10, "stream" to "karaoke"))
                .any { it.contains("must be one of") }
        )
        assertTrue(
            ArgValidator.validate(specs, Args.of("percent" to 10, "note" to "0123456789ABC"))
                .any { it.contains("longer than 10") }
        )
    }

    @Test
    fun registryRejectsDuplicateAndInvalidIds() {
        val registry = ActionRegistry()
        registry.register(ActionSpec("battery_status", "read battery") { ActionResult.ok("ok") })
        var failed = false
        try {
            registry.register(ActionSpec("battery_status", "duplicate") { ActionResult.ok("ok") })
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)

        failed = false
        try {
            registry.register(ActionSpec("Bad Id!", "invalid") { ActionResult.ok("ok") })
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(listOf("battery_status"), registry.ids())
    }

    @Test
    fun resultCarriesStructuredOutcome() {
        // JSON serialisation is exercised on-device (org.json is a stub in JVM unit tests), so
        // this asserts the structured contract that both layers rely on.
        val ok = ActionResult.ok("done", mapOf("percent" to 42))
        assertTrue(ok.ok)
        assertEquals(42, ok.data["percent"])
        assertEquals(null, ok.error)
        assertTrue(!ok.needsConfirmation)

        val fail = ActionResult.fail("denied", "no")
        assertTrue(!fail.ok)
        assertEquals("denied", fail.error)
    }
}
