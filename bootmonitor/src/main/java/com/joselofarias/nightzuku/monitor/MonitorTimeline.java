package com.joselofarias.nightzuku.monitor;

final class MonitorTimeline {
    static final long BOOT_CLOCK_TOLERANCE_MS = 2_000L;

    private MonitorTimeline() {}

    static long bootEpoch(long wallTime, long elapsedRealtime) {
        return wallTime - Math.max(0L, elapsedRealtime);
    }

    static boolean belongsToCurrentBoot(long eventTime, long bootEpoch) {
        return eventTime > 0L && eventTime >= bootEpoch - BOOT_CLOCK_TOLERANCE_MS;
    }

    static boolean shouldRecordEarlierPost(long existingPostTime, long candidatePostTime) {
        return candidatePostTime > 0L
            && (existingPostTime == 0L || candidatePostTime < existingPostTime);
    }

    static long secondsSinceBoot(long eventTime, long bootEpoch) {
        return Math.max(0L, eventTime - bootEpoch) / 1_000L;
    }
}
