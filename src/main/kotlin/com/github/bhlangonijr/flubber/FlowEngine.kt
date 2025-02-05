package com.github.bhlangonijr.flubber

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.bhlangonijr.flubber.Callback.Companion.THREAD_ID_FIELD
import com.github.bhlangonijr.flubber.Event.Companion.EVENT_NAME_FIELD
import com.github.bhlangonijr.flubber.action.ForEachResult
import com.github.bhlangonijr.flubber.action.MenuResult
import com.github.bhlangonijr.flubber.context.Context
import com.github.bhlangonijr.flubber.context.Context.Companion.ASYNC_FIELD
import com.github.bhlangonijr.flubber.context.Context.Companion.ELEMENTS_FIELD
import com.github.bhlangonijr.flubber.context.Context.Companion.EMPTY_OBJECT
import com.github.bhlangonijr.flubber.context.Context.Companion.GLOBAL_ARGS_FIELD
import com.github.bhlangonijr.flubber.context.Context.Companion.MAIN_THREAD_ID
import com.github.bhlangonijr.flubber.context.Context.Companion.MAX_STACK_SIZE
import com.github.bhlangonijr.flubber.context.Context.Companion.PATH_FIELD
import com.github.bhlangonijr.flubber.context.ExecutionState
import com.github.bhlangonijr.flubber.context.StackFrame
import com.github.bhlangonijr.flubber.script.*
import com.github.bhlangonijr.flubber.script.Script.Companion.ACTION_FIELD_NAME
import com.github.bhlangonijr.flubber.script.Script.Companion.DECISION_FIELD_NAME
import com.github.bhlangonijr.flubber.script.Script.Companion.DO_FIELD_NAME
import com.github.bhlangonijr.flubber.script.Script.Companion.ELSE_FIELD_NAME
import com.github.bhlangonijr.flubber.script.Script.Companion.EXIT_NODE_FIELD_NAME
import com.github.bhlangonijr.flubber.script.Script.Companion.ITERATION_RESULT_FIELD_NAME
import com.github.bhlangonijr.flubber.script.Script.Companion.SEQUENCE_FIELD_NAME
import com.github.bhlangonijr.flubber.script.Script.Companion.SET_ELEMENT_FIELD_NAME
import com.github.bhlangonijr.flubber.script.Script.Companion.SET_FIELD_NAME
import com.github.bhlangonijr.flubber.script.Script.Companion.SET_GLOBAL_FIELD_NAME
import com.github.bhlangonijr.flubber.util.NamedThreadFactory
import com.github.bhlangonijr.flubber.util.Util.Companion.bindVars
import com.github.bhlangonijr.flubber.util.Util.Companion.getId
import com.github.bhlangonijr.flubber.util.Util.Companion.jsonException
import com.github.bhlangonijr.flubber.util.Util.Companion.makeJson
import com.github.bhlangonijr.flubber.util.Util.Companion.nodeToMap
import com.github.bhlangonijr.flubber.util.Util.Companion.objectToNode
import mu.KotlinLogging
import java.util.concurrent.Executor
import java.util.concurrent.Executors


