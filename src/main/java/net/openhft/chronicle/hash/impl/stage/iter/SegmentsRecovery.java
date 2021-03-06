/*
 *      Copyright (C) 2015  higherfrequencytrading.com
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.hash.impl.stage.iter;

import net.openhft.chronicle.hash.VanillaGlobalMutableState;
import net.openhft.chronicle.hash.impl.TierCountersArea;
import net.openhft.chronicle.hash.impl.VanillaChronicleHash;
import net.openhft.chronicle.hash.impl.VanillaChronicleHashHolder;
import net.openhft.chronicle.hash.impl.stage.entry.SegmentStages;
import net.openhft.chronicle.hash.impl.stage.hash.LogHolder;
import net.openhft.chronicle.map.impl.IterationContext;
import net.openhft.sg.StageRef;
import net.openhft.sg.Staged;
import org.slf4j.Logger;

@Staged
public abstract class SegmentsRecovery implements IterationContext {

    @StageRef VanillaChronicleHashHolder<?> hh;
    @StageRef SegmentStages s;
    @StageRef TierRecovery tierRecovery;
    @StageRef LogHolder lh;

    @Override
    public void recoverSegments() {
        Logger log = lh.LOG;
        VanillaChronicleHash<?, ?, ?, ?> h = hh.h();
        for (int segmentIndex = 0; segmentIndex < h.actualSegments; segmentIndex++) {
            s.initSegmentIndex(segmentIndex);
            resetSegmentLock();
            zeroOutFirstSegmentTierCountersArea();
            tierRecovery.recoverTier(segmentIndex);
        }

        VanillaGlobalMutableState globalMutableState = h.globalMutableState();
        long storedExtraTiersInUse = globalMutableState.getExtraTiersInUse();
        long allocatedExtraTiers = globalMutableState.getAllocatedExtraTierBulks() * h.tiersInBulk;
        long expectedExtraTiersInUse =
                Math.max(0, Math.min(storedExtraTiersInUse, allocatedExtraTiers));
        long actualExtraTiersInUse = 0;
        long firstFreeExtraTierIndex = -1;
        for (long extraTierIndex = 0; extraTierIndex < expectedExtraTiersInUse; extraTierIndex++) {
            long tierIndex = h.extraTierIndexToTierIndex(extraTierIndex);
            // `tier` is unused in recoverTier(), 0 should be a safe value
            s.initSegmentTier(0, tierIndex);
            int segmentIndex = tierRecovery.recoverTier(-1);
            if (segmentIndex >= 0) {
                long tierCountersAreaAddr = s.tierCountersAreaAddr();
                int storedSegmentIndex = TierCountersArea.segmentIndex(tierCountersAreaAddr);
                if (storedSegmentIndex != segmentIndex) {
                    log.error("wrong segment index stored in tier counters area " +
                            "of tier with index {}: {}, should be, based on entries: {}",
                            tierIndex, storedSegmentIndex, segmentIndex);
                    TierCountersArea.segmentIndex(tierCountersAreaAddr, segmentIndex);
                }
                s.nextTierIndex(0);

                s.initSegmentIndex(segmentIndex);
                s.goToLastTier();
                s.nextTierIndex(tierIndex);

                TierCountersArea.prevTierIndex(tierCountersAreaAddr, s.tierIndex);
                TierCountersArea.tier(tierCountersAreaAddr, s.tier + 1);
                actualExtraTiersInUse = extraTierIndex + 1;
            } else {
                firstFreeExtraTierIndex = extraTierIndex;
                break;
            }
        }

        if (storedExtraTiersInUse != actualExtraTiersInUse) {
            log.error("wrong number of actual tiers in use in global mutable state, stored: {}, " +
                    "should be: {}", storedExtraTiersInUse, actualExtraTiersInUse);
            globalMutableState.setExtraTiersInUse(actualExtraTiersInUse);
        }

        long firstFreeTierIndex;
        if (firstFreeExtraTierIndex == -1) {
            if (allocatedExtraTiers > expectedExtraTiersInUse) {
                firstFreeTierIndex = h.extraTierIndexToTierIndex(expectedExtraTiersInUse);
            } else {
                firstFreeTierIndex = 0;
            }
        } else {
            firstFreeTierIndex = h.extraTierIndexToTierIndex(firstFreeExtraTierIndex);
        }
        if (firstFreeTierIndex > 0) {
            long lastTierIndex = h.extraTierIndexToTierIndex(allocatedExtraTiers - 1);
            h.linkAndZeroOutFreeTiers(firstFreeTierIndex, lastTierIndex);
        }
        long storedFirstFreeTierIndex = globalMutableState.getFirstFreeTierIndex();
        if (storedFirstFreeTierIndex != firstFreeTierIndex) {
            log.error("wrong first free tier index in global mutable state, stored: {}, " +
                    "should be: {}", storedFirstFreeTierIndex, firstFreeTierIndex);
            globalMutableState.setFirstFreeTierIndex(firstFreeTierIndex);
        }

        removeDuplicatesInSegments();
    }

    private void removeDuplicatesInSegments() {
        VanillaChronicleHash<?, ?, ?, ?> h = hh.h();
        for (int segmentIndex = 0; segmentIndex < h.actualSegments; segmentIndex++) {
            s.initSegmentIndex(segmentIndex);
            s.initSegmentTier();
            s.goToLastTier();
            while (true) {
                tierRecovery.removeDuplicatesInSegment();
                if (s.tier > 0) {
                    s.prevTier();
                } else {
                    break;
                }
            }
        }
    }

    private void resetSegmentLock() {
        long lockState = s.segmentHeader.getLockState(s.segmentHeaderAddress);
        if (lockState != s.segmentHeader.resetLockState()) {
            lh.LOG.error("lock of segment {} is not clear: {}",
                    s.segmentIndex, s.segmentHeader.lockStateToString(lockState));
            s.segmentHeader.resetLock(s.segmentHeaderAddress);
        }
    }

    private void zeroOutFirstSegmentTierCountersArea() {
        s.nextTierIndex(0);
        if (s.prevTierIndex() != 0) {
            lh.LOG.error("stored prev tier index in first tier of segment {}: {}, should be 0",
                    s.segmentIndex, s.prevTierIndex());
            s.prevTierIndex(0);
        }
        long tierCountersAreaAddr = s.tierCountersAreaAddr();
        if (TierCountersArea.segmentIndex(tierCountersAreaAddr) != 0) {
            lh.LOG.error("stored segment index in first tier of segment {}: {}, should be 0",
                    s.segmentIndex, TierCountersArea.segmentIndex(tierCountersAreaAddr));
            TierCountersArea.segmentIndex(tierCountersAreaAddr, 0);
        }
        if (TierCountersArea.tier(tierCountersAreaAddr) != 0) {
            lh.LOG.error("stored tier in first tier of segment {}: {}, should be 0",
                    s.segmentIndex, TierCountersArea.tier(tierCountersAreaAddr));
            TierCountersArea.tier(tierCountersAreaAddr, 0);
        }
    }
}
