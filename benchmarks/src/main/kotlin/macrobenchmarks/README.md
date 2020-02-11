# Macrobenchmarks

This package contains various macrobenchmarks that test different aspects of the performance of coroutines. These benchmarks try to emulate real life applications that could use coroutines in them. The idea is to test not a specific part of the implementation but to test the performance in general.

## Channel producer consumer monte carlo benchmark

The benchmark is similar to the `ChannelProducerConsumerBenchmark.kt`, but the difference is that it has random numbers of producers and consumers depending on the thread number, and it tries to get the average time of executions varying producers and consumers numbers.

The benchmark is designed to test the performance of channels under contention, and how it depends on the different types of channels and dispatchers and usage of select.

You can find more details of the benchmark in the `ChannelProdConsMonteCarloBenchmark.kt`. All the configuration for this benchmark are contained there, too. You can change them according your desire to test different work load.

This benchmark could be run using gradle task `./gradlew runChannelMonteCarloBenchmark`.