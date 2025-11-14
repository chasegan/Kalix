package com.kalix.ide.flowviz.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Central registry of all available statistics.
 * Provides easy access to statistics by type (univariate, bivariate, all).
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
     * Gets only univariate statistics (those that don't require a reference series).
     *
     * @return List of univariate statistics
     */
    public static List<Statistic> getUnivariateStatistics() {
        return ALL_STATISTICS.stream()
            .filter(stat -> !stat.isBivariate())
            .collect(Collectors.toList());
    }

    /**
     * Gets only bivariate statistics (those that require comparison to a reference series).
     *
     * @return List of bivariate statistics
     */
    public static List<Statistic> getBivariateStatistics() {
        return ALL_STATISTICS.stream()
            .filter(Statistic::isBivariate)
            .collect(Collectors.toList());
    }

    /**
     * Gets column names for a stats table, starting with "Series" followed by each statistic name.
     *
     * @return Array of column names
     */
    public static String[] getColumnNames() {
        List<String> names = new ArrayList<>();
        names.add("Series");
        for (Statistic stat : ALL_STATISTICS) {
            names.add(stat.getName());
        }
        return names.toArray(new String[0]);
    }

    /**
     * Gets the number of statistic columns (not including the "Series" column).
     *
     * @return Number of statistics
     */
    public static int getStatisticCount() {
        return ALL_STATISTICS.size();
    }
}
