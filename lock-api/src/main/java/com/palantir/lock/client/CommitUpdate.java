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

package com.palantir.lock.client;

import java.util.Set;

import org.immutables.value.Value;

import com.google.common.collect.ImmutableSet;
import com.palantir.lock.LockDescriptor;

@Value.Immutable
public interface CommitUpdate {
    @Value.Parameter
    long commitTs();
    @Value.Parameter
    boolean invalidateAll();
    @Value.Parameter
    Set<LockDescriptor> invalidatedLocks();

    static CommitUpdate ignoringWatches(long timestamp) {
        return ImmutableCommitUpdate.of(timestamp, false, ImmutableSet.of());
    }

    static CommitUpdate invalidateWatches(long timestamp) {
        return ImmutableCommitUpdate.of(timestamp, true, ImmutableSet.of());
    }
}
