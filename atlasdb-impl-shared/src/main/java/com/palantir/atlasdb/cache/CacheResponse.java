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

package com.palantir.atlasdb.cache;

import java.util.Optional;

import org.immutables.value.Value;

@Value.Immutable
public interface CacheResponse<T> {
    CacheValidityState validity();
    Optional<T> value();

    @Value.Derived
    default T presentValue() {
        return value().orElseThrow(() -> new RuntimeException("Expected value to be present, but it wasn't there"));
    }

    enum CacheValidityState {
        INVALID,
        VALID_BUT_NOT_APPLICABLE,
        VALID,
        UNWATCHED
    }
}
