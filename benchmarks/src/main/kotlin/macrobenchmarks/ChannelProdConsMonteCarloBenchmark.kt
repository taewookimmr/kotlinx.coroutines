/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("ChannelProdConsMonteCarloBenchmark")

package macrobenchmarks

import benchmarks.common.*
import org.nield.kotlinstatistics.*
import java.io.*
import java.nio.file.*
import kotlin.math.*
import kotlin.random.*

/**
 * Numbers of threads to be used in the benchmark
 */
private val THREADS = arrayOf(1, 2, 4, 8)
/**
 * Number of warm-up iterations
 */
private const val WARM_UP_ITERATIONS = 5
/**
 * Maximum number of iterations for each configuration
 */
private const val MAX_ITERATIONS = 5_000
/**
 * Additional work baseline (after each send/receive invocation)
 */
private const val BASELINE_WORK = 50
/**
 * The max multiplier for the [BASELINE_WORK]
 */
private const val MAX_WORK_MULTIPLIER = 5.0
/**
 * Approximate number of sent/received messages
 *
 * If you change this variable please be sure that you change variable elements in the
 * `scripts/generate_plots_channel_prod_cons_monte_carlo.py` python script as well
 */
private const val APPROXIMATE_BATCH_SIZE = 50_000
/**
 * After the specified number of iterations the benchmark checks
 * whether the last [ITERATIONS_BETWEEN_THRESHOLD_CHECK] iterations
 * did not influence on the results significantly and stops in this case.
 *
 * Number of iteration between checking if the current benchmark configuration should be stopped
 */
private const val ITERATIONS_BETWEEN_THRESHOLD_CHECK = 50
/**
 * This threshold is used for checking whether the last iterations
 * changed the results significantly; we assume that the changes
 * are significant if the difference is greater than `result x MONTECARLO_STOP_THRESHOLD`.
 */
private const val MONTECARLO_STOP_THRESHOLD = 0.01
/**
 * Indicates whether the `select` expression should be used
 */
private val WITH_SELECT = listOf(false, true)
/**
 * Benchmark output file
 */
private const val OUTPUT = "out/results_channel_prod_cons_montecarlo.csv"
/**
 * Options for benchmark jvm instances
 */
private val jvmOptions = listOf<String>(/*"-Xmx64m", "-XX:+PrintGC"*/)

/**
 * This benchmark tests different channels in the producer-consumer workload.
 *
 * For each parameters combination, it runs a lot of iterations with random numbers of producers and consumers
 * (their sum should be equal to the number of threads) and random additional works after each send or receive invocation
 * (we also keep the workload balanced). Each iteration performs about [APPROXIMATE_BATCH_SIZE] send-receive transfers.
 * The benchmark stops when the current results become stable
 * (see [MONTECARLO_STOP_THRESHOLD] and [ITERATIONS_BETWEEN_THRESHOLD_CHECK] for details).
 */
fun main() {
    // Create a new output CSV file and write the header
    Files.createDirectories(Paths.get(OUTPUT).parent)
    writeOutputHeader()
    // Calculate necessary for ETA properties
    val totalIterations = ChannelCreator.values().size * THREADS.size * DispatcherCreator.values().size * WITH_SELECT.size
    var currentConfigurationNumber = 0
    val startTime = System.currentTimeMillis()
    // Run the benchmark for each configuration
    for (channel in ChannelCreator.values()) {
        for (threads in THREADS) {
            for (dispatcherType in DispatcherCreator.values()) {
                for (withSelect in WITH_SELECT) {
                    val args = listOf(channel, threads, dispatcherType, withSelect,
                                      currentConfigurationNumber, totalIterations, startTime)
                               .map { it.toString() }.toTypedArray()
                    val exitValue = runProcess(MonteCarloIterationProcess::class.java.name, jvmOptions, args)
                    if (exitValue != 0) {
                        println("The benchmark failed with error, see the output.")
                        return
                    }
                    currentConfigurationNumber++ // for ETA
                }
            }
        }
    }
}

class MonteCarloIterationProcess {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val channel = ChannelCreator.valueOf(args[0])
            val threads = args[1].toInt()
            val dispatcherType = DispatcherCreator.valueOf(args[2])
            val withSelect = args[3].toBoolean()
            val currentConfigurationNumber = args[4].toInt()
            val totalIterations = args[5].toInt()
            val startTime = args[6].toLong()
            // Run benchmark for the configuration described above
            val eta = eta(currentConfigurationNumber, totalIterations, startTime)
            print("\rchannel=$channel threads=$threads dispatcherType=$dispatcherType withSelect=$withSelect: warm-up phase... [$eta]")
            repeat(WARM_UP_ITERATIONS) {
                runIteration(threads, channel, withSelect, dispatcherType)
            }
            executeBenchmark(threads, channel, withSelect, dispatcherType, currentConfigurationNumber, totalIterations, startTime)
        }
    }
}

private fun writeOutputHeader() = PrintWriter(OUTPUT).use { pw ->
    pw.println("channel,threads,dispatcherType,withSelect,result,error,iterations")
}

