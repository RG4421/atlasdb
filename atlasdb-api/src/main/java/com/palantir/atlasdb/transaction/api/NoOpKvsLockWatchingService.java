/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.transaction.api;

import java.util.Optional;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.palantir.lock.LockDescriptor;
import com.palantir.lock.watch.LockWatchInfo;
import com.palantir.lock.watch.LockWatchRequest;
import com.palantir.lock.watch.LockWatchStateUpdate;

public final class NoOpKvsLockWatchingService implements KvsLockWatchingService {
    public static final KvsLockWatchingService INSTANCE = new NoOpKvsLockWatchingService();

    private NoOpKvsLockWatchingService() {
        // nope
    }

    @Override
    public void registerWatches(LockWatchRequest lockWatchEntries) {
        // noop
    }

    @Override
    public Set<LockDescriptor> deregisterWatches(LockWatchRequest lockWatchEntries) {
        return ImmutableSet.of();
    }

    @Override
    public LockWatchState getLockWatchState(Optional<Long> lastKnownState) {
        return x -> LockWatchInfo.INVALID;
    }
}