class FlowEngine(
    private val executor: Executor = Executors.newCachedThreadPool(
        NamedThreadFactory("executor-thread")
    )
) {

    private val logger = KotlinLogging.logger {}
    private val processMonitorMap = mutableMapOf<String, Context>()
    private val dispatcherExecutor: Executor = Executors.newSingleThreadExecutor(
        NamedThreadFactory("dispatcher-thread")
    )

    fun run(context: () -> Context): Context = run(context.invoke())

    fun run(context: Context): Context {

        if (context.threadStateValue(MAIN_THREAD_ID) != ExecutionState.NEW) {
            context.invokeExceptionListeners(ScriptStateException("Script already running"))
        } else {
            logger.debug { "Executing script ${context.script.name}" }
            dispatch(context)
        }
        return context

    }

    fun callback(context: Context, callback: Callback): Context {

        if (context.threadStateValue(callback.threadId) != ExecutionState.WAITING) {
            context.invokeExceptionListeners(ScriptStateException("Script not in awaiting state"))
        } else {
            logger.debug { "Callback script ${context.script.name}" }
            dispatch(context) {
                context.pop(callback.threadId)?.let { frame ->
                    val result: Any = if (callback.result.isObject) {
                        nodeToMap(callback.result)
                    } else callback.result.asText()
                    context.push(
                        callback.threadId,
                        StackFrame.create(
                            path = frame.path,
                            sequenceId = frame.sequence,
                            actionIndex = frame.actionIndex,
                            args = frame.args,
                            result = result,
                            sequenceType = false
                        )
                    )
                    val objectResult = objectToNode(result)
                    frame.args[SET_FIELD_NAME]?.asText()?.let { field ->
                        context.globalArgs.set<JsonNode>("${frame.path}$field", objectResult)
                    }
                    frame.args[SET_GLOBAL_FIELD_NAME]?.asText()?.let { field ->
                        context.globalArgs.set<JsonNode>(field, objectResult)
                    }
                    context.setThreadState(callback.threadId, ExecutionState.RUNNING)
                    logger.debug { "Callback resuming script ${context.script.name} and response: $result" }
                }
            }

        }
        return context
    }

    fun hook(context: Context, event: Event): Context {

        if (context.threadStateValue(MAIN_THREAD_ID) == ExecutionState.FINISHED) {
            context.invokeExceptionListeners(ScriptStateException("Script execution is already terminated"))
        } else {
            logger.debug { "Script hook ${event.name}" }
            dispatch(context) {
                context.script.hooks()
                    ?.filter { it.get(EVENT_NAME_FIELD)?.asText()?.equals(event.name) ?: false }
                    ?.let { hooks ->
                        val threadId = getId(event.name ?: "hook")
                        executeDoElse(hooks.first(), true, context, event.args, threadId)
                        context.setThreadState(threadId, ExecutionState.RUNNING)
                        logger.debug { "Script hook calling event ${event.name}" }
                    }
            }
        }
        return context
    }

    private fun dispatch(context: Context, runSequentially: () -> Any? = {}) {

        dispatcherExecutor.execute {
            runSequentially.invoke()
            if (processMonitorMap[context.id] == null) {
                processMonitorMap[context.id] = context
                executor.execute {
                    try {
                        logger.info { "Running: ${context.id}" }
                        execute(context)
                    } finally {
                        processMonitorMap.remove(context.id)
                    }
                }
            }
        }
    }

    private fun execute(context: Context) {

        var onExceptionBlock = false
        while (context.running) {
            for (threadId in context.state.fieldNames()) {
                runCatching {
                    val initialState = context.threadStateValue(threadId)
                    executeOneStep(context, threadId)
                    val finalState = context.threadStateValue(threadId)
                    if (initialState != finalState) {
                        context.invokeStateListeners(threadId, finalState)
                    }
                }.onFailure { exception ->
                    context.invokeExceptionListeners(ScriptException("Script error", exception))
                    context.script.exceptionally()
                        ?.let { action ->
                            if (onExceptionBlock.not()) {
                                onExceptionBlock = true
                                context.threadStack(threadId).removeAll()
                                executeDoElse(
                                    action,
                                    true,
                                    context,
                                    jsonException(exception),
                                    threadId
                                )
                            } else {
                                context.setThreadState(threadId, ExecutionState.FINISHED)
                            }
                        } ?: throw NotHandledScriptException("Not handled Script error", exception)
                }
            }
        }
    }

    private fun executeOneStep(context: Context, threadId: String) {

        if (context.running(threadId)) {
            logger.trace {
                "stack: ${context.stack.toPrettyString()}" +
                        ", \n args: ${context.globalArgs}"
            }
            context.next(threadId)?.let { frame ->
                logger.trace { "frame: $frame" }
                val actionPath = frame.previousFrame?.path ?: "$threadId-${frame.sequenceId}-"
                if (frame.sequenceType) {
                    val sequencePath = frame.previousFrame?.path ?: "$threadId-${frame.sequenceId}-"
                    frame.previousFrame?.let { lastFrame ->
                        val args = objectToNode(lastFrame.args) as ObjectNode
                        args[ELEMENTS_FIELD]?.takeIf { !it.isEmpty }?.let {
                            val setField = args[SET_ELEMENT_FIELD_NAME]?.asText() ?: ITERATION_RESULT_FIELD_NAME
                            val elements = it as ArrayNode
                            val element = elements.remove(0)
                            context.globalArgs.set<JsonNode>("${sequencePath}$setField", objectToNode(element))
                            if (!elements.isEmpty) {
                                context.push(
                                    threadId, StackFrame.create(
                                        path = sequencePath,
                                        sequenceId = frame.sequenceId,
                                        sequenceType = true,
                                        args = args
                                    )
                                )
                            }
                        }
                    }
                    context.push(
                        threadId, StackFrame.create(
                            path = actionPath,
                            sequenceId = frame.sequenceId
                        )
                    )
                } else {
                    val action = frame.node
                    val args = nodeToMap(action[GLOBAL_ARGS_FIELD] ?: EMPTY_OBJECT)
                    args[THREAD_ID_FIELD] = threadId
                    args[PATH_FIELD] = actionPath
                    val globalArgs = context.globalArgs
                    bindVars("", args, globalArgs)
                    bindVars(actionPath, args, globalArgs, true)
                    if (args[ASYNC_FIELD] == true) {
                        context.setThreadState(threadId, ExecutionState.WAITING)
                    }
                    val result = executeAction(context, action, args, globalArgs)
                    result?.let {
                        args[SET_FIELD_NAME]?.let { field ->
                            val fullPath = "$actionPath$field"
                            globalArgs.set<JsonNode>(fullPath, objectToNode(result))
                        }
                        args[SET_GLOBAL_FIELD_NAME]?.let { field ->
                            globalArgs.set<JsonNode>("$field", objectToNode(result))
                        }
                    }
                    context.push(
                        threadId, StackFrame.create(
                            path = actionPath,
                            sequenceId = frame.sequenceId,
                            actionIndex = frame.actionIndex,
                            args = objectToNode(args),
                            result = result
                        )
                    )
                    when {
                        result is Boolean ->
                            executeDoElse(action, result, context, null, threadId, actionPath)
                        result is ForEachResult ->
                            executeDoElse(action, true, context, null, threadId, actionPath, result.elementsNode)
                        result is MenuResult ->
                            executeDoElse(result.sequence, result.result, context, null, threadId, actionPath)
                        result is Map<*, *> && result[EXIT_NODE_FIELD_NAME] == true ->
                            context.setThreadState(threadId, ExecutionState.FINISHED)
                    }
                    context.invokeActionListeners(action, args, result)
                }
            }
        }
    }

    private fun executeAction(
        context: Context,
        action: JsonNode,
        args: MutableMap<String, Any?>,
        globalArgs: ObjectNode
    ): Any? {

        val actionName = when {
            action.hasNonNull(ACTION_FIELD_NAME) -> action[ACTION_FIELD_NAME].asText()
            action.hasNonNull(DECISION_FIELD_NAME) -> action[DECISION_FIELD_NAME].asText()
            else -> throw NotValidObjectException("Object is neither a valid action nor decision: $action")
        }
        val actionFunction = context.script.actionMap[actionName]
        return if (actionFunction == null) {
            throw ActionNotFoundException("Action is not registered: [$actionName]")
        } else {
            val result = actionFunction.execute(globalArgs, args)
            logger.debug { "Called [$actionName] with args [$args] and result: [$result] " }
            result
        }
    }

    private fun executeDoElse(
        action: JsonNode,
        result: Boolean,
        context: Context,
        blockArgs: JsonNode? = null,
        threadId: String,
        path: String? = null,
        iterateOverMap: JsonNode = makeJson()
    ) {
        val actionArgs = action.get(GLOBAL_ARGS_FIELD) ?: action
        when {
            context.threadStack(threadId).size() > MAX_STACK_SIZE ->
                throw ScriptStackOverflowException("Script stack overflow")
            result && actionArgs.hasNonNull(DO_FIELD_NAME) -> actionArgs.get(DO_FIELD_NAME)
            !result && actionArgs.hasNonNull(ELSE_FIELD_NAME) -> actionArgs.get(ELSE_FIELD_NAME)
            else -> throw ScriptStateException("Can't find a sequence to execute")
        }?.let { block ->
            block.get(SEQUENCE_FIELD_NAME)?.asText()?.let { sequenceId ->
                val initialPath = "$threadId-$sequenceId-"
                val args = nodeToMap(block.get(GLOBAL_ARGS_FIELD) ?: EMPTY_OBJECT)
                blockArgs?.let { bindVars("", args, it) }
                val globalArgs = context.globalArgs
                bindVars("", args, globalArgs)
                bindVars(path ?: initialPath, args, globalArgs, true)
                globalArgs.setAll<ObjectNode>(objectToNode(args) as ObjectNode)
                context.push(
                    threadId, StackFrame.create(
                        path = path?.let { "$it$sequenceId-" } ?: initialPath,
                        sequenceId = sequenceId,
                        sequenceType = true,
                        args = iterateOverMap
                    )
                )
            }
        }
    }
}