package com.kalix.ide.flowviz.stats;

import java.util.Collections;
import java.util.List;

/**
 * Central registry of all available statistics.
 */
public class StatisticsRegistry {

    /**
     * The complete list of available statistics in display order.
     * This is the single source of truth for what statistics are available.
     */
    private static final List<Statistic> ALL_STATISTICS = List.of(
        new MinStatistic(),
        new MaxStatistic(),
        new MeanStatistic(),
        new CountStatistic(),
        new BiasStatistic(),
        new SdebStatistic()
    );

    /**
     * Gets all available statistics in display order.
     *
     * @return Unmodifiable list of all statistics
     */
    public static List<Statistic> getAll() {
        return Collections.unmodifiableList(ALL_STATISTICS);
    }

    /**
     * Gets the number of statistic columns (not including the index or "Series" columns).
     *
     * @return Number of statistics
     */
    public static int getStatisticCount() {
        return ALL_STATISTICS.size();
    }
}
