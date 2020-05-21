/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.lock.watch;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.ImmutableSet;
import com.palantir.lock.v2.LockToken;

@SuppressWarnings("FinalClass") // mocks
public class NoOpLockWatchEventCache implements LockWatchEventCache {
    public static final LockWatchEventCache INSTANCE = new NoOpLockWatchEventCache();
    private static final IdentifiedVersion FAKE = ImmutableIdentifiedVersion.of(UUID.randomUUID(), 0L);
    private static final Optional<IdentifiedVersion> FAKE_OPTIONAL_VERSION = Optional.of(FAKE);
    private static final TransactionsLockWatchEvents NONE = TransactionsLockWatchEvents.failure(
            LockWatchStateUpdate.snapshot(UUID.randomUUID(), -1L, ImmutableSet.of(), ImmutableSet.of()));

    private NoOpLockWatchEventCache() {
        // singleton
    }

    @Override
    public Optional<IdentifiedVersion> lastKnownVersion() {
        return FAKE_OPTIONAL_VERSION;
    }

    @Override
    public IdentifiedVersion processStartTransactionsUpdate(Set<Long> startTimestamps, LockWatchStateUpdate update) {
        return FAKE;
    }

    @Override
    public void processGetCommitTimestampsUpdate(Collection<Long> commitTimestamps, LockWatchStateUpdate update) {
        // noop
    }

    @Override
    public CommitUpdate getCommitUpdate(long startTs, long commitTs, LockToken ignore) {
        return CommitUpdate.invalidateWatches(commitTs);
    }

    @Override
    public TransactionsLockWatchEvents getEventsForTransactions(Set<Long> startTimestamps,
            Optional<IdentifiedVersion> version) {
        return NONE;
    }
}
