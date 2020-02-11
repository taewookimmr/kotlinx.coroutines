# To run this script run the command 'python3 scripts/generate_plots_channel_prod_cons_monte_carlo.py'
# in the benchmarks/ folder

import pandas as pd
import matplotlib.pyplot as plt
import locale
from matplotlib.ticker import FormatStrFormatter
from matplotlib.backends.backend_pdf import PdfPages

input_file = "out/results_channel_prod_cons_montecarlo.csv"
output_file = "out/channel-prod-cons-monte-carlo.pdf"
# Please change the value of this variable according to the ChannelProducerConsumerMonteCarloBenchmark.APPROXIMATE_BATCH_SIZE
approx_batch_size = 10000

markers = ['.', 'v', '^', '1', '2', '8', 'p', 'P', 'x', 'D', 'd', 's']
colours = ['#F7A3FF', '#EA00FF', '#2DA6C4', '#238199', '#1CD100', '#139100', '#fcae91', '#fb6a4a', '#8585D6', '#62629E', '#858585', '#2B2B2B', '#FFFF00', '#BABA00']

def next_colour():
    for colour in colours:
        yield colour

def next_marker():
    for marker in markers:
        yield marker

def draw(data, ax_arr):
    flatten_ax_arr = ax_arr.flatten()
    for ax in flatten_ax_arr:
        ax.set_xscale('log', basex=2)
        ax.xaxis.set_major_formatter(FormatStrFormatter('%0.f'))
        ax.grid(linewidth='0.5', color='lightgray')
        ax.set_ylabel("send+receive on msg avg time (ms)".format(approx_batch_size))
        ax.set_xlabel('threads')
        ax.set_xticks(data.threads.unique())

    i = 0
    for dispatcher_type in data.dispatcherType.unique():
        colour_gen = next_colour()
        marker_gen = next_marker()
        flatten_ax_arr[i].set_title("dispatcher_type={}".format(dispatcher_type))

        for channel in data.channel.unique():
            for with_select in data.withSelect.unique():
                gen_colour = next(colour_gen)
                gen_marker = next(marker_gen)
                res = data[(data.dispatcherType == dispatcher_type) & (data.withSelect == with_select) & (data.channel == channel)]
                flatten_ax_arr[i].plot(res.threads, res.result, label="channel={} {}".format(channel, "[with select]" if with_select else ""), color=gen_colour, marker=gen_marker, linewidth=2.2)
        i += 1

def gen_file(pdf):
    langlocale = locale.getdefaultlocale()[0]
    locale.setlocale(locale.LC_ALL, langlocale)
    dp = locale.localeconv()['decimal_point']
    data = pd.read_csv(input_file, sep=",", decimal=dp)
    plt.rcParams.update({'font.size': 15})
    fig, ax_arr = plt.subplots(nrows=len(data.dispatcherType.unique()), ncols=1, figsize=(20, 15))
    draw(data, ax_arr)
    lines, labels = ax_arr[0].get_legend_handles_labels()
    fig.legend(lines, labels, loc='upper center', borderpad=0, ncol=2, frameon=False, borderaxespad=2, prop={'size': 15})

    plt.tight_layout(pad=12, w_pad=2, h_pad=1)
    pdf.savefig(bbox_inches='tight')

with PdfPages(output_file) as pdf:
    gen_file(pdf)