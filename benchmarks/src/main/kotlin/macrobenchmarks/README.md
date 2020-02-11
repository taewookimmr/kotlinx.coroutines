# Macrobenchmarks

This package contains various macro benchmarks that test different aspects of the Kotlin Coroutines performance. These benchmarks simulate real-life applications that use coroutines, and we assume that they are used to test the performance in general.

## Producer-Consumer Monte-Carlo Benchmark for Channels

This benchmark is similar to the `ChannelProducerConsumerBenchmark.kt`, but instead of fixing parameters like "additional work" or "number of producers to number of consumers relation", it examines the _average_ performance using the Monte-Carlo approach. Note that this benchmark tests channels under the high-contention workload. Please, see `ChannelProdConsMonteCarloBenchmark.kt` for more details and configuration parameters.

#### How to run
To run the benchmark, you can use the specified Gradle task, the output CSV file will be located in the `out/` directory. 

```
./gradlew runChanProdConsMCBench
``` 

You can generate plots based on your results executing the following command from the `benchmarks` directory.

```
python3 scripts/generate_plots_channel_prod_cons_monte_carlo.py
```