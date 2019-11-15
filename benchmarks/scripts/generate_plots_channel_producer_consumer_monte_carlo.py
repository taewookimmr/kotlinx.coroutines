import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.ticker import FormatStrFormatter
from matplotlib.backends.backend_pdf import PdfPages

inputFile = "out/results_channel_producer_consumer_montecarlo.csv"
outputFile = "out/channel-producer-consumer-monte-carlo.pdf"

markers = ['.', 'v', '^', '1', '2', '8', 'p', 'P', 'x', 'D', 'd', 's']
colours = ['black', 'silver', 'red', 'gold', 'sienna', 'olivedrab', 'lightseagreen', 'navy', 'blue', 'm', 'crimson', 'yellow', 'orangered', 'slateblue', 'aqua']

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
        ax.set_ylabel("time (ms)")
        ax.set_xlabel('threads')
        ax.set_xticks(data.threads.unique())

    i = 0
    for dispatcher_type in data.dispatcherType.unique():
        for with_balancing in data.withBalancing.unique():
            colour_gen = next_colour()
            marker_gen = next_marker()
            flatten_ax_arr[i].set_title("with_balancing={},dispatcher_type={}".format(with_balancing, dispatcher_type))
            for channel in data.channel.unique():
                for with_select in data.withSelect.unique():
                    gen_colour = next(colour_gen)
                    gen_marker = next(marker_gen)
                    res = data[(data.withBalancing == with_balancing) & (data.dispatcherType == dispatcher_type) & (data.withSelect == with_select) & (data.channel == channel)]
                    flatten_ax_arr[i].plot(res.threads, res.result, label="channel={},with_select={}".format(channel, with_select), color=gen_colour, marker=gen_marker)
            i += 1

def gen_file(pdf):
    data = pd.read_csv(inputFile, sep=",")
    fig, ax_arr = plt.subplots(nrows=len(data.dispatcherType.unique()), ncols=2, figsize=(20, 15))
    draw(data, ax_arr)
    lines, labels = ax_arr[0, 0].get_legend_handles_labels()
    fig.legend(lines, labels, loc='upper center', borderpad=0, ncol=4, frameon=False, borderaxespad=4, prop={'size': 8})

    plt.tight_layout(pad=12, w_pad=2, h_pad=1)
    pdf.savefig(bbox_inches='tight')

with PdfPages(outputFile) as pdf:
    gen_file(pdf)