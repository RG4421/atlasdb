/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.sweep.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.palantir.atlasdb.sweep.metrics.SweepMetricsAssert.assertThat;
import static com.palantir.atlasdb.sweep.queue.SweepQueueUtils.BATCH_SIZE_KVS;
import static com.palantir.atlasdb.sweep.queue.SweepQueueUtils.MAX_CELLS_GENERIC;
import static com.palantir.atlasdb.sweep.queue.SweepQueueUtils.SWEEP_BATCH_SIZE;
import static com.palantir.atlasdb.sweep.queue.SweepQueueUtils.TS_COARSE_GRANULARITY;
import static com.palantir.atlasdb.sweep.queue.SweepQueueUtils.TS_FINE_GRANULARITY;
import static com.palantir.atlasdb.sweep.queue.SweepQueueUtils.maxTsForFinePartition;
import static com.palantir.atlasdb.sweep.queue.SweepQueueUtils.tsPartitionFine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jetty.util.ConcurrentHashSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.math.IntMath;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.cleaner.KeyValueServicePuncherStore;
import com.palantir.atlasdb.cleaner.PuncherStore;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.api.Value;
import com.palantir.atlasdb.sweep.metrics.SweepOutcome;
import com.palantir.exception.NotInitializedException;
import com.palantir.lock.LockDescriptor;
import com.palantir.lock.v2.LockRequest;
import com.palantir.lock.v2.LockResponse;
import com.palantir.lock.v2.LockToken;
import com.palantir.lock.v2.TimelockService;

public class TargetedSweeperTest extends AbstractSweepQueueTest {
    private static final long LOW_TS = 10L;
    private static final long LOW_TS2 = 2 * LOW_TS;
    private static final long LOW_TS3 = 3 * LOW_TS;

    private TargetedSweeper sweepQueue;
    private ShardProgress progress;
    private SweepableTimestamps sweepableTimestamps;
    private SweepableCells sweepableCells;
    private TargetedSweepFollower mockFollower;
    private TimelockService timelockService;
    private PuncherStore puncherStore;
    private boolean enabled = true;

    @Before
    public void setup() {
        super.setup();
        sweepQueue = TargetedSweeper.createUninitializedForTest(metricsManager, () -> enabled, () -> DEFAULT_SHARDS);
        mockFollower = mock(TargetedSweepFollower.class);

        timelockService = mock(TimelockService.class);
        sweepQueue.initializeWithoutRunning(timestampsSupplier, timelockService, spiedKvs, mockFollower);

        progress = new ShardProgress(spiedKvs);
        sweepableTimestamps = new SweepableTimestamps(spiedKvs, partitioner);
        sweepableCells = new SweepableCells(spiedKvs, partitioner, null);
        puncherStore = KeyValueServicePuncherStore.create(spiedKvs, false);
    }

    @Test
    public void callingEnqueueAndSweepOnUninitializedSweeperThrows() {
        TargetedSweeper uninitializedSweeper = TargetedSweeper.createUninitializedForTest(null);
        assertThatThrownBy(() -> uninitializedSweeper.enqueue(ImmutableList.of()))
                .isInstanceOf(NotInitializedException.class)
                .hasMessageContaining("Targeted Sweeper");
        assertThatThrownBy(() -> uninitializedSweeper.sweepNextBatch(ShardAndStrategy.conservative(0)))
                .isInstanceOf(NotInitializedException.class)
                .hasMessageContaining("Targeted Sweeper");
    }

    @Test
    public void initializingWithUninitializedKvsThrows() {
        KeyValueService uninitializedKvs = mock(KeyValueService.class);
        when(uninitializedKvs.isInitialized()).thenReturn(false);
        TargetedSweeper sweeper = TargetedSweeper.createUninitializedForTest(null);
        assertThatThrownBy(() -> sweeper.initializeWithoutRunning(
                null, mock(TimelockService.class), uninitializedKvs, mock(TargetedSweepFollower.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void enqueueUpdatesNumberOfShards() {
        assertThat(AtlasDbConstants.DEFAULT_SWEEP_QUEUE_SHARDS).isLessThan(DEFAULT_SHARDS);
        assertThat(progress.getNumberOfShards()).isEqualTo(AtlasDbConstants.DEFAULT_SWEEP_QUEUE_SHARDS);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        assertThat(progress.getNumberOfShards()).isEqualTo(DEFAULT_SHARDS);
    }

    @Test
    public void sweepStrategyNothingDoesNotPersistAnything() {
        enqueueWriteCommitted(TABLE_NOTH, LOW_TS);
        enqueueWriteCommitted(TABLE_NOTH, LOW_TS2);
        verify(spiedKvs, times(2)).put(eq(TABLE_NOTH), anyMap(), anyLong());
        verify(spiedKvs, times(2)).put(any(TableReference.class), anyMap(), anyLong());
    }

    @Test
    public void sweepStrategyNothingDoesNotUpdateMetrics() {
        enqueueWriteCommitted(TABLE_NOTH, LOW_TS);
        enqueueWriteCommitted(TABLE_NOTH, LOW_TS2);
        assertThat(metricsManager).hasEnqueuedWritesConservativeEqualTo(0);
        assertThat(metricsManager).hasEnqueuedWritesThoroughEqualTo(0);
    }

    @Test
    public void conservativeSweepAddsSentinelAndLeavesSingleValue() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        assertReadAtTimestampReturnsNothing(TABLE_CONS, LOW_TS);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS);
    }

    @Test
    public void sweepWithSingleEntryUpdatesMetrics() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));

        assertThat(metricsManager).hasTombstonesPutConservativeEqualTo(1);
        assertThat(metricsManager).hasSweepTimestampConservativeEqualTo(
                getSweepTsCons());
        assertThat(metricsManager).hasLastSweptTimestampConservativeEqualTo(
                maxTsForFinePartition(0));

