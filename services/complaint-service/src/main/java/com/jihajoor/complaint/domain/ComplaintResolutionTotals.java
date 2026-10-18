package com.jihajoor.complaint.domain;

/**
 * Resolution totals behind the Admin stats report (Requirement 16.9), produced by
 * {@link ComplaintRepository#resolutionTotals()}: the complaints in a terminal state with a
 * resolution time, and the sum of their creation-to-resolution times in milliseconds.
 *
 * @param resolved          number of RESOLVED/CLOSED complaints with a {@code resolved_at}
 * @param resolutionMillis  sum of {@code resolved_at - created_at} over them, each floored to the
 *                          millisecond; {@code null} when there are none
 */
public record ComplaintResolutionTotals(Long resolved, Number resolutionMillis) {
}
