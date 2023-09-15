/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import org.jetbrains.kotlin.backend.common.atMostOne
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.DirectedGraphCondensationBuilder
import org.jetbrains.kotlin.backend.konan.DirectedGraphMultiNode
import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.jetbrains.kotlin.backend.konan.logMultiple
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.utils.addToStdlib.sumByLong

private val DataFlowIR.Node.isAlloc
    get() = this is DataFlowIR.Node.NewObject || this is DataFlowIR.Node.AllocInstance

private val DataFlowIR.Node.ir
    get() = when (this) {
        is DataFlowIR.Node.Call -> irCallSite
        is DataFlowIR.Node.AllocInstance -> irCallSite
        is DataFlowIR.Node.ArrayRead -> irCallSite
        is DataFlowIR.Node.FieldRead -> ir
        else -> null
    }

private val CallGraphNode.CallSite.arguments: List<DataFlowIR.Node>
    get() {
        return if (call is DataFlowIR.Node.NewObject) {
            (0..call.arguments.size).map {
                if (it == 0) node else call.arguments[it - 1].node
            }
        } else {
            (0..call.arguments.size).map {
                if (it < call.arguments.size) call.arguments[it].node else node
            }
        }
    }

internal object EscapeAnalysis {
    /*
     * The goal of escape analysis is to estimate lifetimes of all expressions in a program.
     * Possible lifetimes are:
     *   0. Stack        - an object is used only within its visibility scope within a function.
     *   1. Local        - an object is used only within a function.
     *   2. Return value - an object is either returned or set to a field of an object being returned.
     *   3. Parameter    - an object is set to a field of some parameters of a function.
     *   4. Global       - otherwise.
     * For now only Stack and Global lifetimes are supported by the codegen, so others will be pulled up to Global.
     *
     * The analysis is performed in two main stages - intraprocedural and interprocedural.
     * During intraprocedural analysis we remove all control flow related expressions and compute all possible
     * values of all variables within a function.
     * The goal of interprocedural analysis is to build points-to graph (object A references object B if and only if
     * there is a path from the node A to the node B). This is done by building call graph (using devirtualization
     * for more precise result). But in practice holding this condition both ways can be difficult and bad in terms of
     * performance, so the algorithm tries to ensure only one part: if object A references object B then there must be
     * a path from the node A to the node B, with that none of the constraints from the original program will be lost.
     * It is ok to add some additional constraints, as long as there are not too many of those.
     *
     * How do we exactly build the points-to graph out of the call graph?
     * 1. Build condensation of the call graph.
     * 2. Handle vertices of the resulting DAG in the reversed topological order (ensuring that all functions being called
     *    are already handled).
     * 3. For a strongly connected component build the points-to graph iteratively starting with empty graph
     *    (if the process is seemed to not be converging for some function, assume the pessimistic result).
     *
     * Escape analysis result of a function is not only lifetimes for all allocations of that function
     * but also a snippet of its points-to graph (it's a reduced version, basically, subgraph reachable from
     * the function's parameters).
     * Assuming the function has parameters P0, P1, .., Pn, where the last parameter is the return parameter,
     * it turns out that the snippet can be described as an array of relations of form
     *   v.f0.f1...fk -> w.g0.g1...gl where v, w - either one of the function's parameters or special
     * additional nodes called drains which will be introduced later; and f0, f1, .., fk, g0, g1, .., gl - fields.
     *
     * Building points-to graph:
     * 1. Seed it from the function's DataFlowIR.
     *     There are two kinds of edges:
     *         1) field. The subgraph for [a.f]:
     *               [a]
     *                | f
     *                V
     *              [a.f]
     *            Notice the label [f] on the edge.
     *         2) assignment. The subgraph for [a = b]:
     *               [a]
     *                |
     *                V
     *               [b]
     *            No labels on the edge.
     *     When calling a function, take its points-to graph snippet and embed it at the call site,
     *     replacing parameters with actual node arguments.
     * 2. Build the closure.
     *     Consider an assignment [a = b], and a usage of [a.f] somewhere. Since there is no order on nodes
     *     of DataFlowIR (sea of nodes), the conservative assumption has to be made - [b.f] is also being used
     *     at the same place as [a.f] is. Same applies for usages of [b.f].
     *     This reasoning leads to the following algorithm:
     *         Consider for the time being all assignment edges undirected and build connected components.
     *         Now, every field usage of any node within a component implies the same usage of any other node
     *         from that component, so the following transformation will be performed:
     *             1) Consider components one by one. Select a node which has no outgoing assignment edges,
     *                if there is no such a node, create additional node and add assignment edges from every node
     *                to it. Call this node a drain. Then move all beginnings of field edges from all nodes to
     *                the drain leaving the ends as is (this reflects the above consideration - any field usage
     *                can be applied to any node within a component).
     *             2) After drains creation and field edges moving there might emerge multi-edges (more than one
     *                field edge with the same label going to different components). The components these
     *                multi-edges are pointing at must be coalesced together (this is done either by creating
     *                a new drain or connecting one component's drain to the other). This operation must be
     *                performed until there are no more multi-edges.
     *     After the above transformation has been made, finally, simple lifetime propagation can be performed,
     *     seeing all edges directed.
     */

    // A special marker field for external types implemented in the runtime (mainly, arrays).
    // The types being passed to the constructor are not used in the analysis - just put there anything.
    private val intestinesField = DataFlowIR.Field(DataFlowIR.Type.Virtual, -1, "inte\$tines")

    // A special marker field for return values.
    // Basically we substitute [return x] with [ret.v@lue = x].
    // This is done in order to not handle return parameter somewhat specially.
    private val returnsValueField = DataFlowIR.Field(DataFlowIR.Type.Virtual, -2, "v@lue")

    // The less the higher an object escapes.
    object Depths {
        val INFINITY = 1_000_000
        val ROOT_SCOPE = 0
        val RETURN_VALUE = -1
        val PARAMETER = -2
        val GLOBAL = -3
    }

    private const val MaxGraphSize = 1_000_000

    private inline fun <reified T: Comparable<T>> Array<T>.sortedAndDistinct(): Array<T> {
        this.sort()
        if (this.isEmpty()) return this
        val unique = mutableListOf(this[0])
        for (i in 1 until this.size)
            if (this[i] != this[i - 1])
                unique.add(this[i])
        return unique.toTypedArray()
    }

    private class CompressedPointsToGraph(edges: Array<Edge>) {
        val edges = edges.sortedAndDistinct()

        sealed class NodeKind {
            abstract val absoluteIndex: Int

            object Return : NodeKind() {
                override val absoluteIndex = 0

                override fun equals(other: Any?) = other === this

                override fun toString() = "RET"
            }

            class Param(val index: Int) : NodeKind() {
                override val absoluteIndex: Int
                    get() = -1_000_000 + index

                override fun equals(other: Any?) = index == (other as? Param)?.index

                override fun toString() = "P$index"
            }

            class Drain(val index: Int) : NodeKind() {
                override val absoluteIndex: Int
                    get() = index + 1

                override fun equals(other: Any?) = index == (other as? Drain)?.index

                override fun toString() = "D$index"
            }

            companion object {
                fun parameter(index: Int, total: Int) =
                        if (index == total - 1)
                            Return
                        else
                            Param(index)
            }
        }

        class Node(val kind: NodeKind, val path: Array<DataFlowIR.Field>) : Comparable<Node> {
            override fun compareTo(other: Node): Int {
                if (kind.absoluteIndex != other.kind.absoluteIndex)
                    return kind.absoluteIndex.compareTo(other.kind.absoluteIndex)
                for (i in path.indices) {
                    if (i >= other.path.size)
                        return 1
                    if (path[i].index != other.path[i].index)
                        return path[i].index.compareTo(other.path[i].index)
                }
                if (path.size < other.path.size) return -1
                return 0
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Node) return false
                if (kind != other.kind || path.size != other.path.size)
                    return false
                for (i in path.indices)
                    if (path[i] != other.path[i])
                        return false
                return true
            }

            override fun toString() = debugString(null)

            fun debugString(root: String?) = buildString {
                append(root ?: kind.toString())
                path.forEach {
                    append('.')
                    append(it.name ?: "<no_name@${it.index}>")
                }
            }

            fun goto(field: DataFlowIR.Field?) = when (field) {
                null -> this
                else -> Node(kind, Array(path.size + 1) { if (it < path.size) path[it] else field })
            }

            companion object {
                fun parameter(index: Int, total: Int) = Node(NodeKind.parameter(index, total), path = emptyArray())
                fun drain(index: Int) = Node(NodeKind.Drain(index), path = emptyArray())
            }
        }

        class Edge(val from: Node, val to: Node) : Comparable<Edge> {
            override fun compareTo(other: Edge): Int {
                val fromCompareResult = from.compareTo(other.from)
                if (fromCompareResult != 0)
                    return fromCompareResult
                return to.compareTo(other.to)
            }

            override fun equals(other: Any?): Boolean {
                if (other === this) return true
                if (other !is Edge) return false
                return from == other.from && to == other.to
            }

            override fun toString() = "$from -> $to"

