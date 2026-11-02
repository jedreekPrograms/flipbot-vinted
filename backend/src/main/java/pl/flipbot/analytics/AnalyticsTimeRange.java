package pl.flipbot.analytics;

import java.time.LocalDate;
import java.time.LocalDateTime;
import pl.flipbot.exception.InvalidRequestException;
import pl.flipbot.dashboard.DashboardPeriod;

record AnalyticsTimeRange(LocalDateTime from, LocalDateTime to) {
    boolean contains(LocalDateTime value) {
        return value != null && !value.isBefore(from) && !value.isAfter(to);
    }

    static AnalyticsTimeRange resolveRange(
            DashboardPeriod period,
            LocalDate fromDate,
            LocalDate toDate,
            LocalDateTime now
    ) {
        LocalDateTime from = fromDate == null
                ? periodStart(period, now.toLocalDate())
                : fromDate.atStartOfDay();
        LocalDateTime to = toDate == null
                ? now
                : toDate.plusDays(1).atStartOfDay().minusNanos(1);

        if (from.isAfter(to)) {
            throw new InvalidRequestException("Analytics 'from' date cannot be after 'to' date.");
        }

        return new AnalyticsTimeRange(from, to);
    }

    static LocalDateTime periodStart(DashboardPeriod period, LocalDate today) {
        return switch (period) {
            case TODAY -> today.atStartOfDay();
            case LAST_7_DAYS -> today.minusDays(6).atStartOfDay();
            case LAST_30_DAYS -> today.minusDays(29).atStartOfDay();
            case THIS_MONTH -> today.withDayOfMonth(1).atStartOfDay();
            case THIS_YEAR -> today.withDayOfYear(1).atStartOfDay();
            case ALL -> LocalDateTime.MIN;
        };
    }
}
