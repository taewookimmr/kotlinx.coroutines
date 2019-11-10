/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("RunMonteCarlo")

package macrobenchmarks.channel

import ChannelCreator
import ChannelProducerConsumerBenchmarkIteration
import DispatcherCreator
import doGeomDistrWork
import org.nield.kotlinstatistics.standardDeviation
import java.io.*
import kotlin.math.*
import kotlin.random.Random

fun main(args: Array<String>) {
    val channel = ChannelCreator.valueOf(args[0])
    val threads = args[1].toInt()
    val withBalancing = args[2].toBoolean()
    val dispatcherType = DispatcherCreator.valueOf(args[3])
    val withSelect = args[4].toBoolean()
    val currentConfigurationNumber = args[5].toInt()
    val benchmarksConfigurationsNumber = args[6].toInt()
    val startTime = args[7].toLong()

    print("\rchannel=$channel threads=$threads withBalancing=$withBalancing dispatcherType=$dispatcherType withSelect=$withSelect: warm-up phase... [${eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime)}]")

    repeat(WARM_UP_ITERATIONS) {
        runIteration(threads, channel, withBalancing, withSelect, dispatcherType)
    }

    runMonteCarlo(threads, channel, withBalancing, withSelect, dispatcherType) {
        eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime)
    }
}


private fun runMonteCarlo(threads: Int,
                          channel: ChannelCreator,
                          withBalancing: Boolean,
                          withSelect: Boolean,
                          dispatcherType: DispatcherCreator,
                          generateEta : () -> String) {
    val runExecutionTimesMs = ArrayList<Long>()
    var lastMean = -10000.0
    var runIteration = 0
    while (true) {
        repeat(ITERATIONS_BETWEEN_THRESHOLD_CHECK) {
            runIteration++
            runExecutionTimesMs += runIteration(threads, channel, withBalancing, withSelect, dispatcherType)
            val result = runExecutionTimesMs.average().toInt()
            val std = runExecutionTimesMs.standardDeviation().toInt()
            val eta = generateEta.invoke()
            print("\rchannel=$channel threads=$threads withBalancing=$withBalancing dispatcherType=$dispatcherType withSelect=$withSelect iteration=$runIteration result=$result std=$std [$eta]")
        }
        val curMean = runExecutionTimesMs.average()
        if (runIteration >= MAX_ITERATIONS || abs(curMean - lastMean) / curMean < MONTECARLO_STOP_THRESHOLD) break
        lastMean = curMean
    }

    val result = runExecutionTimesMs.average().toInt()
    val std = runExecutionTimesMs.standardDeviation().toInt()
    println("\rchannel=$channel threads=$threads withBalancing=$withBalancing dispatcherType=$dispatcherType withSelect=$withSelect result=$result std=$std iterations=$runIteration")

    FileOutputStream(OUTPUT, true).bufferedWriter().use {
        writer -> writer.append("$channel,$threads,$withBalancing,$dispatcherType,$withSelect,$result,$std,$runIteration\n")
    }
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

fun runIteration(threads: Int, channelCreator: ChannelCreator, withBalancing: Boolean, withSelect: Boolean,
                 dispatcherCreator: DispatcherCreator): Long {
    val producers = Random.nextInt(1, max(threads, 2))
    val consumers = max(1, threads - producers)

    val channelProducerConsumerBenchmarkWorker = ChannelProducerConsumerBenchmarkIterationMonteCarlo(
            withBalancing, withSelect, dispatcherCreator, channelCreator, producers + consumers,
            producers, consumers, APPROXIMATE_BATCH_SIZE)

    val startTime = System.nanoTime()
    channelProducerConsumerBenchmarkWorker.run()
    val endTime = System.nanoTime()

    val dispatcher = channelProducerConsumerBenchmarkWorker.dispatcher
    if (dispatcher is Closeable) {
        dispatcher.close()
    }

    return (endTime - startTime) / 1_000_000 // ms
}

/**
 * Returns multipliers for the work baseline.
 */
private fun generateWorkMultipliers(workers: Int): DoubleArray = DoubleArray(workers) { Random.nextDouble(1.0, MAX_WORK_MULTIPLIER) }

class ChannelProducerConsumerBenchmarkIterationMonteCarlo(withBalancing: Boolean,
                                                          withSelect: Boolean,
                                                          dispatcherCreator: DispatcherCreator,
                                                          channelCreator: ChannelCreator,
                                                          parallelism: Int,
                                                          producers: Int,
                                                          consumers: Int,
                                                          approximateBatchSize : Int)
    : ChannelProducerConsumerBenchmarkIteration(withSelect, dispatcherCreator, channelCreator, parallelism, producers, consumers, approximateBatchSize) {
    private val producerWorks : Array<Int>

    private val consumerWorks : Array<Int>

    init {
        if (withBalancing) {
            producerWorks = Array(producers) { Random.nextInt(BASELINE_WORK, (BASELINE_WORK * MAX_WORK_MULTIPLIER).toInt() + 1) }
            consumerWorks = Array(consumers) { Random.nextInt(BASELINE_WORK, (BASELINE_WORK * MAX_WORK_MULTIPLIER).toInt() + 1) }
        }
        else {
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
    }

    override fun doProducerWork(id: Int) = doGeomDistrWork(producerWorks[id])

    override fun doConsumerWork(id: Int) = doGeomDistrWork(consumerWorks[id])
}