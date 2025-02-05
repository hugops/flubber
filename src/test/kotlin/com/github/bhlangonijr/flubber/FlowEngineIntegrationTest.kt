package com.github.bhlangonijr.flubber

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.bhlangonijr.flubber.action.Action
import com.github.bhlangonijr.flubber.script.Script
import com.github.bhlangonijr.flubber.util.Util.Companion.loadResource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class FlowEngineIntegrationTest {

    private val scriptWithImports = Script.from(loadResource("/script-example-import.json"))
    private val scriptWithRest = Script.from(loadResource("/script-example-rest.json"))

    @Test
    fun `test imported actions`() {

        val engine = FlowEngine()
        val queue = ArrayBlockingQueue<Boolean>(2)

        val args = """
            {
              "session":{
                "user":"john"
              }
            }
        """

        scriptWithImports.register("waitOnDigits") {
            object : Action {
                override fun execute(context: JsonNode, args: Map<String, Any?>): Any {
                    val input = "1000"
                    args["set"]?.let { (context as ObjectNode).put(it as String, input) }
                    args["set"]?.let { (context as ObjectNode).put("COMPLETED", true) }
                    return input
                }
            }
        }

        val ctx = scriptWithImports.with(args)
        engine.run { ctx }.onComplete { queue.offer(ctx.globalArgs.get("COMPLETED").asBoolean()) }
        assertTrue(queue.poll(5, TimeUnit.SECONDS) == true)
    }

    @Test
    fun `test rest actions`() {

        val engine = FlowEngine()
        val queue = ArrayBlockingQueue<String>(2)

        val args = """
            {
              "session":{
                "user":"john",
                "url":"https://my-json-server.typicode.com/typicode/demo/profile"
              }
            }
        """
        scriptWithRest.register("say") {
            object : Action {
                override fun execute(context: JsonNode, args: Map<String, Any?>): Any {
                    queue.offer(args["text"]?.toString() ?: "")
                    return emptyMap<String, String>()
                }
            }
        }
        val ctx = scriptWithRest.with(args)
        engine.run { ctx }
        assertTrue(queue.poll(5, TimeUnit.SECONDS) == "Bot name: john typicode")
    }

}