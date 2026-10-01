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

    enum Observation { OBSERVED, NO_ACCESS, WAITING_FOR_LISTENER, NO_NOTIFICATION }

    static Observation observation(boolean access, long connected, long posted, long bootEpoch) {
        if (belongsToCurrentBoot(posted, bootEpoch)) return Observation.OBSERVED;
        if (!access) return Observation.NO_ACCESS;
        return belongsToCurrentBoot(connected, bootEpoch)
            ? Observation.NO_NOTIFICATION : Observation.WAITING_FOR_LISTENER;
    }

    static boolean shouldRecordPost(long existing, long candidate, long bootEpoch) {
        return belongsToCurrentBoot(candidate, bootEpoch)
            && shouldRecordEarlierPost(belongsToCurrentBoot(existing, bootEpoch) ? existing : 0, candidate);
    }

    static long secondsSinceBoot(long eventTime, long bootEpoch) {
        return Math.max(0L, eventTime - bootEpoch) / 1_000L;
    }
}
