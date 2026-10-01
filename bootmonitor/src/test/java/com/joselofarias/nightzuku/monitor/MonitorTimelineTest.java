package com.joselofarias.nightzuku.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MonitorTimelineTest {
    @Test public void derivesSystemBootEpochFromWallAndElapsedTime() {
        assertEquals(1_575_000L, MonitorTimeline.bootEpoch(1_700_000L, 125_000L));
    }

    @Test public void acceptsCurrentBootEventsAcrossReceiverOrdering() {
        long bootEpoch = 1_000_000L;
        assertTrue(MonitorTimeline.belongsToCurrentBoot(bootEpoch - 2_000L, bootEpoch));
        assertTrue(MonitorTimeline.belongsToCurrentBoot(bootEpoch + 10_000L, bootEpoch));
        assertFalse(MonitorTimeline.belongsToCurrentBoot(bootEpoch - 2_001L, bootEpoch));
        assertFalse(MonitorTimeline.belongsToCurrentBoot(0L, bootEpoch));
    }

    @Test public void keepsFirstObservedPostForTheCurrentBoot() {
        assertTrue(MonitorTimeline.shouldRecordEarlierPost(0L, 20_000L));
        assertTrue(MonitorTimeline.shouldRecordEarlierPost(20_000L, 19_000L));
        assertFalse(MonitorTimeline.shouldRecordEarlierPost(20_000L, 21_000L));
        assertFalse(MonitorTimeline.shouldRecordEarlierPost(0L, 0L));
    }

    @Test public void reportsDelayFromSystemBootNotBootCompletedReceiver() {
        assertEquals(5L, MonitorTimeline.secondsSinceBoot(1_580_999L, 1_575_000L));
        assertEquals(0L, MonitorTimeline.secondsSinceBoot(1_574_000L, 1_575_000L));
    }
    @Test public void previousBootCannotBeReportedAsSuccessful() {
        assertEquals(MonitorTimeline.Observation.WAITING_FOR_LISTENER,
            MonitorTimeline.observation(true, 900_000, 910_000, 1_000_000));
        assertEquals(MonitorTimeline.Observation.NO_NOTIFICATION,
            MonitorTimeline.observation(true, 1_005_000, 910_000, 1_000_000));
    }

    @Test public void observationDoesNotDependOnBootReceiverArrival() {
        assertEquals(MonitorTimeline.Observation.OBSERVED,
            MonitorTimeline.observation(true, 0, 1_005_000, 1_000_000));
        assertEquals(MonitorTimeline.Observation.NO_ACCESS,
            MonitorTimeline.observation(false, 0, 0, 1_000_000));
    }

    @Test public void newBootPostReplacesStalePostBeforeReceiverClearsIt() {
        assertTrue(MonitorTimeline.shouldRecordPost(910_000, 1_005_000, 1_000_000));
        assertFalse(MonitorTimeline.shouldRecordPost(1_005_000, 1_006_000, 1_000_000));
        assertFalse(MonitorTimeline.shouldRecordPost(0, 910_000, 1_000_000));
    }
}
