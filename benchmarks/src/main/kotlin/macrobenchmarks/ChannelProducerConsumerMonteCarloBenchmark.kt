/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("ChannelProducerConsumerMonteCarloBenchmark")

package macrobenchmarks

import benchmarks.common.*
import org.nield.kotlinstatistics.*
import java.io.*
import java.nio.file.*
import kotlin.math.*
import kotlin.random.*

/**
 * Total amount of threads in a benchmark
 */
private val THREADS = arrayOf(1, 4, 16, 32)
/**
 * Warm up iterations count
 */
private const val WARM_UP_ITERATIONS = 5
/**
 * Maximum number of calling [runIteration] function
 */
private const val MAX_ITERATIONS = 5_000
/**
 * The baseline for loop iterations each working thread does on average on each interaction with a channel
 */
private const val BASELINE_WORK = 50
/**
 * The max multiplier for the [BASELINE_WORK]
 */
private const val MAX_WORK_MULTIPLIER = 5.0
/**
 * Approximate total number of sent/received messages
 *
 * If you change this variable please be sure that you change variable elements in the
 * generate_plots_channel_producer_consumer_monte_carlo.py python script as well
 */
private const val APPROXIMATE_BATCH_SIZE = 10_000
/**
 * The threshold when a current benchmark configuration stops running
 */
private const val MONTECARLO_STOP_THRESHOLD = 0.01
/**
 * Number of iteration between checking if a current benchmark configuration should be stopped
 */
private const val ITERATIONS_BETWEEN_THRESHOLD_CHECK = 50
/**
 * Should select be used in the benchmark or not
 */
private val WITH_SELECT = listOf(false, true)
/**
 * Benchmark output file
 */
private const val OUTPUT = "out/results_channel_producer_consumer_montecarlo.csv"
/**
 * Options for the new jvm instance
 */
private val jvmOptions = listOf<String>(/*"-Xmx64m", "-XX:+PrintGC"*/)

/**
 * This benchmark tests the performance of different types of channel as working queues.
 *
 * First it chooses all parameters that we want to test in this benchmark, a benchmark configuration.
 * Each iteration creates N producers and M consumers, M + N = current count of threads. For each producer and consumer
 * workload is randomly chosen (workload may be balanced or unbalanced according to the benchmark configuration). Then
 * the benchmark starts all producers and consumers coroutines. Producers send to a channel messages and then do
 * some work on CPU, consumers receive messages from the channel and do some work on CPU. Total amount of sent messages
 * is ~ [APPROXIMATE_BATCH_SIZE]. The main thread waits for all coroutines to stop, then measures the execution time of
 * the current iteration.
 *
 * The benchmark stops execution the current benchmark configuration if execution times became stable and stopped changing
 * drastically.
 */