            companion object {
                fun pointsTo(param1: Int, param2: Int, totalParams: Int, kind: Int): Edge {
                    /*
                     * Values extracted from @PointsTo annotation.
                     *  kind            edge
                     *   1      p1            -> p2
                     *   2      p1            -> p2.intestines
                     *   3      p1.intestines -> p2
                     *   4      p1.intestines -> p2.intestines
                     */
                    if (kind <= 0 || kind > 4)
                        error("Invalid pointsTo kind: $kind")
                    val from = if (kind < 3)
                        Node.parameter(param1, totalParams)
                    else
                        Node(NodeKind.parameter(param1, totalParams), Array(1) { intestinesField })
                    val to = if (kind % 2 == 1)
                        Node.parameter(param2, totalParams)
                    else
                        Node(NodeKind.parameter(param2, totalParams), Array(1) { intestinesField })
                    return Edge(from, to)
                }
            }
        }
    }

    private class FunctionEscapeAnalysisResult(
            val numberOfDrains: Int,
            val pointsTo: CompressedPointsToGraph,
            escapes: Array<CompressedPointsToGraph.Node>
    ) {
        val escapes = escapes.sortedAndDistinct()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FunctionEscapeAnalysisResult) return false

            if (escapes.size != other.escapes.size) return false
            for (i in escapes.indices)
                if (escapes[i] != other.escapes[i]) return false

            if (pointsTo.edges.size != other.pointsTo.edges.size)
                return false
            for (i in pointsTo.edges.indices)
                if (pointsTo.edges[i] != other.pointsTo.edges[i])
                    return false
            return true
        }

        override fun toString(): String {
            val result = StringBuilder()
            result.appendLine("PointsTo:")
            pointsTo.edges.forEach { result.appendLine("    $it") }
            result.append("Escapes:")
            escapes.forEach {
                result.append(' ')
                result.append(it)
            }
            return result.toString()
        }

        companion object {
            fun fromBits(escapesMask: Int, pointsToMasks: List<Int>): FunctionEscapeAnalysisResult {
                val paramCount = pointsToMasks.size
                val edges = mutableListOf<CompressedPointsToGraph.Edge>()
                val escapes = mutableListOf<CompressedPointsToGraph.Node>()
                for (param1 in pointsToMasks.indices) {
                    if (escapesMask and (1 shl param1) != 0)
                        escapes.add(CompressedPointsToGraph.Node.parameter(param1, paramCount))
                    val curPointsToMask = pointsToMasks[param1]
                    for (param2 in pointsToMasks.indices) {
                        // Read a nibble at position [param2].
                        val pointsTo = (curPointsToMask shr (4 * param2)) and 15
                        if (pointsTo != 0)
                            edges.add(CompressedPointsToGraph.Edge.pointsTo(param1, param2, paramCount, pointsTo))
                    }
                }
                return FunctionEscapeAnalysisResult(
                        0, CompressedPointsToGraph(edges.toTypedArray()), escapes.toTypedArray())
            }

            fun optimistic() =
                    FunctionEscapeAnalysisResult(0, CompressedPointsToGraph(emptyArray()), emptyArray())

            fun pessimistic(numberOfParameters: Int) =
                    FunctionEscapeAnalysisResult(0, CompressedPointsToGraph(emptyArray()),
                            Array(numberOfParameters + 1) { CompressedPointsToGraph.Node.parameter(it, numberOfParameters + 1) })
        }
    }

    private data class FunctionBodyWithCallSites(val body: DataFlowIR.FunctionBody, val callSites: List<CallGraphNode.CallSite>)

    private class InterproceduralAnalysis(
            val context: Context,
            val generationState: NativeGenerationState,
            val callGraph: CallGraph,
            val moduleDFG: ModuleDFG,
            val externalModulesDFG: ExternalModulesDFG,
            val lifetimes: MutableMap<IrElement, Lifetime>,
            val propagateExiledToHeapObjects: Boolean
    ) {
        private val symbols = context.ir.symbols

        val rootSet = callGraph.rootSet

        private fun DataFlowIR.Type.resolved(): DataFlowIR.Type.Declared {
            if (this is DataFlowIR.Type.Declared) return this
            val hash = (this as DataFlowIR.Type.External).hash
            return externalModulesDFG.publicTypes[hash] ?: error("Unable to resolve exported type $hash")
        }

        val escapeAnalysisResults = mutableMapOf<DataFlowIR.FunctionSymbol.Declared, FunctionEscapeAnalysisResult>()
        // TODO: Probably this is too much - save only what's really needed for dynamic allocation.
        val pointsToGraphs = mutableMapOf<DataFlowIR.FunctionSymbol.Declared, PointsToGraph>()

        val averageNumberOfNodes = (callGraph.directEdges.keys.sumByLong {
            moduleDFG.functions[it]!!.body.allScopes.sumOf { it.nodes.size }.toLong()
        } / callGraph.directEdges.size).toInt()

        var failedToConvergeCount = 0

        fun analyze() {
            context.logMultiple {
                +"CALL GRAPH"
                callGraph.directEdges.forEach { (t, u) ->
                    +"    FUN $t"
                    u.callSites.forEach {
                        val label = when {
                            it.isVirtual -> "VIRTUAL"
                            callGraph.directEdges.containsKey(it.actualCallee) -> "LOCAL"
                            else -> "EXTERNAL"
                        }
                        +"        CALLS $label ${it.actualCallee}"
                    }
                    callGraph.reversedEdges[t]!!.forEach { +"        CALLED BY $it" }
                }
                +""
            }

            analyze(callGraph)

            // After allocating statically try to allocate dynamically (by passing outer stack to callees).
            tryAllocDynamically()

            with(stats) {
                println("Total functions analyzed: ${pointsToGraphs.size}")
                println("Managed to alloc on stack: $staticStackAllocsCount out of ${globalAllocsCount + staticStackAllocsCount}" +
                        " (${staticStackAllocsCount * 100.0 / (globalAllocsCount + staticStackAllocsCount)}%)")
                println("stackAllocs = ${staticStackAllocsCount}, heapAllocs = ${globalAllocsCount}")
                println("escapedAllocsCount = ${escapedAllocsCount * 100.0 / (globalAllocsCount + staticStackAllocsCount)}%")
                println("localAllocsCount = ${localAllocsCount * 100.0 / (globalAllocsCount + staticStackAllocsCount)}%")
                println("leakedToParametersAllocsCount = ${leakedToParametersAllocsCount * 100.0 / (globalAllocsCount + staticStackAllocsCount)}%")
                println("Total ea result size: $totalEAResultSize")
                println("Total points-to graph size: $totalPTGSize")
                println("Total data flow graph size: $totalDFGSize")
                println("Number of failed to converge components: $failedToConvergeCount")
            }

            statsPerFunction.entries.toList()
                    .sortedByDescending { it.value.escapedAllocsCount }
                    .forEach { (function, stats) ->
                        println(function)
                        with(stats) {
                            println("    stackAllocs = ${staticStackAllocsCount}, heapAllocs = ${globalAllocsCount}")
                            println("    escapedAllocsCount = ${escapedAllocsCount * 100.0 / (globalAllocsCount + staticStackAllocsCount)}%")
                            println("    localAllocsCount = ${localAllocsCount * 100.0 / (globalAllocsCount + staticStackAllocsCount)}%")
                            println("    leakedToParametersAllocsCount = ${leakedToParametersAllocsCount * 100.0 / (globalAllocsCount + staticStackAllocsCount)}%")
                        }
                    }

            context.logMultiple {
                with(stats) {
                    +("Managed to alloc on stack: $staticStackAllocsCount out of ${globalAllocsCount + staticStackAllocsCount}" +
                            " (${staticStackAllocsCount * 100.0 / (globalAllocsCount + staticStackAllocsCount)}%)")
                    +"Total ea result size: $totalEAResultSize"
                    +"Total points-to graph size: $totalPTGSize"
                    +"Total data flow graph size: $totalDFGSize"
                    +"Number of failed to converge components: $failedToConvergeCount"
                }
            }
        }

        private fun analyze(subCallGraph: CallGraph) {
            val condensation = DirectedGraphCondensationBuilder(subCallGraph).build()

            context.logMultiple {
                +"CONDENSATION"
                condensation.topologicalOrder.forEach { multiNode ->
                    +"    MULTI-NODE"
                    multiNode.nodes.forEach { +"        $it" }
                }
                +""
                +"CONDENSATION(DETAILED)"
                condensation.topologicalOrder.forEach { multiNode ->
                    +"    MULTI-NODE"
                    multiNode.nodes.forEach {
                        +"        $it"
                        subCallGraph.directEdges[it]!!.callSites
                                .filter { subCallGraph.directEdges.containsKey(it.actualCallee) }
                                .forEach { +"            CALLS ${it.actualCallee}" }
                        subCallGraph.reversedEdges[it]!!.forEach { +"            CALLED BY $it" }
                    }
                }
                +""
            }

            for (multiNode in condensation.topologicalOrder.reversed())
                analyze(multiNode)
        }

        private enum class ComputationState {
            NEW,
            PENDING,
            DONE
        }

        private class Stats {
            var globalAllocsCount = 0
            var staticStackAllocsCount = 0
            var leakedToParametersAllocsCount = 0
            var escapedAllocsCount = 0
            var localAllocsCount = 0
            var dynamicStackAllocAttemptsCount = 0
            var totalEAResultSize = 0
            var totalPTGSize = 0
            var totalDFGSize = 0
        }

        private val stats = Stats()

        private val statsPerFunction = mutableMapOf<DataFlowIR.FunctionSymbol.Declared, Stats>()

        private fun PointsToGraph.saveLifetimes() {
            pointsToGraphs[functionSymbol] = this
            val eaResult = escapeAnalysisResults[functionSymbol]!!
            stats.totalEAResultSize += eaResult.numberOfDrains + eaResult.escapes.size + eaResult.pointsTo.edges.size

            stats.totalPTGSize += allNodes.size
            stats.totalDFGSize += function.body.allScopes.sumOf { it.nodes.size }

            val localStats = Stats()
            statsPerFunction[functionSymbol] = localStats

            for (node in nodes.keys) {
                node.ir?.let {
                    val lifetime = lifetimeOf(node)

                    if (node.isAlloc) {
                        if (lifetime == Lifetime.GLOBAL) {
                            ++stats.globalAllocsCount
                            ++localStats.globalAllocsCount
                        }
                        if (lifetime == Lifetime.STACK) {
                            ++stats.staticStackAllocsCount
                            ++localStats.staticStackAllocsCount
                        }

                        val computedLifetime = lifetimeOf(nodes[node]!!)
                        if (computedLifetime == Lifetime.LOCAL) {
                            ++stats.localAllocsCount
                            ++localStats.localAllocsCount
                        } else if (computedLifetime == Lifetime.GLOBAL) {
                            ++stats.escapedAllocsCount
                            ++localStats.escapedAllocsCount
                        } else if (computedLifetime != Lifetime.STACK) {
                            ++stats.leakedToParametersAllocsCount
                            ++localStats.leakedToParametersAllocsCount
                        }

                        lifetimes[it] = lifetime
                    }
                }
            }
        }

        private fun analyze(multiNode: DirectedGraphMultiNode<DataFlowIR.FunctionSymbol.Declared>) {
            val nodes = multiNode.nodes.filter { moduleDFG.functions.containsKey(it) }.toMutableSet()

            context.logMultiple {
                +"Analyzing multiNode:\n    ${nodes.joinToString("\n   ") { it.toString() }}"
                nodes.forEach { from ->
                    +"DataFlowIR"
                    moduleDFG.functions[from]!!.debugOutput()
                    callGraph.directEdges[from]!!.callSites.forEach { to ->
                        +"CALL"
                        +"   from $from"
                        +"   to ${to.actualCallee}"
                    }
                }
            }

            if (nodes.isEmpty()) return

            val callStack = mutableListOf<DataFlowIR.FunctionSymbol.Declared>()
            val isRecursive = BooleanArray(nodes.size)
            val callSitesStartingRecursion = mutableMapOf<DataFlowIR.Node.Call, MutableSet<DataFlowIR.FunctionSymbol.Declared>>()

            fun computeRecursionParameters(): Pair<Int, Int> {
                val curFunction = callStack.peek()!!
                var size = moduleDFG.functions[curFunction]!!.body.allScopes.sumOf { it.nodes.size }
                var nestingFactor = 0
                for (callSite in callGraph.directEdges[curFunction]!!.callSites) {
                    val callee = callSite.actualCallee
                    if (callee !is DataFlowIR.FunctionSymbol.Declared || callee !in nodes)
                        continue
                    val index = callStack.indexOf(callee)
                    if (index >= 0) {
                        isRecursive[index] = true
                    } else {
                        callStack.push(callee)
                        isRecursive[callStack.size - 1] = false
                        var (subSize, subNestingFactor) = computeRecursionParameters()
                        if (isRecursive[callStack.size - 1]) {
                            callSitesStartingRecursion.getOrPut(callSite.call) { mutableSetOf() }
                                    .add(callee)
                            subSize += callee.parameters.size + 1 /* return value */ + 1 /* wrap to a loop */
                            ++subNestingFactor
                        }
                        callStack.pop()
                        size += subSize
                        if (nestingFactor < subNestingFactor)
                            nestingFactor = subNestingFactor
                    }
                }
                return size to nestingFactor
            }

            var minNestingFactor = Int.MAX_VALUE
            var minInlinedSize = Int.MAX_VALUE
            var bestNode: DataFlowIR.FunctionSymbol.Declared? = null

            for (node in nodes) {
                callSitesStartingRecursion.clear()
                callStack.push(node)
                isRecursive[0] = false
                val (size, nestingFactor) = computeRecursionParameters()
                callStack.pop()
                if (isRecursive[0]) {
                    if (nestingFactor < minNestingFactor || (nestingFactor == minNestingFactor && size < minInlinedSize)) {
                        minNestingFactor = nestingFactor
                        minInlinedSize = size
                        bestNode = node
                    }
                }
            }

            if (bestNode == null) {
                // No recursion.
                val function = nodes.single()
                var pointsToGraph = PointsToGraph(function, false)
                if (!intraproceduralAnalysis(pointsToGraph, MaxGraphSize)) {
                    ++failedToConvergeCount
                    escapeAnalysisResults[function] = FunctionEscapeAnalysisResult.pessimistic(function.parameters.size)
                    // Invalidate the points-to graph.
                    pointsToGraph = PointsToGraph(function, false).apply {
                        allNodes.forEach { it.depth = Depths.GLOBAL }
                    }
                }
                pointsToGraph.saveLifetimes()
            } else {
                callSitesStartingRecursion.clear()
                callStack.push(bestNode)
                val (size, _) = computeRecursionParameters()
                context.log { "Starting converting recursion to a loop from $bestNode" }
                context.log { "Call site starting recursion:" }
                callSitesStartingRecursion.keys.forEach {
                    context.log { "    $it ${it.callee}" }
                }
                val pointsToGraph = PointsToGraph(bestNode, true)
                if (size > 1_000 * averageNumberOfNodes
                        || !intraproceduralAnalysis(pointsToGraph, nodes, callSitesStartingRecursion, MaxGraphSize)
                ) {
                    ++failedToConvergeCount
                    val pointsToGraphs = analyzeComponentPessimistically(callGraph, multiNode)
                    pointsToGraphs.values.forEach { it.saveLifetimes() }
                } else {
                    pointsToGraph.saveLifetimes()
                    // Create new component without bestNode.
                    nodes.remove(bestNode)
                    if (nodes.isEmpty()) return
                    val directEdges = mutableMapOf<DataFlowIR.FunctionSymbol.Declared, CallGraphNode>()
                    val reversedEdges = mutableMapOf<DataFlowIR.FunctionSymbol.Declared, MutableList<DataFlowIR.FunctionSymbol.Declared>>()
                    val subCallGraph = CallGraph(directEdges, reversedEdges, emptyList(), emptyList())
                    for (node in nodes) {
                        reversedEdges[node] = mutableListOf()
                    }
                    for (node in nodes) {
                        val callGraphNode = CallGraphNode(subCallGraph, node)
                        directEdges[node] = callGraphNode
                        for (callSite in callGraph.directEdges[node]!!.callSites) {
                            val callee = callSite.actualCallee
                            if (callee !is DataFlowIR.FunctionSymbol.Declared || callee !in nodes)
                                continue
                            callGraphNode.callSites.add(callSite)
                            reversedEdges[callee]!!.add(node)
                        }
                    }
                    analyze(subCallGraph)
                }
            }
        }

        private fun analyzeComponentPessimistically(
                callGraph: CallGraph,
                multiNode: DirectedGraphMultiNode<DataFlowIR.FunctionSymbol.Declared>
        ): MutableMap<DataFlowIR.FunctionSymbol.Declared, PointsToGraph> {
            val nodes = multiNode.nodes.filter { moduleDFG.functions.containsKey(it) }
            val pointsToGraphs = mutableMapOf<DataFlowIR.FunctionSymbol.Declared, PointsToGraph>()
            val computationStates = mutableMapOf<DataFlowIR.FunctionSymbol.Declared, ComputationState>()
            nodes.forEach { computationStates[it] = ComputationState.NEW }
            val toAnalyze = nodes.toMutableList()
            while (toAnalyze.isNotEmpty()) {
                val function = toAnalyze.peek()!!
                val state = computationStates[function]!!
                val callSites = callGraph.directEdges[function]!!.callSites
                when (state) {
                    ComputationState.NEW -> {
                        computationStates[function] = ComputationState.PENDING
                        for (callSite in callSites) {
                            val callee = callSite.actualCallee
                            val calleeComputationState = computationStates[callee]
                            if (callSite.isVirtual
                                    || callee !is DataFlowIR.FunctionSymbol.Declared // An external call.
                                    || calleeComputationState == null // A call to a function from other component.
                                    || calleeComputationState == ComputationState.DONE // Already analyzed.
                            ) {
                                continue
                            }

                            if (calleeComputationState == ComputationState.PENDING) {
                                // A cycle - break it by assuming nothing about the callee.
                                // This is not the callee's final result - it will be recomputed later in the loop.
                                escapeAnalysisResults[callee] = FunctionEscapeAnalysisResult.pessimistic(callee.parameters.size)
                            } else {
                                computationStates[callee] = ComputationState.NEW
                                toAnalyze.push(callee)
                            }
                        }
                    }

                    ComputationState.PENDING -> {
                        toAnalyze.pop()
                        computationStates[function] = ComputationState.DONE
                        val pointsToGraph = PointsToGraph(function, false)
                        if (intraproceduralAnalysis(pointsToGraph, MaxGraphSize))
                            pointsToGraphs[function] = pointsToGraph
                        else {
                            // TODO: suboptimal. May be it is possible somehow handle the entire component at once?
                            context.log {
                                "WARNING: Escape analysis for $function seems not to be converging." +
                                        " Assuming conservative results."
                            }
                            escapeAnalysisResults[function] = FunctionEscapeAnalysisResult.pessimistic(function.parameters.size)
                            // Invalidate the points-to graph.
                            pointsToGraphs[function] = PointsToGraph(function, false).apply {
                                allNodes.forEach { it.depth = Depths.GLOBAL }
                            }
                        }
                    }

                    ComputationState.DONE -> {
                        toAnalyze.pop()
                    }
                }
            }

            return pointsToGraphs
        }

        // TODO: Describe the algorithm.
        private enum class StackType {
            INNER,
            OUTER
        }

        @JvmInline
        @Suppress("RESERVED_MEMBER_INSIDE_VALUE_CLASS")
        private value class ParametersBitMask(val rawData: IntArray) {
            override fun equals(other: Any?) = other is ParametersBitMask && rawData.contentEquals(other.rawData)

            override fun hashCode() = rawData.contentHashCode()

            fun set(index: Int) {
                rawData[index / 32] = rawData[index / 32] or (1 shl (index % 32))
            }

            fun get(index: Int) = rawData[index / 32] and (1 shl (index % 32)) != 0

            companion object {
                fun empty(symbol: DataFlowIR.FunctionSymbol) =
                        ParametersBitMask(IntArray(symbol.parameters.size / 32 + 1))

                inline fun construct(symbol: DataFlowIR.FunctionSymbol, locality: (Int) -> Boolean) =
                        empty(symbol).apply {
                            for (index in 0..symbol.parameters.size)
                                if (locality(index))
                                    set(index)
                        }
            }
        }

        private data class FunctionWithParametersLocality(val symbol: DataFlowIR.FunctionSymbol, val locality: ParametersBitMask) {
            override fun equals(other: Any?) =
                    other === this || (other is FunctionWithParametersLocality && symbol == other.symbol && locality.rawData.contentEquals(other.locality.rawData))

            override fun hashCode() = symbol.hashCode() * 31 + locality.rawData.contentHashCode()

            companion object {
                fun allGlobal(symbol: DataFlowIR.FunctionSymbol) =
                        FunctionWithParametersLocality(symbol, ParametersBitMask(IntArray(symbol.parameters.size / 32 + 1)))

                inline fun construct(symbol: DataFlowIR.FunctionSymbol, locality: (Int) -> Boolean) =
                        FunctionWithParametersLocality(symbol, ParametersBitMask.construct(symbol, locality))
            }
        }

        private interface StackSelectionStrategy {
            fun getStackType(functionWithLocality: FunctionWithParametersLocality, callSite: CallGraphNode.CallSite): StackType
        }

        private fun tryAllocDynamically() {
            val strategy = comeUpWithStackSelectionStrategy()

            computeDynamicStackSizes(strategy)
        }

        private fun comeUpWithStackSelectionStrategy(): StackSelectionStrategy {
            //return buildBottomStackSelectionStrategy()
            return buildTopStackSelectionStrategy()
        }

        // Always tries to pass own stack.
        private fun buildBottomStackSelectionStrategy(): StackSelectionStrategy =
                object : StackSelectionStrategy {
                    override fun getStackType(functionWithLocality: FunctionWithParametersLocality, callSite: CallGraphNode.CallSite) = StackType.INNER
                }

        // Always tries to pass outer stack.
        private fun buildTopStackSelectionStrategy(): StackSelectionStrategy {
            /*
             * Consideration: if the locality = 0, meaning no parameter is local with respect to the outer stack,
             * it is safe to pass on the local stack instead of the outer (if some object can be allocated on the outer stack,
             * it can be allocated on the local stack as well because of the locality).
             */
            val computationStates = mutableMapOf<FunctionWithParametersLocality, ComputationState>()
            val stackTypes = mutableMapOf<Pair<FunctionWithParametersLocality, CallGraphNode.CallSite>, StackType>()
            var computationItems = rootSet.map { rootSymbol ->
                FunctionWithParametersLocality.allGlobal(rootSymbol).also { rootWithLocality ->
                    computationStates[rootWithLocality] = ComputationState.NEW
                    val callSites = callGraph.directEdges[rootSymbol]!!.callSites.filter {
                        !it.isVirtual && callGraph.directEdges.containsKey(it.actualCallee) // TODO: What about external callees?
                    }
                    for (callSite in callSites) {
                        stackTypes[Pair(rootWithLocality, callSite)] = StackType.INNER
                    }
                }
            }.toMutableList()
            do {
                val nextComputationItems = mutableListOf<FunctionWithParametersLocality>()
                while (computationItems.isNotEmpty()) {
                    val item = computationItems.peek()!!
                    val state = computationStates[item]!!
                    val pointsToGraph = pointsToGraphs[item.symbol] ?: error("No points-to graph for ${item.symbol}")
                    val callSites = callGraph.directEdges[item.symbol]!!.callSites.filter {
                        !it.isVirtual && callGraph.directEdges.containsKey(it.actualCallee) // TODO: What about external callees?
                    }
                    when (state) {
                        ComputationState.NEW -> {
                            computationStates[item] = ComputationState.PENDING
                            for (callSite in callSites) {
                                val key = Pair(item, callSite)
                                if (stackTypes[key] == StackType.INNER) continue
                                if (pointsToGraph.nodes[callSite.node]!!.startDepth > Depths.ROOT_SCOPE) {
                                    stackTypes[key] = StackType.INNER
                                    continue
                                }
                                val calleeWithLocality = computeCalleeLocality(pointsToGraph, item.locality, callSite, StackType.OUTER)
                                val calleeComputationState = computationStates[calleeWithLocality]
                                if (calleeComputationState == ComputationState.DONE) {
                                    stackTypes[key] = StackType.OUTER
                                    continue
                                }
                                if (calleeComputationState == ComputationState.PENDING)
                                    stackTypes[key] = StackType.INNER
                                else {
                                    stackTypes[key] = StackType.OUTER
                                    computationStates[calleeWithLocality] = ComputationState.NEW
                                    computationItems.push(calleeWithLocality)
                                }
                            }
                        }

                        ComputationState.PENDING -> {
                            computationItems.pop()
                            computationStates[item] = ComputationState.DONE

                            for (callSite in callSites) {
                                val stackType = stackTypes[Pair(item, callSite)]!!
                                val calleeWithLocality = computeCalleeLocality(pointsToGraph, item.locality, callSite, stackType)
                                val calleeComputationState = computationStates[calleeWithLocality]
                                when (stackType) {
                                    StackType.OUTER ->
                                        if (calleeComputationState != ComputationState.DONE)
                                            error("Should be handled")

                                    StackType.INNER -> {
                                        if (calleeComputationState == ComputationState.DONE) continue
//                                        if (computationStates[calleeWithLocality] == ComputationState.PENDING)
//                                            error("A cycle detected")
                                        if (calleeComputationState == null) {
                                            computationStates[calleeWithLocality] = ComputationState.NEW
                                            nextComputationItems.add(calleeWithLocality)
                                        }
                                    }
                                }
                            }
                        }

                        ComputationState.DONE -> {
                            computationItems.pop()
                        }
                    }
                }
                computationItems = nextComputationItems
            } while (computationItems.isNotEmpty())

            return object : StackSelectionStrategy {
                override fun getStackType(functionWithLocality: FunctionWithParametersLocality, callSite: CallGraphNode.CallSite) =
                        stackTypes[Pair(functionWithLocality, callSite)]
                                ?: error("No stack type for [locality=${functionWithLocality.locality}] ${functionWithLocality.symbol}" +
                                        " at call site ${callSite.call.callee}")
            }
        }

        class DynamicAllocStats {
            var maxAllocs = 0
            var sumAllocs = 0
            var count = 0
        }

        private fun computeDynamicStackSizes(strategy: StackSelectionStrategy) {
            val computationStates = mutableMapOf<FunctionWithParametersLocality, ComputationState>()
            // How much a function would allocate from the outer stack, including its callees.
            val outerStackSizes = mutableMapOf<FunctionWithParametersLocality, Int>()
            val dynamicAllocStats = mutableMapOf<DataFlowIR.FunctionSymbol, DynamicAllocStats>()
            var computationItems = rootSet.map { rootSymbol ->
                FunctionWithParametersLocality.allGlobal(rootSymbol).also {
                    computationStates[it] = ComputationState.NEW
                }
            }.toMutableList()
            do {
                val nextComputationItems = mutableListOf<FunctionWithParametersLocality>()
                while (computationItems.isNotEmpty()) {
                    val item = computationItems.peek()!!
                    val state = computationStates[item]!!
                    val pointsToGraph = pointsToGraphs[item.symbol] ?: error("No points-to graph for ${item.symbol}")
                    val callSites = callGraph.directEdges[item.symbol]!!.callSites.filter {
                        !it.isVirtual && callGraph.directEdges.containsKey(it.actualCallee) // TODO: What about external callees?
                    }
                    when (state) {
                        ComputationState.NEW -> {
                            computationStates[item] = ComputationState.PENDING
                            for (callSite in callSites) {
                                if (strategy.getStackType(item, callSite) == StackType.INNER) continue
                                val calleeWithLocality = computeCalleeLocality(pointsToGraph, item.locality, callSite, StackType.OUTER)
                                val calleeComputationState = computationStates[calleeWithLocality]
                                if (calleeComputationState == ComputationState.DONE) continue
                                if (calleeComputationState == ComputationState.PENDING)
                                    error("A cycle detected")
                                computationStates[calleeWithLocality] = ComputationState.NEW
                                computationItems.push(calleeWithLocality)
                            }
                        }

                        ComputationState.PENDING -> {
                            computationItems.pop()
                            computationStates[item] = ComputationState.DONE

                            val localsToAllocOnOuterStack = findLocalsToAllocOnOuterStack(pointsToGraph, item.locality)
                            val allocatedOnOuterStackCount = localsToAllocOnOuterStack.count {
                                // Skip arrays for now. TODO: Support.
                                (it.node as? DataFlowIR.Node.NewObject)?.constructedType?.resolved()?.irClass?.let { arrayItemSizeOf(it) } == null
                            }
                            outerStackSizes[item] = allocatedOnOuterStackCount + callSites.filter { strategy.getStackType(item, it) == StackType.OUTER }
                                    .sumOf {
                                        val calleeWithLocality = computeCalleeLocality(pointsToGraph, item.locality, it, StackType.OUTER)
                                        outerStackSizes[calleeWithLocality] ?: error("Should be computed")
                                    }

                            val stats = dynamicAllocStats.getOrPut(item.symbol) { DynamicAllocStats() }
                            ++stats.count
                            if (allocatedOnOuterStackCount > stats.maxAllocs)
                                stats.maxAllocs = allocatedOnOuterStackCount
                            stats.sumAllocs += allocatedOnOuterStackCount

//                        println("Computed for ${item.symbol}")
//                        println("    ${item.locality.rawData.contentToString()}")
//                        outerStackSizes.keys.forEach { k ->
//                            if (k.symbol == item.symbol) {
//                                println("        ${k.locality.rawData.contentToString()}")
//                            }
//                        }

                            for (callSite in callSites) {
                                if (strategy.getStackType(item, callSite) == StackType.OUTER) continue
                                val calleeWithLocality = computeCalleeLocality(pointsToGraph, item.locality, callSite, StackType.INNER)
                                val calleeComputationState = computationStates[calleeWithLocality]
                                if (calleeComputationState == ComputationState.DONE) continue
//                                if (computationStates[calleeWithLocality] == ComputationState.PENDING)
//                                    error("A cycle detected")
                                if (calleeComputationState == null) {
                                    computationStates[calleeWithLocality] = ComputationState.NEW
                                    nextComputationItems.add(calleeWithLocality)
                                }
                            }
                        }

                        ComputationState.DONE -> {
                            computationItems.pop()
                        }
                    }
                }
                computationItems = nextComputationItems
            } while (computationItems.isNotEmpty())

            // How much at maximum a function would allocate from own stack dynamically, including its callees.
            val innerStackSizes = mutableMapOf<DataFlowIR.FunctionSymbol, Int>()
            for (functionWithLocality in outerStackSizes.keys) {
                val (symbol, locality) = functionWithLocality
                val pointsToGraph = pointsToGraphs[symbol]!!
                val callSites = callGraph.directEdges[symbol]!!.callSites.filter {
                    !it.isVirtual && callGraph.directEdges.containsKey(it.actualCallee)
                }
                val curInnerStackSize = callSites.filter { strategy.getStackType(functionWithLocality, it) == StackType.INNER }
                        .sumOf {
                            val calleeWithLocality = computeCalleeLocality(pointsToGraph, locality, it, StackType.INNER)
                            outerStackSizes[calleeWithLocality] ?: error("Should be computed")
                        }
                val prevInnerStackSize = innerStackSizes[symbol]
                if (prevInnerStackSize == null || prevInnerStackSize < curInnerStackSize)
                    innerStackSizes[symbol] = curInnerStackSize
            }
            innerStackSizes.toList().sortedByDescending { it.second }.forEach { (symbol, size) -> println("$symbol : $size") }
            println("Dynamic stack allocations (analyzed ${dynamicAllocStats.size} functions):")
            println("    max: ${dynamicAllocStats.values.sumOf { it.maxAllocs } * 100.0 / (stats.globalAllocsCount + stats.staticStackAllocsCount)}%")
            println("    avg: ${dynamicAllocStats.values.sumOf { it.sumAllocs * 1.0 / it.count } * 100.0 / (stats.globalAllocsCount + stats.staticStackAllocsCount)}%")
        }

        private fun computeCalleeLocality(
                callerPointsToGraph: PointsToGraph,
                callerLocality: ParametersBitMask,
                callSite: CallGraphNode.CallSite,
                stackType: StackType
        ): FunctionWithParametersLocality {
            require(stackType == StackType.INNER || callerPointsToGraph.nodes[callSite.node]!!.startDepth <= Depths.ROOT_SCOPE) {
                "Calls inside a loop should pass the local stack, not the outer"
            }
            val arguments = callSite.arguments
            if (callSite.call is DataFlowIR.Node.NewObject) {
                require(arguments.size == callSite.actualCallee.parameters.size) {
                    "Unexpected number of arguments to call (and alloc) ${callSite.actualCallee}" +
                            " from ${callerPointsToGraph.functionSymbol}: ${callSite.actualCallee.parameters.size} != ${arguments.size}"
                }
            } else {
                require(arguments.size == callSite.actualCallee.parameters.size + 1) {
                    "Unexpected number of arguments to call (w/o alloc) ${callSite.actualCallee}" +
                            " from ${callerPointsToGraph.functionSymbol}: ${callSite.actualCallee.parameters.size} != ${arguments.size - 1}"
                }
            }
            return FunctionWithParametersLocality.construct(callSite.actualCallee) { index ->
                if (callSite.call is DataFlowIR.Node.NewObject && index == arguments.size)
                    return@construct true // Actual constructor call returns Unit.
                val arg = callerPointsToGraph.nodes[arguments[index]]
                        ?: return@construct true // TODO: Looks like it - noone can reference it.
                // TODO: What if an object with STACK kind actually won't be allocated on the stack?
                when (stackType) {
                    StackType.INNER -> arg.kind == PointsToGraphNodeKind.STACK
                    StackType.OUTER -> when (arg.kind) {
                        PointsToGraphNodeKind.GLOBAL -> false // Just escapes.
                        PointsToGraphNodeKind.STACK -> true // Local for either stack type.
                        PointsToGraphNodeKind.LOCAL -> false // Escapes to the outer scope - dangerous to alloc on the stack, even dynamically.
                        PointsToGraphNodeKind.PARAMETER, PointsToGraphNodeKind.RETURN_VALUE -> {
                            (arg.startDepth <= Depths.ROOT_SCOPE) && // Otherwise the above comment applies here as well.
                                    (0..callerPointsToGraph.functionSymbol.parameters.size).all { paramIndex ->
                                        !arg.referencedBy.get(paramIndex) || callerLocality.get(paramIndex)
                                    }
                        }
                    }
                }
            }
        }

        private fun findLocalsToAllocOnOuterStack(pointsToGraph: PointsToGraph, locality: ParametersBitMask) =
                pointsToGraph.nodes.values.filter { ptgNode ->
//                    if (ptgNode.node?.let { it.isAlloc && it.ir != null } == true) {
//                        println("${pointsToGraph.ids[ptgNode.node]}: ${ptgNode.kind}")
//                    }
                    ptgNode.node?.let { it.isAlloc && it.ir != null } == true &&
                            ptgNode.startDepth <= Depths.ROOT_SCOPE &&
                            (ptgNode.kind == PointsToGraphNodeKind.PARAMETER || ptgNode.kind == PointsToGraphNodeKind.RETURN_VALUE) &&
                            (0..pointsToGraph.functionSymbol.parameters.size).all { paramIndex ->
                                !ptgNode.referencedBy.get(paramIndex) || locality.get(paramIndex)
                            }
                }

        private fun arrayLengthOf(node: DataFlowIR.Node): Int? =
                (node as? DataFlowIR.Node.SimpleConst<*>)?.value as? Int
                // In case of several possible values, it's unknown what is used.
                // TODO: if all values are constants which are less limit?
                        ?: (node as? DataFlowIR.Node.Variable)
                                ?.values?.singleOrNull()?.let { arrayLengthOf(it.node) }

        private val pointerSize = generationState.runtime.pointerSize

        private fun arrayItemSizeOf(irClass: IrClass): Int? = when (irClass.symbol) {
            symbols.array -> pointerSize
            symbols.booleanArray -> 1
            symbols.byteArray -> 1
            symbols.charArray -> 2
            symbols.shortArray -> 2
            symbols.intArray -> 4
            symbols.floatArray -> 4
            symbols.longArray -> 8
            symbols.doubleArray -> 8
            else -> null
        }

        private fun arraySize(itemSize: Int, length: Int): Long =
                pointerSize /* typeinfo */ + 4 /* size */ + itemSize * length.toLong()

        private fun intraproceduralAnalysis(pointsToGraph: PointsToGraph, maxAllowedGraphSize: Int) =
                intraproceduralAnalysis(pointsToGraph, setOf(pointsToGraph.functionSymbol), emptyMap(), maxAllowedGraphSize)

        // TODO: May be this function should return PointsToGraph?
        private fun intraproceduralAnalysis(
                pointsToGraph: PointsToGraph,
                component: Set<DataFlowIR.FunctionSymbol.Declared>,
                callSitesStartingRecursion: Map<DataFlowIR.Node.Call, Set<DataFlowIR.FunctionSymbol.Declared>>,
                maxAllowedGraphSize: Int
        ): Boolean {
            val functionSymbol = pointsToGraph.functionSymbol
            context.log { "Processing function $functionSymbol" }
            context.log { "Before calls analysis" }
            pointsToGraph.log()
            pointsToGraph.logDigraph(false)

            if (!pointsToGraph.processCalls(callGraph.directEdges[functionSymbol]!!.callSites, component,
                            callSitesStartingRecursion, maxAllowedGraphSize)
            ) {
                return false
            }

            context.log { "After calls analysis" }
            pointsToGraph.log()
            pointsToGraph.logDigraph(false)

            // Build transitive closure.
            val eaResult = pointsToGraph.buildClosure()

            context.log { "After closure building" }
            pointsToGraph.log()
            pointsToGraph.logDigraph(true)

            escapeAnalysisResults[functionSymbol] = eaResult

            return true
        }

        private fun DataFlowIR.FunctionSymbol.resolved(): DataFlowIR.FunctionSymbol {
            if (this is DataFlowIR.FunctionSymbol.External)
                return externalModulesDFG.publicFunctions[this.hash] ?: this
            return this
        }

        private fun getExternalFunctionEAResult(callSite: CallGraphNode.CallSite): FunctionEscapeAnalysisResult {
            val callee = callSite.actualCallee.resolved()

            val calleeEAResult = if (callSite.isVirtual) {
                context.log { "A virtual call: $callee" }
                //FunctionEscapeAnalysisResult.pessimistic(callee.parameters.size)
                FunctionEscapeAnalysisResult.optimistic()
            } else {
                context.log { "An external call: $callee" }
                if (callee.name?.startsWith("kfun:kotlin.") == true
                        // TODO: Is it possible to do it in a more fine-grained fashion?
                        && !callee.name.startsWith("kfun:kotlin.native.concurrent")
                        && !callee.name.startsWith("kfun:kotlin.concurrent")) {
                    context.log { "A function from K/N runtime - can use annotations" }
                    FunctionEscapeAnalysisResult.fromBits(
                            callee.escapes ?: 0,
                            (0..callee.parameters.size).map { callee.pointsTo?.elementAtOrNull(it) ?: 0 }
                    )
                } else {
                    context.log { "An unknown function - assume pessimistic result" }
                    //FunctionEscapeAnalysisResult.pessimistic(callee.parameters.size)
                    FunctionEscapeAnalysisResult.optimistic()
                }
            }

            context.logMultiple {
                +"Escape analysis result"
                +calleeEAResult.toString()
                +""
            }
            return calleeEAResult
        }

        private enum class PointsToGraphNodeKind {
            STACK,
            LOCAL,
            PARAMETER,
            RETURN_VALUE,
            GLOBAL
        }

        private sealed class PointsToGraphEdge(val node: PointsToGraphNode) {
            class Assignment(node: PointsToGraphNode) : PointsToGraphEdge(node)

            class Field(node: PointsToGraphNode, val field: DataFlowIR.Field) : PointsToGraphEdge(node)
        }

        private class PointsToGraphNode(functionSymbol: DataFlowIR.FunctionSymbol, val startDepth: Int, val node: DataFlowIR.Node?) {
            val edges = mutableListOf<PointsToGraphEdge>()
            val reversedEdges = mutableListOf<PointsToGraphEdge.Assignment>()

            fun addAssignmentEdge(to: PointsToGraphNode) {
                edges += PointsToGraphEdge.Assignment(to)
                to.reversedEdges += PointsToGraphEdge.Assignment(this)
            }

            private val fields = mutableMapOf<DataFlowIR.Field, PointsToGraphNode>()

            fun getFieldNode(field: DataFlowIR.Field, graph: PointsToGraph) =
                    fields.getOrPut(field) { graph.newNode().also { edges += PointsToGraphEdge.Field(it, field) } }

            var depth = startDepth

            val kind
                get() = when {
                    depth == Depths.GLOBAL -> PointsToGraphNodeKind.GLOBAL
                    depth == Depths.PARAMETER -> PointsToGraphNodeKind.PARAMETER
                    depth == Depths.RETURN_VALUE -> PointsToGraphNodeKind.RETURN_VALUE
                    depth != startDepth -> PointsToGraphNodeKind.LOCAL
                    else -> PointsToGraphNodeKind.STACK
                }

            var forcedLifetime: Lifetime? = null

            lateinit var drain: PointsToGraphNode
            val isDrain get() = this == drain

            val actualDrain: PointsToGraphNode
                get() = drain.let {
                    if (it.isDrain) it
                    // Flip to the real drain as it is done in the disjoint sets algorithm,
                    // to reduce the time spent in this function.
                    else it.actualDrain.also { drain = it }
                }
            val isActualDrain get() = this == actualDrain

            val referencedBy = ParametersBitMask.empty(functionSymbol)
        }

        private data class ArrayStaticAllocation(val node: PointsToGraphNode, val irClass: IrClass, val size: Int)

        private enum class EdgeDirection {
            FORWARD,
            BACKWARD
        }

        private inner class PointsToGraph(val functionSymbol: DataFlowIR.FunctionSymbol.Declared, val shouldConvertRecursionToLoop: Boolean) {
            val function = moduleDFG.functions[functionSymbol]!!
            val nodes = mutableMapOf<DataFlowIR.Node, PointsToGraphNode>()

            val allNodes = mutableListOf<PointsToGraphNode>()

            fun newNode(depth: Int, node: DataFlowIR.Node?) =
                    PointsToGraphNode(functionSymbol, depth, node).also { allNodes.add(it) }
            fun newNode() = newNode(Depths.INFINITY, null)
            fun newDrain() = newNode().also { it.drain = it }

            val returnsNode = newNode(Depths.RETURN_VALUE, null)

            /*
             * Of all escaping nodes there are some "starting" - call them origins.
             * Consider a variable [v], which is assigned with two values - [a] and [b].
             * Now assume [a] escapes (written to a global, for instance). This implies that [v] also escapes,
             * but [b] doesn't, albeit [v] (an escaping node) references it. It's because [v] is not an escape origin.
             */
            // The origins of escaping.
            val escapeOrigins = mutableSetOf<PointsToGraphNode>()
            // Nodes reachable from either of escape origins going along all edges (assignment and/or field).
            val reachableFromEscapeOrigins = mutableSetOf<PointsToGraphNode>()
            // Nodes referencing any escape origin only by assignment edges.
            val referencingEscapeOrigins = mutableSetOf<PointsToGraphNode>()

            fun escapes(node: PointsToGraphNode) = node in reachableFromEscapeOrigins || node in referencingEscapeOrigins

            val ids = mutableMapOf<DataFlowIR.Node, Int>()

            fun lifetimeOf(node: DataFlowIR.Node) = nodes[node]!!.let { it.forcedLifetime ?: lifetimeOf(it) }

            fun lifetimeOf(node: PointsToGraphNode) =
                    if (escapes(node))
                        Lifetime.GLOBAL
                    else when (node.kind) {
                        PointsToGraphNodeKind.GLOBAL -> Lifetime.GLOBAL

                        PointsToGraphNodeKind.PARAMETER -> Lifetime.ARGUMENT

                        PointsToGraphNodeKind.STACK -> {
                            // A value doesn't escape from its scope - it can be allocated on the stack.
                            Lifetime.STACK
                        }

                        PointsToGraphNodeKind.LOCAL -> {
                            // A value is neither stored into a global nor into any parameter nor into the return value -
                            // it can be allocated locally.
                            Lifetime.LOCAL
                        }

                        PointsToGraphNodeKind.RETURN_VALUE -> {
                            when {
                                // If a value is explicitly returned.
                                node.node?.let { it in returnValues } == true -> Lifetime.RETURN_VALUE

                                // A value is stored into a field of the return value.
                                else -> Lifetime.INDIRECT_RETURN_VALUE
                            }
                        }
                    }

            private val returnValues: Set<DataFlowIR.Node> = function.body.returns.values.map { it.node }.toSet()

            val parameters: Array<PointsToGraphNode>
            val parameterVariables = mutableMapOf<DataFlowIR.Node.Parameter, PointsToGraphNode>()
            val parameterValues = mutableMapOf<DataFlowIR.Node.Parameter, DataFlowIR.Node>()
            val returnVariables = mutableMapOf<DataFlowIR.FunctionSymbol.Declared, PointsToGraphNode>()

            fun DataFlowIR.Node.toPTGNode(): PointsToGraphNode? = when {
                this == DataFlowIR.Node.Null -> null
                this is DataFlowIR.Node.Parameter -> {
                    val value = parameterValues[this]
                    if (value != null)
                        value.toPTGNode()
                    else parameterVariables[this] ?: nodes[this]!!
                }
                else -> nodes[this]!!
            }

            fun DataFlowIR.Edge.toPTGNode() = node.toPTGNode()

            fun convertBody(body: DataFlowIR.FunctionBody, returnsNode: PointsToGraphNode) {
                val startIndex = ids.size
                (listOf(body.rootScope) + body.allScopes.flatMap { it.nodes })
                        .forEachIndexed { index, node -> ids[node] = startIndex + index }

                val nothing = moduleDFG.symbolTable.mapClassReferenceType(context.ir.symbols.nothing.owner).resolved()
                body.forEachNonScopeNode { node ->
                    when (node) {
                        is DataFlowIR.Node.FieldWrite -> {
                            val receiver = node.receiver?.toPTGNode()
                            val value = node.value.toPTGNode()
                            if (value != null) {
                                if (receiver == null)
                                    escapeOrigins.add(value)
                                else
                                    receiver.getFieldNode(node.field, this).addAssignmentEdge(value)
                            }
                        }

                        is DataFlowIR.Node.Singleton -> {
                            val type = node.type.resolved()
                            if (type != nothing)
                                escapeOrigins.add(node.toPTGNode()!!)
                        }

                        is DataFlowIR.Node.FieldRead -> {
                            val readResult = node.toPTGNode()!!
                            val receiver = node.receiver?.toPTGNode()
                            if (receiver == null)
                                escapeOrigins.add(readResult)
                            else
                                readResult.addAssignmentEdge(receiver.getFieldNode(node.field, this))
                        }

                        is DataFlowIR.Node.ArrayWrite -> {
                            val array = node.array.toPTGNode()!!
                            val value = node.value.toPTGNode()
                            if (value != null)
                                array.getFieldNode(intestinesField, this).addAssignmentEdge(value)
                        }

                        is DataFlowIR.Node.ArrayRead -> {
                            val array = node.array.toPTGNode()!!
                            val readResult = node.toPTGNode()!!
                            readResult.addAssignmentEdge(array.getFieldNode(intestinesField, this))
                        }

                        is DataFlowIR.Node.Variable -> {
                            val variable = node.toPTGNode()!!
                            for (value in node.values)
                                value.toPTGNode()?.let { variable.addAssignmentEdge(it) }
                        }
                        is DataFlowIR.Node.AllocInstance,
                        is DataFlowIR.Node.Call,
                        is DataFlowIR.Node.Const,
                        is DataFlowIR.Node.FunctionReference,
                        is DataFlowIR.Node.Null,
                        is DataFlowIR.Node.Parameter,
                        is DataFlowIR.Node.Scope -> Unit
                    }
                }

                body.throws.values.forEach { escapeOrigins.add(it.toPTGNode()!!) }
                body.returns.values.forEach { returnValue ->
                    returnValue.toPTGNode()?.let {
                        returnsNode.getFieldNode(returnsValueField, this).addAssignmentEdge(it)
                    }
                }
            }

            init {
                fun traverseAndConvert(node: DataFlowIR.Node, depth: Int) {
                    when (node) {
                        DataFlowIR.Node.Null -> return
                        is DataFlowIR.Node.Scope -> node.nodes.forEach { traverseAndConvert(it, depth + 1) }
                        else -> {
                            val actualDepth = when (node) {
                                is DataFlowIR.Node.Parameter -> Depths.PARAMETER
                                in returnValues -> Depths.RETURN_VALUE
                                else -> depth
                            }
                            nodes[node] = newNode(actualDepth, node)
                        }
                    }
                }

                val body = function.body

                traverseAndConvert(body.rootScope, Depths.ROOT_SCOPE - 1)

                parameters = Array(body.parameters.size + 1) {
                    if (it == body.parameters.size)
                        returnsNode
                    else nodes[body.parameters[it]]!!
                }
                if (shouldConvertRecursionToLoop) {
                    for (index in body.parameters.indices) {
                        val parameterVariable = newNode()
                        parameterVariable.addAssignmentEdge(parameters[index])
                        parameterVariables[body.parameters[index]] = parameterVariable
                    }
                    returnVariables[functionSymbol] = returnsNode
                }

                convertBody(body, returnsNode)

                val escapes = functionSymbol.escapes
                if (escapes != null) {
                    for (parameter in body.parameters)
                        if (escapes and (1 shl parameter.index) != 0)
                            escapeOrigins += nodes[parameter]!!
                    if (escapes and (1 shl body.parameters.size) != 0)
                        escapeOrigins += returnsNode
                }
            }

            private fun nodeToStringWhole(node: DataFlowIR.Node) = DataFlowIR.Function.nodeToString(node, ids)

            private fun nodeToString(node: DataFlowIR.Node) = ids[node].toString()

            fun log() = context.logMultiple {
                +"POINTS-TO GRAPH"
                +"NODES"
                val tempIds = mutableMapOf<PointsToGraphNode, Int>()
                var tempIndex = 0
                allNodes.forEach {
                    if (it.node == null)
                        tempIds[it] = tempIndex++
                }
                allNodes.forEach {
                    val tempId = tempIds[it]
                    +"    ${lifetimeOf(it)} ${it.depth} ${if (it in escapeOrigins) "ESCAPES" else ""} ${it.node?.let { nodeToString(it) } ?: "t$tempId"}"
                    +(it.node?.let { nodeToStringWhole(it) } ?: "        t$tempId")
                }
            }

            fun logDigraph(
                    markDrains: Boolean,
                    nodeFilter: (PointsToGraphNode) -> Boolean = { true },
                    nodeLabel: ((PointsToGraphNode) -> String)? = null
            ) = context.logMultiple {
                +"digraph {"
                val tempIds = mutableMapOf<PointsToGraphNode, Int>()
                var tempIndex = 0
                allNodes.forEach {
                    if (it.node == null)
                        tempIds[it] = tempIndex++
                }

                fun PointsToGraphNode.format() =
                        (nodeLabel?.invoke(this) ?:
                        (if (markDrains && isDrain) "d" else "")
                        + (node?.let { "n${ids[it]!!}" } ?: "t${tempIds[this]}")) +
                                "[d=$depth,${if (this in escapeOrigins) "eo" else if (escapes(this)) "e" else ""}]"

                for (from in allNodes) {
                    if (!nodeFilter(from)) continue
                    for (it in from.edges) {
                        val to = it.node
                        if (!nodeFilter(to)) continue
                        when (it) {
                            is PointsToGraphEdge.Assignment ->
                                +"    \"${from.format()}\" -> \"${to.format()}\";"
                            is PointsToGraphEdge.Field ->
                                +"    \"${from.format()}\" -> \"${to.format()}\" [ label=\"${it.field.name}\"];"
                        }
                    }
                }
                +"}"
            }

            val originalCalls = mutableMapOf<DataFlowIR.Node.Call, DataFlowIR.Node.Call>()

            fun FunctionBodyWithCallSites.deepCopy(): FunctionBodyWithCallSites {
                val copiedNodes = mutableMapOf<DataFlowIR.Node, DataFlowIR.Node>()

                fun DataFlowIR.Node.copy(): DataFlowIR.Node = copiedNodes.getOrPut(this) {
                    when (this) {
                        DataFlowIR.Node.Null -> DataFlowIR.Node.Null
                        is DataFlowIR.Node.Parameter -> this // Don't copy the parameters, only the body.
                        is DataFlowIR.Node.SimpleConst<*> -> DataFlowIR.Node.SimpleConst(type, value)
                        is DataFlowIR.Node.Const -> DataFlowIR.Node.Const(type)
                        is DataFlowIR.Node.NewObject -> DataFlowIR.Node.NewObject(callee, arguments.map { DataFlowIR.Edge(it.castToType) }, constructedType, null)
                        is DataFlowIR.Node.StaticCall -> DataFlowIR.Node.StaticCall(callee, arguments.map { DataFlowIR.Edge(it.castToType) }, receiverType, returnType, null)
                        is DataFlowIR.Node.VtableCall -> DataFlowIR.Node.VtableCall(callee, receiverType, calleeVtableIndex,
                                arguments.map { DataFlowIR.Edge(it.castToType) }, returnType, null)
                        is DataFlowIR.Node.ItableCall -> DataFlowIR.Node.ItableCall(callee, receiverType, interfaceId, calleeItableIndex,
                                arguments.map { DataFlowIR.Edge(it.castToType) }, returnType, null)
                        is DataFlowIR.Node.VirtualCall -> DataFlowIR.Node.VirtualCall(callee, arguments.map { DataFlowIR.Edge(it.castToType) }, receiverType, returnType, null)
                        is DataFlowIR.Node.Call -> DataFlowIR.Node.Call(callee, arguments.map { DataFlowIR.Edge(it.castToType) }, returnType, null)
                        is DataFlowIR.Node.Singleton -> DataFlowIR.Node.Singleton(type, constructor, arguments?.map { DataFlowIR.Edge(it.castToType) })
                        is DataFlowIR.Node.AllocInstance -> DataFlowIR.Node.AllocInstance(type, null)
                        is DataFlowIR.Node.FunctionReference -> DataFlowIR.Node.FunctionReference(symbol, type, returnType)
                        is DataFlowIR.Node.FieldRead -> DataFlowIR.Node.FieldRead(receiver?.let { DataFlowIR.Edge(it.castToType) }, field, type, null)
                        is DataFlowIR.Node.FieldWrite -> DataFlowIR.Node.FieldWrite(receiver?.let { DataFlowIR.Edge(it.castToType) }, field, DataFlowIR.Edge(value.castToType))
                        is DataFlowIR.Node.ArrayRead -> DataFlowIR.Node.ArrayRead(callee, DataFlowIR.Edge(array.castToType), DataFlowIR.Edge(index.castToType), type, null)
                        is DataFlowIR.Node.ArrayWrite -> DataFlowIR.Node.ArrayWrite(callee, DataFlowIR.Edge(array.castToType), DataFlowIR.Edge(index.castToType), DataFlowIR.Edge(value.castToType), type)
                        is DataFlowIR.Node.Variable -> DataFlowIR.Node.Variable(values.map { DataFlowIR.Edge(it.castToType) }, type, kind)
                        is DataFlowIR.Node.Scope -> DataFlowIR.Node.Scope(depth, emptyList())
                    }
                }

                for (scope in body.allScopes)
                    (scope.copy() as DataFlowIR.Node.Scope).nodes.addAll(scope.nodes.map { it.copy() })
                body.forEachNonScopeNode { node ->
                    val copy = copiedNodes[node]!!
                    when (node) {
                        is DataFlowIR.Node.Call -> {
                            (copy as DataFlowIR.Node.Call).arguments.forEachIndexed { index, edge ->
                                edge.node = copiedNodes[node.arguments[index].node]!!
                            }
                            originalCalls[copy] = node
                        }
                        is DataFlowIR.Node.Singleton -> (copy as DataFlowIR.Node.Singleton).arguments?.forEachIndexed { index, edge ->
                            edge.node = copiedNodes[node.arguments!![index].node]!!
                        }
                        is DataFlowIR.Node.FieldRead -> {
                            (copy as DataFlowIR.Node.FieldRead).receiver?.node = copiedNodes[node.receiver!!.node]!!
                        }
                        is DataFlowIR.Node.FieldWrite -> {
                            (copy as DataFlowIR.Node.FieldWrite).receiver?.node = copiedNodes[node.receiver!!.node]!!
                            copy.value.node = copiedNodes[node.value.node]!!
                        }
                        is DataFlowIR.Node.ArrayRead -> {
                            (copy as DataFlowIR.Node.ArrayRead).array.node = copiedNodes[node.array.node]!!
                            copy.index.node = copiedNodes[node.index.node]!!
                        }
                        is DataFlowIR.Node.ArrayWrite -> {
                            (copy as DataFlowIR.Node.ArrayWrite).array.node = copiedNodes[node.array.node]!!
                            copy.index.node = copiedNodes[node.index.node]!!
                            copy.value.node = copiedNodes[node.value.node]!!
                        }
                        is DataFlowIR.Node.Variable -> (copy as DataFlowIR.Node.Variable).values.forEachIndexed { index, edge ->
                            edge.node = copiedNodes[node.values[index].node]!!
                        }
                        else -> Unit
                    }
                }

                return FunctionBodyWithCallSites(
                        body = with(body) {
                            DataFlowIR.FunctionBody(
                                    copiedNodes[rootScope] as DataFlowIR.Node.Scope,
                                    allScopes.map { copiedNodes[it] as DataFlowIR.Node.Scope },
                                    Array(parameters.size) { copiedNodes[parameters[it]] as DataFlowIR.Node.Parameter },
                                    copiedNodes[returns] as DataFlowIR.Node.Variable,
                                    copiedNodes[throws] as DataFlowIR.Node.Variable
                            )
                        },
                        callSites = callSites.map { callSite ->
                            with(callSite) {
                                val copiedCall = copiedNodes[call] as? DataFlowIR.Node.Call
                                        ?: DataFlowIR.Node.Call(
                                                call.callee,
                                                call.arguments.map { DataFlowIR.Edge(copiedNodes[it.node]!!, it.castToType) },
                                                call.returnType,
                                                call.irCallSite,
                                        )
                                CallGraphNode.CallSite(copiedCall, copiedNodes[node]!!, isVirtual, actualCallee)
                            }
                        }
                )
            }

            fun processCalls(
                    callSites: List<CallGraphNode.CallSite>,
                    component: Set<DataFlowIR.FunctionSymbol.Declared>,
                    callSitesStartingRecursion: Map<DataFlowIR.Node.Call, Set<DataFlowIR.FunctionSymbol.Declared>>,
                    maxAllowedGraphSize: Int,
            ): Boolean {
                for (callSite in callSites) {
                    val callee = callSite.actualCallee
                    val calleeEAResult = callGraph.directEdges[callee]?.symbol
//                            ?.takeIf { !callSite.isVirtual }
//                            ?.let {
//                                escapeAnalysisResults[it] ?: FunctionEscapeAnalysisResult.pessimistic(it.parameters.size)
//                            }
                            //?.takeIf { !callSite.isVirtual }
                            ?.let {
                                escapeAnalysisResults[it] ?: (
                                        //if (callSite.isVirtual)
                                        FunctionEscapeAnalysisResult.optimistic())
//                                        else
//                                            FunctionEscapeAnalysisResult.pessimistic(it.parameters.size))
                            }
                            ?: getExternalFunctionEAResult(callSite)
                    val originalCall = originalCalls[callSite.call] ?: callSite.call
                    val startsRecursion = callSitesStartingRecursion[originalCall]?.contains(callee) == true
                    processCall(callSite, calleeEAResult, startsRecursion, component, callSitesStartingRecursion, maxAllowedGraphSize)

                    if (allNodes.size > maxAllowedGraphSize)
                        return false
                }

                return true
            }

            fun processCall(
                    callSite: CallGraphNode.CallSite,
                    calleeEscapeAnalysisResult: FunctionEscapeAnalysisResult,
                    startsRecursion: Boolean,
                    component: Set<DataFlowIR.FunctionSymbol.Declared>,
                    callSitesStartingRecursion: Map<DataFlowIR.Node.Call, Set<DataFlowIR.FunctionSymbol.Declared>>,
                    maxAllowedGraphSize: Int
            ) {
                val call = callSite.call
                val calleeSymbol = callSite.actualCallee
                val arguments = callSite.arguments
                context.logMultiple {
                    +"Processing callSite"
                    +nodeToStringWhole(call)
                    +"Actual callee: $calleeSymbol"
                }

                val returnVariable = returnVariables[calleeSymbol]
                when {
                    calleeSymbol !is DataFlowIR.FunctionSymbol.Declared -> // An external function.
                        inlineCalleeEscapeAnalysisResult(call, arguments, calleeEscapeAnalysisResult)

                    returnVariable != null -> {
                        context.log { "A recursive call: saving arguments to parameter values" }
                        val callee = moduleDFG.functions[calleeSymbol]!!
                        for (parameter in callee.body.parameters) {
                            val index = parameter.index
                            arguments[index].toPTGNode()?.let {
                                parameterVariables[parameter]!!.addAssignmentEdge(it)
                            }
                        }
                        if (call !is DataFlowIR.Node.NewObject)
                            arguments[calleeSymbol.parameters.size].toPTGNode()?.addAssignmentEdge(returnVariable)
                    }

                    shouldConvertRecursionToLoop && calleeSymbol in component ->
                        inlineCall(call, calleeSymbol, arguments, startsRecursion, component, callSitesStartingRecursion, maxAllowedGraphSize)

                    else -> // A simple call (with no recursion involved whatsoever).
                        inlineCalleeEscapeAnalysisResult(call, arguments, calleeEscapeAnalysisResult)
                }
            }

            fun inlineCall(
                    call: DataFlowIR.Node.Call,
                    calleeSymbol: DataFlowIR.FunctionSymbol.Declared,
                    arguments: List<DataFlowIR.Node>,
                    startsRecursion: Boolean,
                    component: Set<DataFlowIR.FunctionSymbol.Declared>,
                    callSitesStartingRecursion: Map<DataFlowIR.Node.Call, Set<DataFlowIR.FunctionSymbol.Declared>>,
                    maxAllowedGraphSize: Int,
            ) {
                val callee = moduleDFG.functions[calleeSymbol]!!
                val bodyWithCallSites = FunctionBodyWithCallSites(callee.body, callGraph.directEdges[calleeSymbol]!!.callSites)
                val (copiedBody, copiedCallSites) = bodyWithCallSites.deepCopy()
                val localReturnsNode = newNode()
                if (startsRecursion) {
                    for (index in copiedBody.parameters.indices) {
                        val parameterVariable = newNode()
                        arguments[index].toPTGNode()?.let { parameterVariable.addAssignmentEdge(it) }
                        parameterVariables[copiedBody.parameters[index]] = parameterVariable
                    }
                    returnVariables[calleeSymbol] = localReturnsNode
                } else {
                    for (parameter in copiedBody.parameters)
                        parameterValues[parameter] = arguments[parameter.index]
                }

                copiedBody.forEachNonScopeNode {
                    if (it != DataFlowIR.Node.Null && it !is DataFlowIR.Node.Parameter)
                        nodes[it] = newNode()
                }
                convertBody(copiedBody, localReturnsNode)
                if (call !is DataFlowIR.Node.NewObject)
                    arguments[calleeSymbol.parameters.size].toPTGNode()?.addAssignmentEdge(localReturnsNode)
                processCalls(copiedCallSites, component, callSitesStartingRecursion, maxAllowedGraphSize)

                if (startsRecursion) {
                    for (parameter in copiedBody.parameters)
                        parameterVariables.remove(parameter)
                    returnVariables.remove(calleeSymbol)
                } else {
                    for (parameter in copiedBody.parameters)
                        parameterValues.remove(parameter)
                }
            }

            fun inlineCalleeEscapeAnalysisResult(
                    call: DataFlowIR.Node.Call,
                    arguments: List<DataFlowIR.Node>,
                    calleeEscapeAnalysisResult: FunctionEscapeAnalysisResult,
            ) {
                context.logMultiple {
                    +"Callee escape analysis result:"
                    +calleeEscapeAnalysisResult.toString()
                }

                val calleeDrains = Array(calleeEscapeAnalysisResult.numberOfDrains) { newNode() }

                fun mapNode(compressedNode: CompressedPointsToGraph.Node): Pair<DataFlowIR.Node?, PointsToGraphNode?> {
                    val (arg, rootNode) = when (val kind = compressedNode.kind) {
                        CompressedPointsToGraph.NodeKind.Return ->
                            if (call is DataFlowIR.Node.NewObject) // TODO: This better be an assertion.
                                DataFlowIR.Node.Null to null
                            else
                                arguments.last() to arguments.last().toPTGNode()
                        is CompressedPointsToGraph.NodeKind.Param -> arguments[kind.index] to arguments[kind.index].toPTGNode()
                        is CompressedPointsToGraph.NodeKind.Drain -> null to calleeDrains[kind.index]
                    }
                    if (rootNode == null)
                        return arg to rootNode
                    val path = compressedNode.path
                    var node: PointsToGraphNode = rootNode
                    for (field in path) {
                        node = when (field) {
                            returnsValueField -> node
                            else -> node.getFieldNode(field, this)
                        }
                    }
                    return arg to node
                }

                calleeEscapeAnalysisResult.escapes.forEach { escapingNode ->
                    val (arg, node) = mapNode(escapingNode)
                    if (node == null) {
                        context.log { "WARNING: There is no node ${nodeToString(arg!!)}" }
                        return@forEach
                    }
                    escapeOrigins += node
                    context.log { "Node ${escapingNode.debugString(arg?.let { nodeToString(it) })} escapes" }
                }

                calleeEscapeAnalysisResult.pointsTo.edges.forEach { edge ->
                    val (fromArg, fromNode) = mapNode(edge.from)
                    if (fromNode == null) {
                        context.log { "WARNING: There is no node ${nodeToString(fromArg!!)}" }
                        return@forEach
                    }
                    val (toArg, toNode) = mapNode(edge.to)
                    if (toNode == null) {
                        context.log { "WARNING: There is no node ${nodeToString(toArg!!)}" }
                        return@forEach
                    }
                    fromNode.addAssignmentEdge(toNode)

                    context.logMultiple {
                        +"Adding edge"
                        +"    FROM ${edge.from.debugString(fromArg?.let { nodeToString(it) })}"
                        +"    TO ${edge.to.debugString(toArg?.let { nodeToString(it) })}"
                    }
                }

                logDigraph(false)
            }

            fun buildClosure(): FunctionEscapeAnalysisResult {
                context.logMultiple {
                    +"BUILDING CLOSURE"
                    +"Return values:"
                    returnValues.forEach { +"    ${nodeToString(it)}" }
                }

                buildDrains()

                logDigraph(true)

                computeLifetimes()

                /*
                 * The next part determines the function's escape analysis result.
                 * Of course, the simplest way would be to just take the entire graph, but it might be big,
                 * and during call graph traversal these EA graphs will continue to grow (since they are
                 * being embedded at each call site). To overcome this, the graph must be reduced.
                 * Let us call nodes that will be part of the result "interesting", and, obviously,
                 * "interesting drains" - drains that are going to be in the result.
                 */
                val (numberOfDrains, nodeIds) = paintInterestingNodes()

                logDigraph(true, { nodeIds[it] != null }, { nodeIds[it].toString() })

                // TODO: Remove redundant edges.
                val compressedEdges = mutableListOf<CompressedPointsToGraph.Edge>()
                val escapingNodes = mutableListOf<CompressedPointsToGraph.Node>()
                for (from in allNodes) {
                    val fromCompressedNode = nodeIds[from] ?: continue
                    if (from in escapeOrigins)
                        escapingNodes += fromCompressedNode
                    for (edge in from.edges) {
                        val toCompressedNode = nodeIds[edge.node] ?: continue
                        when (edge) {
                            is PointsToGraphEdge.Assignment ->
                                compressedEdges += CompressedPointsToGraph.Edge(fromCompressedNode, toCompressedNode)

                            is PointsToGraphEdge.Field -> {
                                val next = fromCompressedNode.goto(edge.field)
                                if (next != toCompressedNode) // Skip loops.
                                    compressedEdges += CompressedPointsToGraph.Edge(next, toCompressedNode)
                            }
                        }
                    }
                }

                return FunctionEscapeAnalysisResult(
                        numberOfDrains,
                        CompressedPointsToGraph(compressedEdges.toTypedArray()),
                        escapingNodes.toTypedArray()
                )
            }

            private fun buildDrains() {
                // TODO: This is actually conservative. If a field is being read of some node,
                // then here it is assumed that it might also be being read from any node reachable
                // by assignment edges considering them undirected. But in reality it is enough to just
                // merge two sets: reachable by assignment edges and reachable by reversed assignment edges.
                // But, there will be a downside - drains will have to be created for each field access,
                // thus increasing the graph size significantly.
                val visited = mutableSetOf<PointsToGraphNode>()
                val drains = mutableListOf<PointsToGraphNode>()
                val createdDrains = mutableSetOf<PointsToGraphNode>()
                // Create drains.
                for (node in allNodes.toTypedArray() /* Copy as [allNodes] might change inside */) {
                    if (node in visited) continue
                    val component = mutableListOf<PointsToGraphNode>()
                    buildComponent(node, visited, component)
                    val drain = trySelectDrain(component)?.also { it.drain = it }
                            ?: newDrain().also { createdDrains += it }
                    drains += drain
                    component.forEach {
                        if (it == drain) return@forEach
                        it.drain = drain
                        val assignmentEdges = mutableListOf<PointsToGraphEdge>()
                        for (edge in it.edges) {
                            if (edge is PointsToGraphEdge.Assignment)
                                assignmentEdges += edge
                            else
                                drain.edges += edge
                        }
                        it.edges.clear()
                        it.edges += assignmentEdges
                    }
                }

                fun PointsToGraphNode.flipTo(otherDrain: PointsToGraphNode) {
                    require(isDrain)
                    require(otherDrain.isDrain)
                    drain = otherDrain
                    otherDrain.edges += edges
                    edges.clear()
                }

                // Merge the components multi-edges are pointing at.
                // TODO: This looks very similar to the system of disjoint sets algorithm.
                while (true) {
                    val toMerge = mutableListOf<Pair<PointsToGraphNode, PointsToGraphNode>>()
                    for (drain in drains) {
                        val fields = drain.edges.groupBy { edge ->
                            (edge as? PointsToGraphEdge.Field)?.field
                                    ?: error("A drain cannot have outgoing assignment edges")
                        }
                        for (nodes in fields.values) {
                            if (nodes.size == 1) continue
                            for (i in nodes.indices) {
                                val firstNode = nodes[i].node
                                val secondNode = if (i == nodes.size - 1) nodes[0].node else nodes[i + 1].node
                                if (firstNode.actualDrain != secondNode.actualDrain)
                                    toMerge += Pair(firstNode, secondNode)
                            }
                        }
                    }
                    if (toMerge.isEmpty()) break
                    val possibleDrains = mutableListOf<PointsToGraphNode>()
                    for ((first, second) in toMerge) {
                        // Merge components: try to flip one drain to the other if possible,
                        // otherwise just create a new one.

                        val firstDrain = first.actualDrain
                        val secondDrain = second.actualDrain
                        when {
                            firstDrain == secondDrain -> continue

                            firstDrain in createdDrains -> {
                                secondDrain.flipTo(firstDrain)
                                possibleDrains += firstDrain
                            }

                            secondDrain in createdDrains -> {
                                firstDrain.flipTo(secondDrain)
                                possibleDrains += secondDrain
                            }

                            else -> {
                                // Create a new drain in order to not create false constraints.
                                val newDrain = newDrain().also { createdDrains += it }
                                firstDrain.flipTo(newDrain)
                                secondDrain.flipTo(newDrain)
                                possibleDrains += newDrain
                            }
                        }
                    }
                    drains.clear()
                    possibleDrains.filterTo(drains) { it.isDrain }
                }

                // Compute current drains.
                drains.clear()
                allNodes.filterTo(drains) { it.isActualDrain }

                // A validation.
                for (drain in drains) {
                    val fields = mutableMapOf<DataFlowIR.Field, PointsToGraphNode>()
                    for (edge in drain.edges) {
                        val field = (edge as? PointsToGraphEdge.Field)?.field
                                ?: error("A drain cannot have outgoing assignment edges")
                        val node = edge.node.actualDrain
                        fields.getOrPut(field) { node }.also {
                            if (it != node) error("Drains have not been built correctly")
                        }
                    }
                }

                // Coalesce multi-edges.
                for (drain in drains) {
                    val actualDrain = drain.actualDrain
                    val fields = actualDrain.edges.groupBy { edge ->
                        (edge as? PointsToGraphEdge.Field)?.field
                                ?: error("A drain cannot have outgoing assignment edges")
                    }
                    actualDrain.edges.clear()
                    for (nodes in fields.values) {
                        if (nodes.size == 1) {
                            actualDrain.edges += nodes[0]
                            continue
                        }
                        // All nodes in [nodes] must be connected to each other, but a drain, by definition,
                        // cannot have outgoing assignment edges, thus a new drain must be created here.
                        nodes.atMostOne { it.node.isActualDrain }
                                ?.node?.actualDrain?.flipTo(newDrain())

                        for (i in nodes.indices) {
                            val firstNode = nodes[i].node
                            val secondNode = if (i == nodes.size - 1) nodes[0].node else nodes[i + 1].node
                            firstNode.addAssignmentEdge(secondNode)
                        }
                        // Can pick any.
                        actualDrain.edges += nodes[0]
                    }
                }

                // Make sure every node within a component points to the component's drain.
                for (node in allNodes) {
                    val drain = node.actualDrain
                    node.drain = drain
                    if (node != drain)
                        node.addAssignmentEdge(drain)
                }
            }

            // Drains, other than interesting, can be safely omitted from the result.
            private fun findInterestingDrains(parameters: Array<PointsToGraphNode>): Set<PointsToGraphNode> {
                // Starting with all reachable from the parameters.
                val interestingDrains = mutableSetOf<PointsToGraphNode>()
                for (param in parameters) {
                    val drain = param.drain
                    if (drain !in interestingDrains)
                        findReachableDrains(drain, interestingDrains)
                }

                // Then iteratively remove all drains forming kind of a "cactus"
                // (picking a leaf drain with only one incoming edge at a time).
                // They can be removed because they don't add any relations between the parameters.
                val reversedEdges = interestingDrains.associateWith {
                    mutableListOf<Pair<PointsToGraphNode, PointsToGraphEdge>>()
                }
                val edgesCount = mutableMapOf<PointsToGraphNode, Int>()
                val leaves = mutableListOf<PointsToGraphNode>()
                for (drain in interestingDrains) {
                    var count = 0
                    for (edge in drain.edges) {
                        val nextDrain = edge.node.drain
                        reversedEdges[nextDrain]!! += drain to edge
                        if (nextDrain in interestingDrains)
                            ++count
                    }
                    edgesCount[drain] = count
                    if (count == 0)
                        leaves.push(drain)
                }
                val parameterDrains = parameters.map { it.drain }.toSet()
                while (leaves.isNotEmpty()) {
                    val drain = leaves.pop()
                    val incomingEdges = reversedEdges[drain]!!
                    if (incomingEdges.isEmpty()) {
                        if (drain !in parameterDrains)
                            error("A drain with no incoming edges")
                        if (!parameters.any { it.isDrain && escapes(it) })
                            interestingDrains.remove(drain)
                        continue
                    }
                    if (drain in parameterDrains)
                        continue
                    if (incomingEdges.size == 1
                            && incomingEdges[0].let { (node, edge) -> escapes(node) || !escapes(edge.node) }
                    ) {
                        interestingDrains.remove(drain)
                        val prevDrain = incomingEdges[0].first
                        val count = edgesCount[prevDrain]!! - 1
                        edgesCount[prevDrain] = count
                        if (count == 0)
                            leaves.push(prevDrain)
                    }
                }
                return interestingDrains
            }

            private fun paintInterestingNodes(): Pair<Int, Map<PointsToGraphNode, CompressedPointsToGraph.Node>> {
                var drainsCount = 0
                val drainFactory = { CompressedPointsToGraph.Node.drain(drainsCount++) }

                val interestingDrains = findInterestingDrains(parameters)
                val nodeIds = paintNodes(parameters, interestingDrains, drainFactory)
                buildComponentsClosures(nodeIds)
                handleNotTakenEscapeOrigins(nodeIds, drainFactory)
                restoreOptimizedAwayDrainsIfNeeded(interestingDrains, nodeIds, drainFactory)

                return Pair(drainsCount, nodeIds)
            }

            private fun handleNotTakenEscapeOrigins(
                    nodeIds: MutableMap<PointsToGraphNode, CompressedPointsToGraph.Node>,
                    drainFactory: () -> CompressedPointsToGraph.Node
            ) {
                // We've marked reachable nodes from the parameters, also taking some of the escape origins.
                // But there might be some escape origins that are not taken, yet referencing some of the
                // marked nodes. Do the following: find all escaping nodes only taking marked escape origins
                // into account, compare the result with all escaping nodes. Now for each non-marked escape origin
                // find nodes escaping because of it, take those who aren't escaping through the marked origins,
                // and add an additional node, pointing at those and mark it as an escape origin.
                val reachableFromTakenEscapeOrigins = mutableSetOf<PointsToGraphNode>()
                val referencingTakenEscapeOrigins = mutableSetOf<PointsToGraphNode>()
                val reachableFromNotTakenEscapeOrigins = mutableSetOf<PointsToGraphNode>()
                val referencingNotTakenEscapeOrigins = mutableSetOf<PointsToGraphNode>()
                val reachableFringeFromNotTakenEscapeOrigins = mutableSetOf<PointsToGraphNode>()
                val fringeReferencingNotTakenEscapeOrigins = mutableSetOf<PointsToGraphNode>()
                for (escapeOrigin in escapeOrigins) {
                    if (nodeIds[escapeOrigin] == null) {
                        if (escapeOrigin !in reachableFromNotTakenEscapeOrigins)
                            findReachableFringe(escapeOrigin, reachableFromNotTakenEscapeOrigins,
                                    reachableFringeFromNotTakenEscapeOrigins, nodeIds)
                        if (escapeOrigin !in referencingNotTakenEscapeOrigins)
                            findReferencingFringe(escapeOrigin, referencingNotTakenEscapeOrigins,
                                    fringeReferencingNotTakenEscapeOrigins, nodeIds)
                    } else {
                        if (escapeOrigin !in reachableFromTakenEscapeOrigins)
                            findReachable(escapeOrigin, reachableFromTakenEscapeOrigins, false, null)
                        if (escapeOrigin !in referencingTakenEscapeOrigins)
                            findReferencing(escapeOrigin, referencingTakenEscapeOrigins)
                    }
                }

                fun addAdditionalEscapeOrigins(escapingNodes: List<PointsToGraphNode>, direction: EdgeDirection) {
                    escapingNodes
                            .groupBy { it.drain }
                            .forEach { (drain, nodes) ->
                                val tempNode = newNode()
                                nodeIds[tempNode] = drainFactory()
                                tempNode.drain = drain
                                tempNode.addAssignmentEdge(drain)
                                escapeOrigins += tempNode
                                for (node in nodes)
                                    when (direction) {
                                        EdgeDirection.FORWARD -> tempNode.addAssignmentEdge(node)
                                        EdgeDirection.BACKWARD -> node.addAssignmentEdge(tempNode)
                                    }
                            }
                }

                addAdditionalEscapeOrigins(
                        reachableFringeFromNotTakenEscapeOrigins
                                .filterNot { it in reachableFromTakenEscapeOrigins },
                        EdgeDirection.FORWARD
                )
                addAdditionalEscapeOrigins(
                        fringeReferencingNotTakenEscapeOrigins
                                .filterNot { it in referencingTakenEscapeOrigins },
                        EdgeDirection.BACKWARD
                )
            }

            private fun restoreOptimizedAwayDrainsIfNeeded(
                    interestingDrains: Set<PointsToGraphNode>,
                    nodeIds: MutableMap<PointsToGraphNode, CompressedPointsToGraph.Node>,
                    drainFactory: () -> CompressedPointsToGraph.Node
            ) {
                // Here we try to find this subgraph within one component: [v -> d; w -> d; v !-> w; w !-> v].
                // This is needed because components are built with edges being considered undirected, so
                // this implicit connection between [v] and [w] may be needed. Note, however, that the
                // opposite subgraph: [d -> v; d -> w; v !-> w; w !-> v] is not interesting, because [d]
                // can't hold both values simultaneously, but two references can hold the same value
                // at the same time, that's the difference.
                // For concrete example see [codegen/escapeAnalysis/test10.kt].
                // Note: it is possible to search not for all such nodes, but for drains only, here's why:
                // Assume a node [v] which is referenced from the two fixed nodes is found, then consider
                // the [v]'s drain, by construction all drains are reachable from all nodes within the corresponding
                // component, including [v]; this implies that the drain also is referenced from these two nodes,
                // and therefore it is possible to check only drains rather than all nodes.
                val connectedNodes = mutableSetOf<Pair<PointsToGraphNode, PointsToGraphNode>>()
                allNodes.filter { nodeIds[it] != null && nodeIds[it.drain] == null /* The drain has been optimized away */ }
                        .forEach { node ->
                            val referencingNodes = findReferencing(node).filter { nodeIds[it] != null }
                            for (i in referencingNodes.indices)
                                for (j in i + 1 until referencingNodes.size) {
                                    val firstNode = referencingNodes[i]
                                    val secondNode = referencingNodes[j]
                                    connectedNodes.add(Pair(firstNode, secondNode))
                                    connectedNodes.add(Pair(secondNode, firstNode))
                                }
                        }

                interestingDrains
                        .filter { nodeIds[it] == null } // Was optimized away.
                        .forEach { drain ->
                            val referencingNodes = findReferencing(drain).filter { nodeIds[it] != null }
                            var needDrain = false
                            outerLoop@ for (i in referencingNodes.indices)
                                for (j in i + 1 until referencingNodes.size) {
                                    val firstNode = referencingNodes[i]
                                    val secondNode = referencingNodes[j]
                                    val pair = Pair(firstNode, secondNode)
                                    if (pair !in connectedNodes) {
                                        needDrain = true
                                        break@outerLoop
                                    }
                                }
                            if (needDrain)
                                nodeIds[drain] = drainFactory()
                        }
            }

            private fun findReferencing(node: PointsToGraphNode, visited: MutableSet<PointsToGraphNode>) {
                visited += node
                for (edge in node.reversedEdges) {
                    val nextNode = edge.node
                    if (nextNode !in visited)
                        findReferencing(nextNode, visited)
                }
            }

            private fun findReferencing(node: PointsToGraphNode): Set<PointsToGraphNode> {
                val visited = mutableSetOf<PointsToGraphNode>()
                findReferencing(node, visited)
                return visited
            }

            private fun trySelectDrain(component: MutableList<PointsToGraphNode>) =
                    component.firstOrNull { node ->
                        if (node.edges.any { it is PointsToGraphEdge.Assignment })
                            false
                        else
                            findReferencing(node).size == component.size
                    }

            private fun buildComponent(
                    node: PointsToGraphNode,
                    visited: MutableSet<PointsToGraphNode>,
                    component: MutableList<PointsToGraphNode>
            ) {
                visited += node
                component += node
                for (edge in node.edges) {
                    if (edge is PointsToGraphEdge.Assignment && edge.node !in visited)
                        buildComponent(edge.node, visited, component)
                }
                for (edge in node.reversedEdges) {
                    if (edge.node !in visited)
                        buildComponent(edge.node, visited, component)
                }
            }

            private fun findReachable(node: PointsToGraphNode, visited: MutableSet<PointsToGraphNode>,
                                      assignmentOnly: Boolean,
                                      nodeIds: MutableMap<PointsToGraphNode, CompressedPointsToGraph.Node>?) {
                visited += node
                node.edges.forEach {
                    val next = it.node
                    if ((it is PointsToGraphEdge.Assignment || !assignmentOnly)
                            && next !in visited && nodeIds?.containsKey(next) != false)
                        findReachable(next, visited, assignmentOnly, nodeIds)
                }
            }

            private fun findFringe(node: PointsToGraphNode, visited: MutableSet<PointsToGraphNode>,
                                   fringe: MutableSet<PointsToGraphNode>, direction: EdgeDirection,
                                   nodeIds: MutableMap<PointsToGraphNode, CompressedPointsToGraph.Node>) {
                visited += node
                if (nodeIds[node] != null) {
                    fringe += node
                    return
                }
                val edges = when (direction) {
                    EdgeDirection.FORWARD -> node.edges
                    EdgeDirection.BACKWARD -> node.reversedEdges
                }
                for (edge in edges) {
                    val next = edge.node
                    if (next !in visited)
                        findFringe(next, visited, fringe, direction, nodeIds)
                }
            }

            private fun findReachableFringe(
                    node: PointsToGraphNode, visited: MutableSet<PointsToGraphNode>,
                    fringe: MutableSet<PointsToGraphNode>,
                    nodeIds: MutableMap<PointsToGraphNode, CompressedPointsToGraph.Node>
            ) = findFringe(node, visited, fringe, EdgeDirection.FORWARD, nodeIds)

            private fun findReferencingFringe(
                    node: PointsToGraphNode, visited: MutableSet<PointsToGraphNode>,
                    fringe: MutableSet<PointsToGraphNode>,
                    nodeIds: MutableMap<PointsToGraphNode, CompressedPointsToGraph.Node>
            ) = findFringe(node, visited, fringe, EdgeDirection.BACKWARD, nodeIds)

            private fun buildComponentsClosures(nodeIds: MutableMap<PointsToGraphNode, CompressedPointsToGraph.Node>) {
                for (node in allNodes) {
                    if (node !in nodeIds) continue
                    val visited = mutableSetOf<PointsToGraphNode>()
                    findReachable(node, visited, true, null)
                    val visitedInInterestingSubgraph = mutableSetOf<PointsToGraphNode>()
                    findReachable(node, visitedInInterestingSubgraph, true, nodeIds)
                    visited.removeAll(visitedInInterestingSubgraph)
                    for (reachable in visited)
                        if (reachable in nodeIds)
                            node.addAssignmentEdge(reachable)
                }
            }

            private fun propagateLifetimes() {
                val visited = mutableSetOf<PointsToGraphNode>()

                fun propagate(node: PointsToGraphNode) {
                    visited += node
                    val depth = node.depth
                    for (edge in node.edges) {
                        val nextNode = edge.node
                        if (nextNode !in visited && nextNode.depth >= depth) {
                            nextNode.depth = depth
                            propagate(nextNode)
                        }
                    }
                }

                for (node in allNodes.sortedBy { it.depth }) {
                    if (node !in visited)
                        propagate(node)
                }
            }

            private fun propagateEscapeOrigin(node: PointsToGraphNode) {
                if (node !in reachableFromEscapeOrigins)
                    findReachable(node, reachableFromEscapeOrigins, false, null)
                if (node !in referencingEscapeOrigins)
                    findReferencing(node, referencingEscapeOrigins)
            }

            private fun propagateEscapeOrigins() {
                escapeOrigins.forEach { propagateEscapeOrigin(it) }
                for (node in allNodes) {
                    if (escapes(node))
                        node.depth = Depths.GLOBAL
                }
            }

            private fun computeReferencedBy() {
                for (index in parameters.indices) {
                    val reachable = mutableSetOf<PointsToGraphNode>()
                    findReachable(parameters[index], reachable, false, null)
                    for (node in reachable)
                        node.referencedBy.set(index)
                }
            }

            private fun computeLifetimes() {
                propagateLifetimes()

                propagateEscapeOrigins()
                computeReferencedBy()

                // TODO: To a setting?
                val allowedToAlloc = 2_000_000_000//65536
                val stackArrayCandidates = mutableListOf<ArrayStaticAllocation>()
                for ((node, ptgNode) in nodes) {
                    if (node.ir == null) continue

                    val computedLifetime = lifetimeOf(node)
                    var lifetime = computedLifetime

                    if (lifetime != Lifetime.STACK) {
                        // TODO: Support other lifetimes - requires arenas.
                        lifetime = Lifetime.GLOBAL
                    }

                    if (lifetime == Lifetime.STACK && node is DataFlowIR.Node.NewObject) {
                        val constructedType = node.constructedType.resolved()
                        constructedType.irClass?.let { irClass ->
                            val itemSize = arrayItemSizeOf(irClass)
                            if (itemSize != null) {
                                val sizeArgument = node.arguments.first().node
                                val arrayLength = arrayLengthOf(sizeArgument)?.takeIf { it >= 0 }
                                val arraySize = arraySize(itemSize, arrayLength ?: 1)//Int.MAX_VALUE)
                                if (arraySize <= allowedToAlloc) {
                                    stackArrayCandidates += ArrayStaticAllocation(ptgNode, irClass, arraySize.toInt())
                                } else {
                                    // Can be placed into the local arena.
                                    // TODO. Support Lifetime.LOCAL
                                    lifetime = Lifetime.GLOBAL
                                }
                            }
                        }
                    }

                    if (lifetime != computedLifetime) {
                        if (propagateExiledToHeapObjects && node.isAlloc) {
                            context.log { "Forcing node ${nodeToString(node)} to escape" }
                            escapeOrigins += ptgNode
                            propagateEscapeOrigin(ptgNode)
                        } else {
                            ptgNode.forcedLifetime = lifetime
                        }
                    }
                }

                stackArrayCandidates.sortBy { it.size }
                var remainedToAlloc = allowedToAlloc
                for ((ptgNode, irClass, size) in stackArrayCandidates) {
                    if (lifetimeOf(ptgNode) != Lifetime.STACK) continue
                    if (size <= remainedToAlloc)
                        remainedToAlloc -= size
                    else {
                        remainedToAlloc = 0
                        // Do not exile primitive arrays - they ain't reference no object.
                        if (irClass.symbol == symbols.array && propagateExiledToHeapObjects) {
                            context.log { "Forcing node ${nodeToString(ptgNode.node!!)} to escape" }
                            escapeOrigins += ptgNode
                            propagateEscapeOrigin(ptgNode)
                        } else {
                            ptgNode.forcedLifetime = Lifetime.GLOBAL // TODO: Change to LOCAL when supported.
                        }
                    }
                }
            }

            private fun findReachableDrains(drain: PointsToGraphNode, visitedDrains: MutableSet<PointsToGraphNode>) {
                visitedDrains += drain
                for (edge in drain.edges) {
                    if (edge is PointsToGraphEdge.Assignment)
                        error("A drain cannot have outgoing assignment edges")
                    val nextDrain = edge.node.drain
                    if (nextDrain !in visitedDrains)
                        findReachableDrains(nextDrain, visitedDrains)
                }
            }

            private fun paintNodes(
                    parameters: Array<PointsToGraphNode>,
                    interestingDrains: Set<PointsToGraphNode>,
                    drainFactory: () -> CompressedPointsToGraph.Node
            ): MutableMap<PointsToGraphNode, CompressedPointsToGraph.Node> {
                val nodeIds = mutableMapOf<PointsToGraphNode, CompressedPointsToGraph.Node>()

                for (index in parameters.indices)
                    nodeIds[parameters[index]] = CompressedPointsToGraph.Node.parameter(index, parameters.size)

                val standAloneDrains = interestingDrains.toMutableSet()
                for (drain in interestingDrains)
                    for (edge in drain.edges) {
                        val node = edge.node
                        if (node.isDrain && node != drain /* Skip loops */)
                            standAloneDrains.remove(node)
                    }
                for (drain in standAloneDrains) {
                    if (nodeIds[drain] == null
                            // A little optimization - skip leaf drains.
                            && drain.edges.any { it.node.drain in interestingDrains })
                        nodeIds[drain] = drainFactory()
                }

                var front = nodeIds.keys.toMutableList()
                do {
                    while (front.isNotEmpty()) {
                        val nextFront = mutableListOf<PointsToGraphNode>()
                        for (node in front) {
                            val nodeId = nodeIds[node]!!
                            node.edges.filterIsInstance<PointsToGraphEdge.Field>().forEach { edge ->
                                val field = edge.field
                                val nextNode = edge.node
                                if (nextNode.drain in interestingDrains && nextNode != node /* Skip loops */) {
                                    val nextNodeId = nodeId.goto(field)
                                    if (nodeIds[nextNode] == null) {
                                        nodeIds[nextNode] = nextNodeId
                                        if (nextNode.isDrain)
                                            nextFront += nextNode
                                    }
                                }
                            }
                        }
                        front = nextFront
                    }

                    // Find unpainted drain.
                    for (drain in interestingDrains) {
                        if (nodeIds[drain] == null
                                // A little optimization - skip leaf drains.
                                && drain.edges.any { it.node.drain in interestingDrains }
                        ) {
                            nodeIds[drain] = drainFactory()
                            front += drain
                            break
                        }
                    }
                } while (front.isNotEmpty()) // Loop until all drains have been painted.

                return nodeIds
            }
        }
    }

    fun computeLifetimes(
            context: Context,
            generationState: NativeGenerationState,
            moduleDFG: ModuleDFG,
            externalModulesDFG: ExternalModulesDFG,
            callGraph: CallGraph,
            lifetimes: MutableMap<IrElement, Lifetime>
    ) {
        assert(lifetimes.isEmpty())

        try {
            InterproceduralAnalysis(context, generationState, callGraph,
                    moduleDFG, externalModulesDFG, lifetimes,
                    propagateExiledToHeapObjects = context.config.memoryModel != MemoryModel.EXPERIMENTAL
            ).analyze()
        } catch (t: Throwable) {
            val extraUserInfo =
                    """
                        Please try to disable escape analysis and rerun the build. To do it add the following snippet to the gradle script:

                            kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
                                binaries.all {
                                    freeCompilerArgs += "-Xdisable-phases=EscapeAnalysis"
                                }
                            }

                        In case of using command line compiler add this option: "-Xdisable-phases=EscapeAnalysis".
                        Also, consider filing an issue with full Gradle log here: https://kotl.in/issue
                        """.trimIndent()
            context.reportCompilationError("Escape analysis failure:\n$extraUserInfo\n\n${t.message}\n${t.stackTraceToString()}")
        }
    }
}