private fun writeIterationResults(channel: ChannelCreator, threads: Int, dispatcherType : DispatcherCreator,
                                  withSelect : Boolean, result : Int, std : Int, iterations : Int) {
    FileOutputStream(OUTPUT, true).bufferedWriter().use {
        writer -> writer.append("$channel,$threads,$dispatcherType,$withSelect,$result,$std,$iterations\n")
    }
}

private fun executeBenchmark(threads: Int, channel: ChannelCreator, withSelect: Boolean, dispatcherType: DispatcherCreator,
                             currentConfigurationNumber: Int, totalIterations: Int, startTime: Long) {
    val runExecutionTimesMs = ArrayList<Long>()
    var lastMean = -10000.0
    var runIteration = 0
    while (true) {
        repeat(ITERATIONS_BETWEEN_THRESHOLD_CHECK) {
            runIteration++
            runExecutionTimesMs += runIteration(threads, channel, withSelect, dispatcherType)
            val result = runExecutionTimesMs.average().toInt()
            val std = runExecutionTimesMs.standardDeviation().toInt()
            val eta = eta(currentConfigurationNumber, totalIterations, startTime)
            print("\rchannel=$channel threads=$threads dispatcherType=$dispatcherType withSelect=$withSelect iteration=$runIteration result=$result std=$std [$eta]")
        }
        val curMean = runExecutionTimesMs.average()
        if (runIteration >= MAX_ITERATIONS || abs(curMean - lastMean) / curMean < MONTECARLO_STOP_THRESHOLD) break
        lastMean = curMean
    }

    val result = runExecutionTimesMs.average().toInt()
    val std = runExecutionTimesMs.standardDeviation().toInt()
    println("\rchannel=$channel threads=$threads dispatcherType=$dispatcherType withSelect=$withSelect result=$result std=$std iterations=$runIteration")

    writeIterationResults(channel = channel, threads = threads, dispatcherType = dispatcherType,
            withSelect = withSelect, result = result, std = std, iterations = runIteration)
}

/**
 * Estimated time of arrival
 */
private fun eta(curIteration: Int, totalIterations: Int, startTime: Long): String {
    if (curIteration == 0) return "ETA - NaN"
    val time = System.currentTimeMillis() - startTime
    val eta = (time.toDouble() / curIteration * totalIterations - time).toInt() / 60_000 // in minutes
    val minutes = eta % 60
    val hours = eta / 60
    return "ETA - $hours hours $minutes minutes"
}

private fun runIteration(threads: Int, channelCreator: ChannelCreator, withSelect: Boolean, dispatcherCreator: DispatcherCreator): Long {
    val producers = Random.nextInt(1, max(threads, 2)) // at least one producer
    val consumers = max(1, threads - producers) // at least one consumer
    // Run the iteration
    val iteration = MonteCarloBenchmarkIteration(channelCreator, withSelect, producers, consumers,
        dispatcherCreator, producers + consumers, APPROXIMATE_BATCH_SIZE)
    val startTime = System.nanoTime()
    iteration.run()
    val endTime = System.nanoTime()
    // Close resources and return the execution time
    (iteration.dispatcher as? Closeable)?.close()
    return (endTime - startTime) / 1_000_000 // ms
}

private class MonteCarloBenchmarkIteration(
    channelCreator: ChannelCreator,
    withSelect: Boolean,
    producers: Int,
    consumers: Int,
    dispatcherCreator: DispatcherCreator,
    parallelism: Int,
    approximateBatchSize: Int
) : ChannelProdConsBenchmarkIteration(channelCreator, withSelect, producers, consumers, dispatcherCreator, parallelism, approximateBatchSize) {
    private val producerWorks : Array<Int>
    private val consumerWorks : Array<Int>

    init {
        val producerWorkMultipliers = generateWorkMultipliers(producers)
        val consumerWorkMultipliers = generateWorkMultipliers(consumers)

        val consumerToProducerBaselineRelation = 1.0 * (consumers * consumers) / (producers * producers) *
                producerWorkMultipliers.sum() / consumerWorkMultipliers.sum()
        val producerBaselineWork: Int
        val consumerBaselineWork: Int
        if (consumerToProducerBaselineRelation > 1) {
            producerBaselineWork = BASELINE_WORK
            consumerBaselineWork = (consumerToProducerBaselineRelation * BASELINE_WORK).toInt()
        } else {
            consumerBaselineWork = BASELINE_WORK
            producerBaselineWork = (BASELINE_WORK / consumerToProducerBaselineRelation).toInt()
        }

        producerWorks = producerWorkMultipliers.map { (it * producerBaselineWork).toInt() }.toTypedArray()
        consumerWorks = consumerWorkMultipliers.map { (it * consumerBaselineWork).toInt() }.toTypedArray()
    }

    override fun doProducerWork(id: Int) = doGeomDistrWork(producerWorks[id])
    override fun doConsumerWork(id: Int) = doGeomDistrWork(consumerWorks[id])
}

/**
 * Returns multipliers for the work baseline.
 */
private fun generateWorkMultipliers(workers: Int): DoubleArray = DoubleArray(workers) { Random.nextDouble(1.0, MAX_WORK_MULTIPLIER) }