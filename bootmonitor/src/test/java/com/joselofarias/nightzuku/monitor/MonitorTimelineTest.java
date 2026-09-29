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
}
