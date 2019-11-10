/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("ChannelProducerConsumerMonteCarloBenchmark")

package macrobenchmarks.channel

import ChannelCreator
import DispatcherCreator
import runProcess
import java.io.PrintWriter
import java.nio.file.*

/**
 * Total amount of threads in a benchmark
 */
private val THREADS = arrayOf(1, 2, 4, 8, 16, 18, 32, 36, 64, 72, 96, 108, 128, 144)
/**
 * Warm up iterations count
 */
const val WARM_UP_ITERATIONS = 5
/**
 * Maximum number of calling [runIteration] function
 */
const val MAX_ITERATIONS = 5_000
/**
 * The baseline for loop iterations each working thread does on average on each interaction with a channel
 */
const val BASELINE_WORK = 50
/**
 * The max multiplier for the [BASELINE_WORK]
 */
const val MAX_WORK_MULTIPLIER = 5.0
/**
 * Approximate total number of sent/received messages
 */
const val APPROXIMATE_BATCH_SIZE = 750_000
/**
 * The threshold when a current benchmark configuration stops running
 */
const val MONTECARLO_STOP_THRESHOLD = 0.01
/**
 * Number of iteration between checking if a current benchmark configuration should be stopped
 */
const val ITERATIONS_BETWEEN_THRESHOLD_CHECK = 50
/**
 * Different types of creating workload in the benchmark: using balanced or unbalanced work load
 */
private val WITH_BALANCING = listOf(true, false)
/**
 * Should select be used in the benchmark or not
 */
private val WITH_SELECT = listOf(true, false)
/**
 * Benchmark output file
 */
const val OUTPUT = "out/results_channel_producer_consumer_montecarlo.csv"
/**
 * Class name to run in a new jvm instance
 */
private const val CLASS_NAME = "macrobenchmarks.channel.RunMonteCarlo"
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
    Files.createDirectories(Paths.get(OUTPUT).parent)
    PrintWriter(OUTPUT).use { pw ->
        pw.println("channel,threads,withBalancing,dispatcherType,withSelect,result,std,iterations")
    }

    val benchmarksConfigurationsNumber = THREADS.size * ChannelCreator.values().size
    var currentConfigurationNumber = 0
    val startTime = System.currentTimeMillis()

    for (channel in ChannelCreator.values()) {
        for (threads in THREADS) {
            for (withBalancing in WITH_BALANCING) {
                for (dispatcherType in DispatcherCreator.values()) {
                    for (withSelect in WITH_SELECT) {
                        val args = arrayOf(channel.toString(),
                                threads.toString(),
                                withBalancing.toString(),
                                dispatcherType.toString(),
                                withSelect.toString(),
                                benchmarksConfigurationsNumber.toString(),
                                currentConfigurationNumber.toString(),
                                startTime.toString())
                        val exitValue = runProcess(CLASS_NAME, jvmOptions, args)
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
}