fun main() {
    // Create a new output CSV file and write the header
    Files.createDirectories(Paths.get(OUTPUT).parent)
    writeOutputHeader()

    val totalIterations = ChannelCreator.values().size * THREADS.size * DispatcherCreator.values().size * WITH_SELECT.size
    var currentConfigurationNumber = 0
    val startTime = System.currentTimeMillis()

    for (channel in ChannelCreator.values()) {
        for (threads in THREADS) {
            for (dispatcherType in DispatcherCreator.values()) {
                for (withSelect in WITH_SELECT) {
                    val args = arrayOf(channel.toString(),
                            threads.toString(),
                            dispatcherType.toString(),
                            withSelect.toString(),
                            currentConfigurationNumber.toString(),
                            totalIterations.toString(),
                            startTime.toString())
                    val exitValue = runProcess(MonteCarloIterationProcess::class.java.name, jvmOptions, args)
                    if (exitValue != 0) {
                        println("The benchmark couldn't complete properly, will end running benchmarks")
                        return
                    }
                    currentConfigurationNumber++
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

            print("\rchannel=$channel threads=$threads dispatcherType=$dispatcherType withSelect=$withSelect: warm-up phase... [${eta(currentConfigurationNumber, totalIterations, startTime)}]")

            repeat(WARM_UP_ITERATIONS) {
                runIteration(threads, channel, withSelect, dispatcherType)
            }

            runMonteCarlo(threads, channel, withSelect, dispatcherType) {
                eta(currentConfigurationNumber, totalIterations, startTime)
            }
        }
    }
}

private fun writeOutputHeader() = PrintWriter(OUTPUT).use { pw ->
    pw.println("channel,threads,dispatcherType,withSelect,result,std,iterations")
}

private fun writeIterationResults(channel: ChannelCreator, threads: Int, dispatcherType : DispatcherCreator,
                                  withSelect : Boolean, result : Int, std : Int, iterations : Int) {
    FileOutputStream(OUTPUT, true).bufferedWriter().use {
        writer -> writer.append("$channel,$threads,$dispatcherType,$withSelect,$result,$std,$iterations\n")
    }
}

private fun runMonteCarlo(threads: Int,
                          channel: ChannelCreator,
                          withSelect: Boolean,
                          dispatcherType: DispatcherCreator,
                          generateEta : () -> String) {
    val runExecutionTimesMs = ArrayList<Long>()
    var lastMean = -10000.0
    var runIteration = 0
    while (true) {
        repeat(ITERATIONS_BETWEEN_THRESHOLD_CHECK) {
            runIteration++
            runExecutionTimesMs += runIteration(threads, channel, withSelect, dispatcherType)
            val result = runExecutionTimesMs.average().toInt()
            val std = runExecutionTimesMs.standardDeviation().toInt()
            val eta = generateEta.invoke()
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
fun eta(curIteration: Int, totalIterations: Int, startTime: Long): String {
    if (curIteration == 0) return "ETA - NaN"
    val time = System.currentTimeMillis() - startTime
    var eta = (time.toDouble() / curIteration * totalIterations / 60_000).toInt() // in minutes
    val minutes = eta % 60
    eta /= 60
    val hours = eta % 24
    eta /= 24
    val days = eta
    return "ETA - $days days, $hours hours,  $minutes minutes"
}

fun runIteration(threads: Int, channelCreator: ChannelCreator, withSelect: Boolean, dispatcherCreator: DispatcherCreator): Long {
    val producers = Random.nextInt(1, max(threads, 2))
    val consumers = max(1, threads - producers)

    val channelProducerConsumerBenchmarkWorker = ChannelProducerConsumerBenchmarkIterationMonteCarlo(
            channelCreator, withSelect, producers, consumers,
            dispatcherCreator, producers + consumers, APPROXIMATE_BATCH_SIZE)

    val startTime = System.nanoTime()
    channelProducerConsumerBenchmarkWorker.run()
    val endTime = System.nanoTime()

    val dispatcher = channelProducerConsumerBenchmarkWorker.dispatcher
    if (dispatcher is Closeable) {
        dispatcher.close()
    }

    return (endTime - startTime) / 1_000_000 // ms
}

class ChannelProducerConsumerBenchmarkIterationMonteCarlo(channelCreator: ChannelCreator,
                                                          withSelect: Boolean,
                                                          producers: Int,
                                                          consumers: Int,
                                                          dispatcherCreator: DispatcherCreator,
                                                          parallelism: Int,
                                                          approximateBatchSize : Int)
    : ChannelProducerConsumerBenchmarkIteration(channelCreator, withSelect, producers, consumers, dispatcherCreator, parallelism, approximateBatchSize) {
    private val producerWorks : Array<Int>

    private val consumerWorks : Array<Int>

    init {
        val producerWorkMultipliers = generateWorkMultipliers(producers)
        val consumerWorkMultipliers = generateWorkMultipliers(consumers)

        val consumerToProducerBaselineRelation = 1.0 * (consumers * consumers) / (producers * producers) *
                producerWorkMultipliers.sum() / consumerWorkMultipliers.sum()
        val producerWorkBaseline: Int
        val consumerWorkBaseline: Int
        if (consumerToProducerBaselineRelation > 1) {
            producerWorkBaseline = BASELINE_WORK
            consumerWorkBaseline = (consumerToProducerBaselineRelation * BASELINE_WORK).toInt()
        } else {
            consumerWorkBaseline = BASELINE_WORK
            producerWorkBaseline = (BASELINE_WORK / consumerToProducerBaselineRelation).toInt()
        }

        producerWorks = producerWorkMultipliers.map { (it * producerWorkBaseline).toInt() }.toTypedArray()
        consumerWorks = consumerWorkMultipliers.map { (it * consumerWorkBaseline).toInt() }.toTypedArray()

    }

    override fun doProducerWork(id: Int) = doGeomDistrWork(producerWorks[id])
    override fun doConsumerWork(id: Int) = doGeomDistrWork(consumerWorks[id])
}

/**
 * Returns multipliers for the work baseline.
 */
private fun generateWorkMultipliers(workers: Int): DoubleArray = DoubleArray(workers) { Random.nextDouble(1.0, MAX_WORK_MULTIPLIER) }