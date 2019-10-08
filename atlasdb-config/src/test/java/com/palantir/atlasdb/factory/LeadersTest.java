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
package com.palantir.atlasdb.factory;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.palantir.atlasdb.http.UserAgents;
import com.palantir.atlasdb.util.TestSslUtils;
import com.palantir.paxos.PaxosAcceptor;
import com.palantir.paxos.PaxosLearner;
import com.palantir.paxos.PaxosValue;

public class LeadersTest {

    public static final Set<String> REMOTE_SERVICE_ADDRESSES = ImmutableSet.of("http://foo:1234", "http://bar:5678");

    @Test
    public void canCreateProxyAndLocalListOfPaxosLearners() {
        PaxosLearner localLearner = mock(PaxosLearner.class);
        Optional<PaxosValue> value = Optional.of(mock(PaxosValue.class));
        when(localLearner.safeGetGreatestLearnedValue()).thenReturn(value);

        List<PaxosLearner> paxosLearners = Leaders.createProxyAndLocalList(
                new MetricRegistry(),
                localLearner,
                REMOTE_SERVICE_ADDRESSES,
                TestSslUtils.TRUST_CONTEXT,
                PaxosLearner.class,
                UserAgents.DEFAULT_USER_AGENT);

        MatcherAssert.assertThat(paxosLearners.size(), is(REMOTE_SERVICE_ADDRESSES.size() + 1));
        paxosLearners.forEach(object -> MatcherAssert.assertThat(object, not(nullValue())));
        MatcherAssert.assertThat(Iterables.getLast(paxosLearners).safeGetGreatestLearnedValue(), is(value));
        verify(localLearner).safeGetGreatestLearnedValue();
        verifyNoMoreInteractions(localLearner);
    }

    @Test
    public void canCreateProxyAndLocalListOfPaxosAcceptors() {
        PaxosAcceptor localAcceptor = mock(PaxosAcceptor.class);
        when(localAcceptor.getLatestSequencePreparedOrAccepted()).thenReturn(1L);

        List<PaxosAcceptor> paxosAcceptors = Leaders.createProxyAndLocalList(
                new MetricRegistry(),
                localAcceptor,
                REMOTE_SERVICE_ADDRESSES,
                TestSslUtils.TRUST_CONTEXT,
                PaxosAcceptor.class,
                UserAgents.DEFAULT_USER_AGENT);

        MatcherAssert.assertThat(paxosAcceptors.size(), is(REMOTE_SERVICE_ADDRESSES.size() + 1));
        paxosAcceptors.forEach(object -> MatcherAssert.assertThat(object, not(nullValue())));

        MatcherAssert.assertThat(Iterables.getLast(paxosAcceptors).getLatestSequencePreparedOrAccepted(), is(1L));
        verify(localAcceptor).getLatestSequencePreparedOrAccepted();
        verifyNoMoreInteractions(localAcceptor);
    }

    @Test
    public void createProxyAndLocalListCreatesSingletonListIfNoRemoteAddressesProvided() {
        PaxosAcceptor localAcceptor = mock(PaxosAcceptor.class);
        when(localAcceptor.getLatestSequencePreparedOrAccepted()).thenReturn(1L);

        List<PaxosAcceptor> paxosAcceptors = Leaders.createProxyAndLocalList(
                new MetricRegistry(),
                localAcceptor,
                ImmutableSet.of(),
                TestSslUtils.TRUST_CONTEXT,
                PaxosAcceptor.class,
                UserAgents.DEFAULT_USER_AGENT);

        MatcherAssert.assertThat(paxosAcceptors.size(), is(1));

        MatcherAssert.assertThat(Iterables.getLast(paxosAcceptors).getLatestSequencePreparedOrAccepted(), is(1L));
        verify(localAcceptor).getLatestSequencePreparedOrAccepted();
        verifyNoMoreInteractions(localAcceptor);
    }

    @Test(expected = IllegalStateException.class)
    public void createProxyAndLocalListThrowsIfCreatingObjectsWithoutHttpMethodAnnotatedMethods() {
        BigInteger localBigInteger = new BigInteger("0");

        Leaders.createProxyAndLocalList(
                new MetricRegistry(),
                localBigInteger,
                REMOTE_SERVICE_ADDRESSES,
                TestSslUtils.TRUST_CONTEXT,
                BigInteger.class,
                UserAgents.DEFAULT_USER_AGENT);
    }

    @Test(expected = NullPointerException.class)
    public void createProxyAndLocalListThrowsIfNullClassProvided() {
        PaxosAcceptor localAcceptor = mock(PaxosAcceptor.class);

        Leaders.createProxyAndLocalList(
                new MetricRegistry(),
                localAcceptor,
                REMOTE_SERVICE_ADDRESSES,
                TestSslUtils.TRUST_CONTEXT,
                null,
                UserAgents.DEFAULT_USER_AGENT);
    }
}