        setTimelockTime(5_000L);
        punchTimeAtTimestamp(2_000, LOW_TS);
        // all entries were swept
        assertThat(metricsManager).hasMillisSinceLastSweptConservativeEqualTo(0L);
        assertThat(metricsManager).hasTargetedOutcomeEqualTo(SweepOutcome.SUCCESS, 1L);
    }

    @Test
    public void sweepWithNoCandidatesBeforeSweepTimestampReportsNothingToSweep() {
        enqueueWriteCommitted(TABLE_CONS, getSweepTsCons());
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));

        assertThat(metricsManager).hasTargetedOutcomeEqualTo(SweepOutcome.NOTHING_TO_SWEEP, 1L);
    }

    @Test
    public void sweepDisabledIsReportedInOutcome() {
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(metricsManager).hasTargetedOutcomeEqualTo(SweepOutcome.NOTHING_TO_SWEEP, 1L);

        enabled = false;
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(metricsManager).hasTargetedOutcomeEqualTo(SweepOutcome.DISABLED, 1L);
    }

    @Test
    public void thoroughSweepDoesNotAddSentinelAndLeavesSingleValue() {
        enqueueWriteCommitted(TABLE_THOR, LOW_TS);
        assertReadAtTimestampReturnsNothing(TABLE_THOR, LOW_TS);

        sweepQueue.sweepNextBatch(ShardAndStrategy.thorough(THOR_SHARD));
        assertReadAtTimestampReturnsNothing(TABLE_THOR, LOW_TS);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_THOR, LOW_TS);
    }

    @Test
    public void thoroughSweepDeletesExistingSentinel() {
        spiedKvs.addGarbageCollectionSentinelValues(TABLE_THOR, ImmutableList.of(DEFAULT_CELL));
        assertReadAtTimestampReturnsSentinel(TABLE_THOR, 0L);
        enqueueWriteCommitted(TABLE_THOR, 10L);
        sweepQueue.sweepNextBatch(ShardAndStrategy.thorough(THOR_SHARD));
        assertReadAtTimestampReturnsNothing(TABLE_THOR, 0L);
    }

    @Test
    public void conservativeSweepDeletesLowerValue() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS2);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS2);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS + 1);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS2);
    }

    @Test
    public void thoroughSweepDeletesLowerValue() {
        enqueueWriteCommitted(TABLE_THOR, LOW_TS);
        enqueueWriteCommitted(TABLE_THOR, LOW_TS2);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_THOR, LOW_TS);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_THOR, LOW_TS2);

        sweepQueue.sweepNextBatch(ShardAndStrategy.thorough(THOR_SHARD));
        assertReadAtTimestampReturnsNothing(TABLE_THOR, LOW_TS + 1);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_THOR, LOW_TS2);
    }

    @Test
    public void conservativeSweepCallsFollower() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS2);
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));

        ArgumentCaptor<Set> captor = ArgumentCaptor.forClass(Set.class);
        verify(mockFollower, times(1)).run(eq(TABLE_CONS), captor.capture());
        assertThat(Iterables.getOnlyElement(captor.getAllValues())).containsExactly(DEFAULT_CELL);
    }

    @Test
    public void thoroughSweepCallsFollower() {
        enqueueWriteCommitted(TABLE_THOR, LOW_TS);
        enqueueWriteCommitted(TABLE_THOR, LOW_TS2);
        sweepQueue.sweepNextBatch(ShardAndStrategy.thorough(THOR_SHARD));

        ArgumentCaptor<Set> captor = ArgumentCaptor.forClass(Set.class);
        verify(mockFollower, times(1)).run(eq(TABLE_THOR), captor.capture());
        assertThat(Iterables.getOnlyElement(captor.getAllValues())).containsExactly(DEFAULT_CELL);
    }

    @Test
    public void conservativeSweepDeletesAllButLatestWithSingleDeleteAllTimestamps() {
        long lastWriteTs = TS_FINE_GRANULARITY - 1;
        for (long i = 0; i <= lastWriteTs; i++) {
            enqueueWriteCommitted(TABLE_CONS, i);
        }
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, lastWriteTs);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, lastWriteTs);
        verify(spiedKvs, times(1)).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(false));
    }

    @Test
    public void thoroughSweepDeletesAllButLatestWithSingleDeleteAllTimestampsIncludingSentinels() {
        long lastWriteTs = TS_FINE_GRANULARITY - 1;
        for (long i = 0; i <= lastWriteTs; i++) {
            enqueueWriteCommitted(TABLE_THOR, i);
        }
        sweepQueue.sweepNextBatch(ShardAndStrategy.thorough(THOR_SHARD));
        assertReadAtTimestampReturnsNothing(TABLE_THOR, lastWriteTs);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_THOR, lastWriteTs);
        verify(spiedKvs, times(1)).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(true));
    }

    @Test
    public void onlySweepsOneBatchAtATime() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS2);
        enqueueWriteCommitted(TABLE_CONS, TS_FINE_GRANULARITY);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS + 1);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS2);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, TS_FINE_GRANULARITY);
    }

    @Test
    public void sweepDeletesWritesWhenTombstoneHasHigherTimestamp() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueTombstone(TABLE_CONS, LOW_TS2);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS);
        assertReadAtTimestampReturnsTombstoneAtTimestamp(TABLE_CONS, LOW_TS2 + 1, LOW_TS2);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS + 1);
        assertReadAtTimestampReturnsTombstoneAtTimestamp(TABLE_CONS, LOW_TS2 + 1, LOW_TS2);
    }

    @Test
    public void thoroughSweepDeletesTombstoneIfLatestWrite() {
        enqueueTombstone(TABLE_THOR, LOW_TS);
        enqueueTombstone(TABLE_THOR, LOW_TS2);
        assertReadAtTimestampReturnsTombstoneAtTimestamp(TABLE_THOR, LOW_TS + 1, LOW_TS);
        assertReadAtTimestampReturnsTombstoneAtTimestamp(TABLE_THOR, LOW_TS2 + 1, LOW_TS2);

        sweepQueue.sweepNextBatch(ShardAndStrategy.thorough(THOR_SHARD));
        assertReadAtTimestampReturnsNothing(TABLE_THOR, LOW_TS + 1);
        assertReadAtTimestampReturnsNothing(TABLE_THOR, LOW_TS2 + 1);
    }

    @Test
    public void sweepDeletesTombstonesWhenWriteHasHigherTimestamp() {
        enqueueTombstone(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS2);
        assertReadAtTimestampReturnsTombstoneAtTimestamp(TABLE_CONS, LOW_TS + 1, LOW_TS);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS2);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS + 1);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS2);
    }

    @Test
    public void sweepHandlesSequencesOfDeletesAndReadditionsInOneShot() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueTombstone(TABLE_CONS, LOW_TS + 2);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS + 4);
        enqueueTombstone(TABLE_CONS, LOW_TS + 6);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS + 8);
        // ensure not all entries will be swept
        enqueueWriteCommitted(TABLE_CONS, maxTsForFinePartition(0) + 1);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        for (int i = 0; i < 10; i = i + 2) {
            assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS + i);
        }
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS + 8);
        verify(spiedKvs, times(1)).deleteAllTimestamps(any(), any(), eq(false));

        assertThat(metricsManager).hasTombstonesPutConservativeEqualTo(1);
        assertThat(metricsManager).hasLastSweptTimestampConservativeEqualTo(
                maxTsForFinePartition(0));

        setTimelockTime(10_000L);
        punchTimeAtTimestamp(5_000L, LOW_TS + 8);
        assertThat(metricsManager).hasMillisSinceLastSweptConservativeEqualTo(10_000L - 5000L);
    }

    @Test
    public void sweepProgressesAndSkipsEmptyFinePartitions() {
        long tsFineTwo = LOW_TS + TS_FINE_GRANULARITY;
        long tsFineFour = LOW_TS + 3 * TS_FINE_GRANULARITY;
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        punchTimeAtTimestamp(100L, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, tsFineTwo);
        punchTimeAtTimestamp(200L, tsFineTwo);
        enqueueWriteCommitted(TABLE_CONS, tsFineFour);
        punchTimeAtTimestamp(300L, tsFineFour);
        enqueueWriteCommitted(TABLE_CONS, tsFineFour + 1L);
        punchTimeAtTimestamp(400L, tsFineFour + 1L);

        // add one more entry that will not be swept
        enqueueWriteCommitted(TABLE_CONS, 5 * TS_FINE_GRANULARITY);
        punchTimeAtTimestamp(3000L, 5 * TS_FINE_GRANULARITY);


        // first sweep effectively only writes a sentinel
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS);
        assertThat(metricsManager).hasTombstonesPutConservativeEqualTo(1);

        // second sweep deletes first entry
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, tsFineTwo);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, tsFineTwo);
        assertThat(metricsManager).hasTombstonesPutConservativeEqualTo(2);

        // third sweep deletes all but last entry
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, tsFineFour + 1);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, tsFineFour + 1);
        assertThat(metricsManager).hasTombstonesPutConservativeEqualTo(3);
        assertThat(metricsManager).hasEntriesReadConservativeEqualTo(4);
        assertThat(metricsManager).hasLastSweptTimestampConservativeEqualTo(maxTsForFinePartition(3));

        setTimelockTime(5000L);
        assertThat(metricsManager).hasMillisSinceLastSweptConservativeEqualTo(5000L - 400L);
    }

    @Test
    public void sweepProgressesAcrossCoarsePartitions() {
        long tsCoarseTwo = LOW_TS + TS_FINE_GRANULARITY + TS_COARSE_GRANULARITY;
        long tsCoarseFour = LOW_TS + 3 * TS_COARSE_GRANULARITY;
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, tsCoarseTwo);
        enqueueWriteCommitted(TABLE_CONS, tsCoarseFour);
        enqueueWriteCommitted(TABLE_CONS, tsCoarseFour + 1L);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, tsCoarseTwo);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, tsCoarseTwo);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, tsCoarseFour + 1);
        assertReadAtTimestampReturnsValue(TABLE_CONS, tsCoarseFour + 2, tsCoarseFour + 1);
    }

    @Test
    public void sweepProgressesToJustBeforeSweepTsWhenNothingToSweep() {
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertProgressUpdatedToTimestamp(getSweepTsCons() - 1L);
    }

    @Test
    public void sweepProgressesToEndOfPartitionWhenFewValuesAndSweepTsLarge() {
        long writeTs = getSweepTsCons() - 3 * TS_FINE_GRANULARITY;
        enqueueWriteCommitted(TABLE_CONS, writeTs);
        enqueueWriteCommitted(TABLE_CONS, writeTs + 5);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertProgressUpdatedToTimestamp(maxTsForFinePartition(tsPartitionFine(writeTs)));

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertProgressUpdatedToTimestamp(getSweepTsCons() - 1L);
    }

    @Test
    public void sweepCellOnlyOnceWhenInLastPartitionBeforeSweepTs() {
        immutableTs = 2 * TS_COARSE_GRANULARITY - TS_FINE_GRANULARITY;
        verify(spiedKvs, never()).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(false));

        enqueueWriteCommitted(TABLE_CONS, immutableTs - 1);
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        verify(spiedKvs, times(1)).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(false));

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        verify(spiedKvs, times(1)).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(false));
    }

    @Test
    public void sweepableTimestampsGetsScrubbedWhenNoMoreToSweepButSweepTsInNewCoarsePartition() {
        long tsSecondPartitionFine = LOW_TS + TS_FINE_GRANULARITY;
        long largestFirstPartitionCoarse = TS_COARSE_GRANULARITY - 1L;
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, tsSecondPartitionFine);
        enqueueWriteCommitted(TABLE_CONS, largestFirstPartitionCoarse);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertLowestFinePartitionInSweepableTimestampsEquals(tsPartitionFine(LOW_TS));
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertLowestFinePartitionInSweepableTimestampsEquals(tsPartitionFine(LOW_TS));

        // after this sweep we progress to sweepTsConservative - 1
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertNoEntriesInSweepableTimestampsBeforeSweepTimestamp();
    }

    @Test
    public void sweepableTimestampsGetsScrubbedWhenLastSweptProgressesInNewCoarsePartition() {
        long tsSecondPartitionFine = LOW_TS + TS_FINE_GRANULARITY;
        long largestFirstPartitionCoarse = TS_COARSE_GRANULARITY - 1L;
        long thirdPartitionCoarse = 2 * TS_COARSE_GRANULARITY;
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, tsSecondPartitionFine);
        enqueueWriteCommitted(TABLE_CONS, largestFirstPartitionCoarse);
        enqueueWriteCommitted(TABLE_CONS, thirdPartitionCoarse);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertLowestFinePartitionInSweepableTimestampsEquals(tsPartitionFine(LOW_TS));
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertLowestFinePartitionInSweepableTimestampsEquals(tsPartitionFine(LOW_TS));

        // after this sweep we progress to thirdPartitionCoarse - 1
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertLowestFinePartitionInSweepableTimestampsEquals(tsPartitionFine(thirdPartitionCoarse));
    }

    @Test
    public void sweepableCellsGetsScrubbedWheneverLastSweptInNewPartition() {
        long tsSecondPartitionFine = LOW_TS + TS_FINE_GRANULARITY;
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS + 1L);
        enqueueWriteCommitted(TABLE_CONS, tsSecondPartitionFine);
        enqueueWriteCommitted(TABLE_CONS, getSweepTsCons());

        // last swept timestamp: TS_FINE_GRANULARITY - 1
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertSweepableCellsHasEntryForTimestamp(LOW_TS + 1);
        assertSweepableCellsHasEntryForTimestamp(tsSecondPartitionFine);
        assertSweepableCellsHasEntryForTimestamp(getSweepTsCons());

        // last swept timestamp: 2 * TS_FINE_GRANULARITY - 1
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertSweepableCellsHasNoEntriesInPartitionOfTimestamp(LOW_TS + 1);
        assertSweepableCellsHasEntryForTimestamp(tsSecondPartitionFine);
        assertSweepableCellsHasEntryForTimestamp(getSweepTsCons());

        // last swept timestamp: largestBeforeSweepTs
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertSweepableCellsHasNoEntriesInPartitionOfTimestamp(LOW_TS + 1);
        assertSweepableCellsHasNoEntriesInPartitionOfTimestamp(tsSecondPartitionFine);
        assertSweepableCellsHasEntryForTimestamp(getSweepTsCons());
    }

    @Test
    public void doesNotSweepBeyondSweepTimestamp() {
        writeValuesAroundSweepTimestampAndSweepAndCheck(getSweepTsCons(), 1);
    }

    @Test
    public void doesNotTransitivelyRetainWritesFromBeforeSweepTimestamp() {
        long sweepTimestamp = getSweepTsCons();
        enqueueWriteCommitted(TABLE_CONS, sweepTimestamp - 10);
        enqueueTombstone(TABLE_CONS, sweepTimestamp - 5);
        enqueueWriteCommitted(TABLE_CONS, sweepTimestamp + 5);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, sweepTimestamp - 5);
        assertReadAtTimestampReturnsTombstoneAtTimestamp(TABLE_CONS, sweepTimestamp - 5 + 1, sweepTimestamp - 5);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, sweepTimestamp + 5);
        assertThat(metricsManager).hasTombstonesPutConservativeEqualTo(1);
        assertThat(metricsManager).hasLastSweptTimestampConservativeEqualTo(
                sweepTimestamp - 1);
    }

    @Test
    public void sweepsOnlySweepableSegmentOfFinePartitions() {
        long sweepTs = TS_FINE_GRANULARITY + 1337;
        runWithConservativeSweepTimestamp(() -> writeValuesAroundSweepTimestampAndSweepAndCheck(sweepTs, 1), sweepTs);
    }

    @Test
    public void sweepsOnlySweepableSegmentAcrossFinePartitionBoundary() {
        long sweepTs = TS_FINE_GRANULARITY + 7;

        // Need 2 because need to cross a partition boundary
        runWithConservativeSweepTimestamp(() -> writeValuesAroundSweepTimestampAndSweepAndCheck(sweepTs, 2), sweepTs);
    }

    @Test
    public void sweepsOnlySweepableSegmentAcrossCoarsePartitionBoundary() {
        long sweepTs = TS_COARSE_GRANULARITY + 7;

        // Need 2 because need to cross a partition boundary
        runWithConservativeSweepTimestamp(() -> writeValuesAroundSweepTimestampAndSweepAndCheck(sweepTs, 2), sweepTs);
    }

    @Test
    public void remembersProgressWhenSweepTimestampAdvances() {
        long baseSweepTs = getSweepTsCons();
        long oldPartitionTs = baseSweepTs - 5;
        long newPartitionFirstTs = baseSweepTs + 5;
        long newPartitionSecondTs = baseSweepTs + 10;

        enqueueWriteCommitted(TABLE_CONS, oldPartitionTs);
        enqueueWriteCommitted(TABLE_CONS, newPartitionFirstTs);
        enqueueWriteCommitted(TABLE_CONS, newPartitionSecondTs);

        assertReadAtTimestampReturnsNothing(TABLE_CONS, oldPartitionTs);

        runConservativeSweepAtTimestamp(baseSweepTs + 7);
        runConservativeSweepAtTimestamp(baseSweepTs + 7);
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, oldPartitionTs + 1);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, newPartitionFirstTs);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, newPartitionSecondTs);

        runConservativeSweepAtTimestamp(newPartitionSecondTs + 1);
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, newPartitionSecondTs);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, newPartitionSecondTs);
    }

    @Test
    public void doesNotGoBackwardsEvenIfSweepTimestampRegressesWithinBucket() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS2);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS3);

        runConservativeSweepAtTimestamp(LOW_TS2 + 5);
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS2);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS2);
        verify(spiedKvs, times(1)).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(false));
        assertProgressUpdatedToTimestamp(LOW_TS2 + 5 - 1);

        runConservativeSweepAtTimestamp(LOW_TS2 - 5);
        verify(spiedKvs, times(1)).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(false));
        assertProgressUpdatedToTimestamp(LOW_TS2 + 5 - 1);
    }

    @Test
    public void canSweepAtMinimumTimeWithNoWrites() {
        runConservativeSweepAtTimestamp(Long.MIN_VALUE);
        assertProgressUpdatedToTimestamp(SweepQueueUtils.INITIAL_TIMESTAMP);
    }

    @Test
    public void canSweepAtMinimumTime() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS2);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS3);

        runConservativeSweepAtTimestamp(Long.MIN_VALUE);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS);
        assertProgressUpdatedToTimestamp(SweepQueueUtils.INITIAL_TIMESTAMP);
    }

    @Test
    public void canSweepAtMaximumTime() {
        enqueueWriteCommitted(TABLE_CONS, LOW_TS);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS2);
        enqueueWriteCommitted(TABLE_CONS, LOW_TS3);

        runConservativeSweepAtTimestamp(Long.MAX_VALUE);
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, LOW_TS3);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, LOW_TS3);
    }

    @Test
    public void doesNotGoBackwardsEvenIfSweepTimestampRegressesAcrossBoundary() {
        long coarseBoundary = TS_COARSE_GRANULARITY;
        enqueueWriteCommitted(TABLE_CONS, coarseBoundary - 5);
        enqueueWriteCommitted(TABLE_CONS, coarseBoundary + 5);
        enqueueWriteCommitted(TABLE_CONS, coarseBoundary + 15);

        // Need 2 sweeps to get through the first coarse bucket
        runConservativeSweepAtTimestamp(coarseBoundary + 8);
        assertThat(metricsManager).hasSweepTimestampConservativeEqualTo(
                coarseBoundary + 8);
        runConservativeSweepAtTimestamp(coarseBoundary + 8);
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, coarseBoundary - 5 + 1);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, coarseBoundary + 5);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, coarseBoundary + 15);

        // Now regresses (e.g. clock drift on unreadable)
        runConservativeSweepAtTimestamp(coarseBoundary - 3);
        assertThat(metricsManager).hasSweepTimestampConservativeEqualTo(
                coarseBoundary - 3);

        // And advances again
        runConservativeSweepAtTimestamp(coarseBoundary + 18);
        assertThat(metricsManager).hasSweepTimestampConservativeEqualTo(
                coarseBoundary + 18);
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, coarseBoundary - 5 + 1);
        assertReadAtTimestampReturnsSentinel(TABLE_CONS, coarseBoundary + 5 + 1);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, coarseBoundary + 15);
    }

    @Test
    public void testSweepTimestampMetric() {
        unreadableTs = 17;
        immutableTs = 40;
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(0));
        sweepQueue.sweepNextBatch(ShardAndStrategy.thorough(0));

        assertThat(metricsManager).hasSweepTimestampConservativeEqualTo(17L);
        assertThat(metricsManager).hasSweepTimestampThoroughEqualTo(40);

        immutableTs = 5;
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(0));
        sweepQueue.sweepNextBatch(ShardAndStrategy.thorough(0));

        assertThat(metricsManager).hasSweepTimestampConservativeEqualTo(5L);
        assertThat(metricsManager).hasSweepTimestampThoroughEqualTo(5);
    }

    @Test
    public void doNotSweepAnythingAfterEntryWithCommitTsAfterSweepTs() {
        immutableTs = 1000L;
        // put 4 writes committed at the same timestamp as start timestamp, and put one committed at sweep timestamp
        enqueueWriteCommitted(TABLE_CONS, 900);
        enqueueWriteCommitted(TABLE_CONS, 910);
        enqueueWriteCommitted(TABLE_CONS, 920);
        enqueueWriteCommitedAt(TABLE_CONS, 950, immutableTs);
        enqueueWriteCommitted(TABLE_CONS, 970);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(CONS_SHARD))).isEqualTo(920L);
        ArgumentCaptor<Map> argument = ArgumentCaptor.forClass(Map.class);
        verify(spiedKvs, times(1)).deleteAllTimestamps(eq(TABLE_CONS), argument.capture(), eq(false));
        assertThat(argument.getValue()).containsValue(920L);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(CONS_SHARD))).isEqualTo(920L);
        verify(spiedKvs, times(1)).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(false));

        immutableTs = 1001L;
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(CONS_SHARD))).isEqualTo(1001L - 1L);
        // we have now had a total of 2 calls to deleteAllTimestamps, 1 from before and one new
        verify(spiedKvs, times(2)).deleteAllTimestamps(eq(TABLE_CONS), argument.capture(), eq(false));
        assertThat(argument.getValue()).containsValue(970L);
    }

    @Test
    public void doNotDeleteAnythingAfterEntryWithCommitTsAfterSweepTs() {
        immutableTs = 1000L;
        enqueueWriteUncommitted(TABLE_CONS, 900);
        enqueueWriteUncommitted(TABLE_CONS, 920);
        enqueueWriteCommitedAt(TABLE_CONS, 950, 2000);
        enqueueWriteUncommitted(TABLE_CONS, 970);
        enqueueWriteUncommitted(TABLE_CONS, 1110);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(CONS_SHARD))).isEqualTo(920L);
        verify(spiedKvs, never()).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(false));

        ArgumentCaptor<Multimap> multimap = ArgumentCaptor.forClass(Multimap.class);
        verify(spiedKvs, times(1)).delete(eq(TABLE_CONS), multimap.capture());
        assertThat(multimap.getValue().keySet()).containsExactly(DEFAULT_CELL);
        assertThat(multimap.getValue().values()).containsExactly(900L, 920L);

        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(CONS_SHARD))).isEqualTo(920L);
        verify(spiedKvs, never()).deleteAllTimestamps(any(TableReference.class), anyMap(), eq(false));
        verify(spiedKvs, times(1)).delete(any(TableReference.class), any(Multimap.class));
        assertReadAtTimestampReturnsValue(TABLE_CONS, 1500L, 1110L);

        immutableTs = 2009L;
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(CONS_SHARD))).isEqualTo(2009L - 1L);
        ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
        verify(spiedKvs, times(1)).deleteAllTimestamps(eq(TABLE_CONS), map.capture(), eq(false));
        assertThat(map.getValue()).containsValue(950L);

        assertReadAtTimestampReturnsValue(TABLE_CONS, 1500L, 950L);
    }

    @Test
    public void stopReadingEarlyWhenEncounteringEntryKnownToBeCommittedAfterSweepTs() {
        immutableTs = 100L;

        enqueueWriteCommitted(TABLE_CONS, 10);
        enqueueWriteCommitedAt(TABLE_CONS, 30, 150);

        putTimestampIntoTransactionTable(50, 200);
        Map<Integer, Integer> largeWriteDistribution = enqueueAtLeastThresholdWritesInDefaultShardWithStartTs(100, 50);
        int writesInDedicated = largeWriteDistribution.get(CONS_SHARD);

        enqueueWriteUncommitted(TABLE_CONS, 70);
        enqueueWriteCommitted(TABLE_CONS, 90);

        // first iteration reads all before giving up
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(metricsManager).hasEntriesReadConservativeEqualTo(4 + writesInDedicated);

        // we read one entry and give up
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(metricsManager).hasEntriesReadConservativeEqualTo(4 + writesInDedicated + 1);

        immutableTs = 170;

        // we read one good entry and then a reference to bad entries and give up
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(metricsManager).hasEntriesReadConservativeEqualTo(4 + writesInDedicated + 3);

        immutableTs = 250;

        // we now read all to the end
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(metricsManager).hasEntriesReadConservativeEqualTo(4 + writesInDedicated + 3 + writesInDedicated + 2);
    }

    @Test
    public void stopReadingEarlyInOtherShardWhenEncounteringEntryKnownToBeCommittedAfterSweepTs() {
        immutableTs = 100L;

        putTimestampIntoTransactionTable(50, 200);
        Map<Integer, Integer> largeWriteDistribution = enqueueAtLeastThresholdWritesInDefaultShardWithStartTs(100, 50);
        int writesInDedicated = largeWriteDistribution.get(CONS_SHARD);
        int otherShard = IntMath.mod(CONS_SHARD + 1, DEFAULT_SHARDS);
        int writesInOther = largeWriteDistribution.get(otherShard);

        assertThat(writesInOther).isGreaterThan(0);

        // first iteration reads all before giving up
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD));
        assertThat(metricsManager).hasEntriesReadConservativeEqualTo(writesInDedicated);

        // we read a reference to bad entries and give up
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(otherShard));
        assertThat(metricsManager).hasEntriesReadConservativeEqualTo(writesInDedicated + 1);

        immutableTs = 250;

        // we now read all to the end
        sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(otherShard));
        assertThat(metricsManager).hasEntriesReadConservativeEqualTo(writesInDedicated + 1 + writesInOther);
    }

    @Test
    public void batchIncludesAllWritesWithTheSameTimestampAndDoesNotSkipOrRepeatAnyWritesInNextIteration() {
        TargetedSweeper sweeperConservative = getSingleShardSweeper();

        int relativePrime = MAX_CELLS_GENERIC - 1;
        // this assertion verifies that the test checks what we want. If it fails, change the value of relativePrime
        assertThat(SWEEP_BATCH_SIZE % relativePrime).isNotEqualTo(0);

        int minTsToReachBatchSize = (SWEEP_BATCH_SIZE - 1) / relativePrime + 1;

        commitTransactionsWithWritesIntoUniqueCells(minTsToReachBatchSize + 1, relativePrime, sweeperConservative);

        // first iteration of sweep should include all writes corresponding to timestamp 999 + minCellsToReachBatchSize,
        // since deletes are batched, we do not specify the number of calls to delete
        sweeperConservative.sweepNextBatch(ShardAndStrategy.conservative(0));
        ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
        verify(spiedKvs, atLeast(1)).deleteAllTimestamps(eq(TABLE_CONS), map.capture(), eq(false));
        assertThat(map.getAllValues().stream().map(Map::size).mapToInt(x -> x).sum())
                .isEqualTo(minTsToReachBatchSize * relativePrime);
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(0)))
                .isEqualTo(1000 + minTsToReachBatchSize - 1);

        //second iteration should only contain writes corresponding to timestamp 1000 + minCellsToReachBatchSize
        sweeperConservative.sweepNextBatch(ShardAndStrategy.conservative(0));
        verify(spiedKvs, atLeast(2)).deleteAllTimestamps(eq(TABLE_CONS), map.capture(), eq(false));
        assertThat(map.getValue().size()).isEqualTo(relativePrime);
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(0)))
                .isEqualTo(maxTsForFinePartition(0));
    }

    @Test
    public void doNotMissSingleWriteInNextIteration() {
        TargetedSweeper sweeperConservative = getSingleShardSweeper();

        int minTsToReachBatchSize = (SWEEP_BATCH_SIZE - 1) / MAX_CELLS_GENERIC + 1;

        commitTransactionsWithWritesIntoUniqueCells(minTsToReachBatchSize, MAX_CELLS_GENERIC, sweeperConservative);
        // put one additional transaction with a single write after
        putTimestampIntoTransactionTable(1000 + minTsToReachBatchSize, 1000 + minTsToReachBatchSize);
        Cell cell = Cell.create(PtBytes.toBytes(1000 + minTsToReachBatchSize), PtBytes.toBytes(0));
        sweeperConservative.enqueue(ImmutableList.of(WriteInfo.write(TABLE_CONS, cell, 1000 + minTsToReachBatchSize)));

        // first iteration of sweep should include all but one of the writes, since deletes are batched, we do not
        // specify the number of calls to delete
        sweeperConservative.sweepNextBatch(ShardAndStrategy.conservative(0));
        ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
        verify(spiedKvs, atLeast(1)).deleteAllTimestamps(eq(TABLE_CONS), map.capture(), eq(false));
        assertThat(map.getAllValues().stream().map(Map::size).mapToInt(x -> x).sum())
                .isEqualTo(SWEEP_BATCH_SIZE);
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(0)))
                .isEqualTo(1000 + minTsToReachBatchSize - 1);

        // second iteration of sweep should contain the remaining write
        sweeperConservative.sweepNextBatch(ShardAndStrategy.conservative(0));
        verify(spiedKvs, atLeast(2)).deleteAllTimestamps(eq(TABLE_CONS), map.capture(), eq(false));
        assertThat(map.getValue().size()).isEqualTo(1);
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(0)))
                .isEqualTo(maxTsForFinePartition(0));
    }

    @Test
    public void deletesGetBatched() {
        TargetedSweeper sweeperConservative = getSingleShardSweeper();

        int numberOfTimestamps = 5 * BATCH_SIZE_KVS / MAX_CELLS_GENERIC + 1;

        commitTransactionsWithWritesIntoUniqueCells(numberOfTimestamps, MAX_CELLS_GENERIC, sweeperConservative);
        sweeperConservative.sweepNextBatch(ShardAndStrategy.conservative(0));
        ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
        verify(spiedKvs, times(6)).deleteAllTimestamps(eq(TABLE_CONS), map.capture(), eq(false));
        assertThat(map.getAllValues().stream().map(Map::size).mapToInt(x -> x).sum())
                .isEqualTo(5 * BATCH_SIZE_KVS + MAX_CELLS_GENERIC);
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(0)))
                .isEqualTo(maxTsForFinePartition(0));
    }

    @Test
    public void multipleSweepersSweepDifferentShardsAndCallUnlockAfterwards() throws InterruptedException {
        int shards = 128;
        int sweepers = 8;
        int threads = shards / sweepers;
        TimelockService stickyLockService = createStickyLockService();
        createAndInitializeSweepersAndWaitForOneBackgroundIteration(sweepers, shards, threads, stickyLockService);

        for (int i = 0; i < shards; i++) {
            assertProgressUpdatedToTimestamp(maxTsForFinePartition(tsPartitionFine(unreadableTs - 1)), i);
            verify(stickyLockService, times(1)).unlock(ImmutableSet.of(LockToken.of(new UUID(i, 0L))));
        }

        // minimum: all threads on one host succeed, then on another, etc:
        // threads + threads * 2 + ...  + threads * swepers
        verify(stickyLockService, atLeast(threads * sweepers * (sweepers - 1) / 2))
                .lock(any(LockRequest.class));
        // maximum: all but one succeed on each host, and only then those succeed:
        // shards + shards - 1 + ... + shards - (sweepers - 1)
        verify(stickyLockService, atMost(sweepers * shards - sweepers * (sweepers - 1) / 2))
                .lock(any(LockRequest.class));
    }

    @Test
    public void extraSweepersGiveUpAfterFailingToAcquireEnoughTimes() throws InterruptedException {
        int shards = 16;
        int sweepers = 4;
        int threads = shards / (sweepers / 2);
        TimelockService stickyLockService = createStickyLockService();
        createAndInitializeSweepersAndWaitForOneBackgroundIteration(sweepers, shards, threads, stickyLockService);

        ArgumentCaptor<LockRequest> captor = ArgumentCaptor.forClass(LockRequest.class);
        // minimum: as in the example above, but we have extra threads
        // threads + ... + threads * (shards / threads) + shards * (threads * sweepers - shards)
        verify(stickyLockService, atLeast(shards * (shards / threads + 1) / 2 + shards * (threads * sweepers - shards)))
                .lock(captor.capture());
        // maximum: one would think that it is
        // shards + shards - 1 + ... + shards - (sweepers - 1) + shards * (threads * sweepers - shards)
        // but actually the logic is much more complicated since threads from the same sweeper can loop back and hit a
        // race condition with each other, so we go with the more conservative upper bound
        verify(stickyLockService, atMost(threads * sweepers * shards)).lock(any());
        Set<String> requestedLockIds = captor.getAllValues().stream()
                .map(LockRequest::getLockDescriptors)
                .map(Iterables::getOnlyElement)
                .map(LockDescriptor::getLockIdAsString)
                .collect(Collectors.toSet());

        Set<String> expectedLockIds = IntStream.range(0, shards).boxed()
                .map(ShardAndStrategy::conservative)
                .map(ShardAndStrategy::toText)
                .collect(Collectors.toSet());

        assertThat(requestedLockIds).hasSameElementsAs(expectedLockIds);
    }

    private void writeValuesAroundSweepTimestampAndSweepAndCheck(long sweepTimestamp, int sweepIterations) {
        enqueueWriteCommitted(TABLE_CONS, sweepTimestamp - 10);
        enqueueWriteCommitted(TABLE_CONS, sweepTimestamp - 5);
        enqueueWriteCommitted(TABLE_CONS, sweepTimestamp + 5);

        IntStream.range(0, sweepIterations)
                .forEach(unused -> sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD)));

        assertReadAtTimestampReturnsSentinel(TABLE_CONS, sweepTimestamp - 5);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, sweepTimestamp - 5);
        assertTestValueEnqueuedAtGivenTimestampStillPresent(TABLE_CONS, sweepTimestamp + 5);
    }

    private void runConservativeSweepAtTimestamp(long desiredSweepTimestamp) {
        runWithConservativeSweepTimestamp(() -> sweepQueue.sweepNextBatch(ShardAndStrategy.conservative(CONS_SHARD)),
                desiredSweepTimestamp);
    }

    private void enqueueWriteCommitted(TableReference tableRef, long ts) {
        putTimestampIntoTransactionTable(ts, ts);
        enqueueWriteUncommitted(tableRef, ts);
    }

    private void enqueueWriteCommitedAt(TableReference tableRef, long startTs, long commitTs) {
        putTimestampIntoTransactionTable(startTs, commitTs);
        enqueueWriteUncommitted(tableRef, startTs);
    }

    private void enqueueTombstone(TableReference tableRef, long ts) {
        putTimestampIntoTransactionTable(ts, ts);
        sweepQueue.enqueue(tombstoneToDefaultCell(tableRef, ts), ts);
    }

    private void enqueueWriteUncommitted(TableReference tableRef, long startTs) {
        sweepQueue.enqueue(writeToDefaultCell(tableRef, startTs), startTs);
    }

    private Map<TableReference, ? extends Map<Cell, byte[]>> writeToDefaultCell(TableReference tableRef, long ts) {
        return writeToCell(tableRef, ts, DEFAULT_CELL);
    }

    private Map<TableReference, ? extends Map<Cell, byte[]>> writeToCell(TableReference tableRef, long ts, Cell cell) {
        Map<Cell, byte[]> singleWrite = ImmutableMap.of(cell, PtBytes.toBytes(ts));
        spiedKvs.put(tableRef, singleWrite, ts);
        return ImmutableMap.of(tableRef, singleWrite);
    }

    private Map<TableReference, ? extends Map<Cell, byte[]>> tombstoneToDefaultCell(TableReference tableRef, long ts) {
        Map<Cell, byte[]> singleWrite = ImmutableMap.of(DEFAULT_CELL, PtBytes.EMPTY_BYTE_ARRAY);
        spiedKvs.put(tableRef, singleWrite, ts);
        return ImmutableMap.of(tableRef, singleWrite);
    }

    private void assertReadAtTimestampReturnsValue(TableReference tableRef, long readTs, long value) {
        assertThat(readValueFromDefaultCell(tableRef, readTs)).isEqualTo(value);
    }

    private void assertTestValueEnqueuedAtGivenTimestampStillPresent(TableReference tableRef, long timestamp) {
        assertReadAtTimestampReturnsValue(tableRef, timestamp + 1, timestamp);
    }

    private long readValueFromDefaultCell(TableReference tableRef, long ts) {
        byte[] cellContents = Iterables.getOnlyElement(readFromDefaultCell(tableRef, ts).values()).getContents();
        assertThat(cellContents).as("Reading value from table %s at timestamp %s", tableRef, ts).isNotEmpty();
        return PtBytes.toLong(cellContents);
    }

    private void assertReadAtTimestampReturnsSentinel(TableReference tableRef, long readTs) {
        assertReadAtTimestampReturnsTombstoneAtTimestamp(tableRef, readTs, -1L);
    }

    private void assertReadAtTimestampReturnsTombstoneAtTimestamp(TableReference tableRef, long readTs, long tombTs) {
        Value readValue = Iterables.getOnlyElement(readFromDefaultCell(tableRef, readTs).values());
        assertThat(readValue.getTimestamp()).isEqualTo(tombTs);
        assertThat(readValue.getContents()).isEmpty();
    }

    private void assertReadAtTimestampReturnsNothing(TableReference tableRef, long readTs) {
        assertThat(readFromDefaultCell(tableRef, readTs)).isEmpty();
    }

    // this implicitly assumes the entry was not committed after the timestamp
    private void assertSweepableCellsHasEntryForTimestamp(long timestamp) {
        SweepBatch batch = sweepableCells.getBatchForPartition(
                ShardAndStrategy.conservative(CONS_SHARD), tsPartitionFine(timestamp), -1L, timestamp + 1);
        assertThat(batch.writes()).containsExactly(WriteInfo.write(TABLE_CONS, DEFAULT_CELL, timestamp));
    }

    private void assertSweepableCellsHasNoEntriesInPartitionOfTimestamp(long timestamp) {
        SweepBatch batch = sweepableCells.getBatchForPartition(
                ShardAndStrategy.conservative(CONS_SHARD), tsPartitionFine(timestamp), -1L, Long.MAX_VALUE);
        assertThat(batch.writes()).isEmpty();
    }

    private Map<Cell, Value> readFromDefaultCell(TableReference tableRef, long ts) {
        Map<Cell, Long> singleRead = ImmutableMap.of(DEFAULT_CELL, ts);
        return spiedKvs.get(tableRef, singleRead);
    }

    private void assertProgressUpdatedToTimestamp(long ts) {
        assertProgressUpdatedToTimestamp(ts, CONS_SHARD);
    }

    private void assertProgressUpdatedToTimestamp(long ts, int shard) {
        assertThat(progress.getLastSweptTimestamp(ShardAndStrategy.conservative(shard))).isEqualTo(ts);
    }


    private void assertLowestFinePartitionInSweepableTimestampsEquals(long partitionFine) {
        assertThat(sweepableTimestamps
                .nextSweepableTimestampPartition(ShardAndStrategy.conservative(CONS_SHARD), -1L, getSweepTsCons()))
                .contains(partitionFine);
    }

    private void assertNoEntriesInSweepableTimestampsBeforeSweepTimestamp() {
        assertThat(sweepableTimestamps
                .nextSweepableTimestampPartition(ShardAndStrategy.conservative(CONS_SHARD), -1L, getSweepTsCons()))
                .isEmpty();
    }

    private void setTimelockTime(long timeMillis) {
        when(timelockService.currentTimeMillis()).thenReturn(timeMillis);
    }

    private void punchTimeAtTimestamp(long timeMillis, long timestamp) {
        puncherStore.put(timestamp, timeMillis);
    }

    private void commitTransactionsWithWritesIntoUniqueCells(int transactions, int writes, TargetedSweeper sweeper) {
        for (int i = 1000; i < 1000 + transactions; i++) {
            putTimestampIntoTransactionTable(i, i);
            List<WriteInfo> writeInfos = new ArrayList<>();
            for (int j = 0; j < writes; j++) {
                Cell cell = Cell.create(PtBytes.toBytes(i), PtBytes.toBytes(j));
                writeInfos.add(WriteInfo.write(TABLE_CONS, cell, i));
            }
            sweeper.enqueue(writeInfos);
        }
    }

    private TargetedSweeper getSingleShardSweeper() {
        TargetedSweeper sweeper = TargetedSweeper.createUninitializedForTest(() -> 1);
        sweeper.initializeWithoutRunning(
                timestampsSupplier, mock(TimelockService.class), spiedKvs, mock(TargetedSweepFollower.class));
        return sweeper;
    }

    /**
     * Creates a mock of a LockService that only gives out a lock once per unique request and never releases it, even
     * if unlock is called. The returned tokens are monotonically increasing in the tokenId.
     */
    private TimelockService createStickyLockService() {
        AtomicLong lockToken = new AtomicLong(0);
        Set<LockDescriptor> requestedLocks = new ConcurrentHashSet<>();
        TimelockService stickyLockService = mock(TimelockService.class);
        doAnswer(invocation -> {
            LockRequest request = invocation.getArgument(0);
            if (requestedLocks.add(Iterables.getOnlyElement(request.getLockDescriptors()))) {
                return (LockResponse) () -> Optional.of(LockToken.of(new UUID(lockToken.getAndIncrement(), 0L)));
            } else {
                return (LockResponse) Optional::empty;
            }
        }).when(stickyLockService).lock(any());
        return stickyLockService;
    }

    private void createAndInitializeSweepersAndWaitForOneBackgroundIteration(int sweepers, int shards, int threads,
            TimelockService stickyLockService) throws InterruptedException {
        for (int i = 0; i < sweepers; i++) {
            TargetedSweeper sweeperInstance = TargetedSweeper
                    .createUninitialized(metricsManager, () -> true, () -> shards, threads, 0, ImmutableList.of());
            sweeperInstance.initializeWithoutRunning(timestampsSupplier, stickyLockService, spiedKvs, mockFollower);
            sweeperInstance.runInBackground();
        }
        waitUntilBackgroundSweepRunsOneIteration();
    }

    private void waitUntilBackgroundSweepRunsOneIteration() throws InterruptedException {
        Thread.sleep(3_000);
    }

    private Map<Integer, Integer> enqueueAtLeastThresholdWritesInDefaultShardWithStartTs(long threshold, long startTs) {
        List<WriteInfo> writeInfos = new ArrayList<>();
        int counter = 0;
        while (writeInfos.stream().filter(write -> write.toShard(DEFAULT_SHARDS) == CONS_SHARD).count() < threshold) {
            writeInfos.addAll(generateHundredWrites(counter++, startTs));
        }
        sweepQueue.enqueue(writeInfos);
        return writeInfos.stream()
                .collect(Collectors.toMap(write -> write.toShard(DEFAULT_SHARDS), write -> 1, (fst, snd) -> fst + snd));
    }

    private List<WriteInfo> generateHundredWrites(int startCol, long startTs) {
        List<WriteInfo> writeInfos = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            writeInfos.add(WriteInfo.write(TABLE_CONS,
                    Cell.create(DEFAULT_CELL.getRowName(), PtBytes.toBytes(startCol * 100 + i)), startTs));
        }
        return writeInfos;
    }
}
