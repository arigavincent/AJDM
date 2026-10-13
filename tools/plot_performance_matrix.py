"""Render standalone comparison figures from verified measured matrix samples."""
import argparse
import csv
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def percentile(values, quantile):
    values = sorted(values)
    rank = (len(values) - 1) * quantile
    lower = int(rank)
    fraction = rank - lower
    return values[lower] * (1 - fraction) + values[min(lower + 1, len(values) - 1)] * fraction


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    with (args.directory / "samples.csv").open(newline="") as stream:
        samples = list(csv.DictReader(stream))
    scenarios = list(dict.fromkeys(row["scenario"] for row in samples))
    figure, axes = plt.subplots(2, 3, figsize=(12, 7.5), constrained_layout=True)
    modes = ["single", "fixed", "adaptive"]
    colors = ["#526978", "#dc7d3c", "#8063ac"]
    for axis, scenario in zip(axes.flat, scenarios):
        for index, mode in enumerate(modes):
            values = [float(row["seconds"]) for row in samples if row["scenario"] == scenario and row["mode"] == mode]
            median = percentile(values, .5)
            axis.barh(index, median, color=colors[index], height=.56)
            axis.errorbar(median, index, xerr=[[median - percentile(values, .1)], [percentile(values, .9) - median]],
                          color="#202b34", capsize=4, linewidth=1.5)
            axis.scatter(values, [index] * len(values), color="#202b34", s=13, zorder=3, alpha=.65)
            axis.text(median, index - .35, f"{median:.3f} s", fontsize=9, color="#202b34")
        axis.set_yticks(range(3), modes)
        axis.invert_yaxis()
        axis.set_title(scenario.replace("-", " "), loc="left", fontsize=12, fontweight="bold")
        axis.set_xlabel("Completion time (seconds)")
        axis.grid(axis="x", color="#dee3e8", linewidth=.7)
        axis.set_axisbelow(True)
        axis.spines[["top", "right"]].set_visible(False)
        axis.set_ylim(2.6, -.65)
    figure.suptitle("AJDM: single, fixed four-worker, and adaptive up to four", fontsize=16, fontweight="bold")
    figure.supxlabel("Six rounds per mode/scenario. Bars: medians. Whiskers: interpolated p10–p90. Dots: every run.\n"
                       "Loopback HTTP fixtures with warm JVM/filesystem caches; no IDM or real 4G/5G result.", fontsize=10)
    for extension in ["svg", "png"]:
        figure.savefig(args.directory / ("completion-times." + extension), dpi=180, facecolor="white")
    plt.close(figure)
    print(args.directory / "completion-times.svg")


if __name__ == "__main__":
    main()
