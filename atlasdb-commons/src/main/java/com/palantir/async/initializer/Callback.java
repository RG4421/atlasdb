/*
 * Copyright 2018 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.async.initializer;

import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.palantir.common.base.Throwables;

/**
 * A Callback is a potentially retryable operation on a resource R. The intended use is to specify a task to be run on
 * an uninitialized resource R once it becomes ready by calling {@link #runWithRetry(R)}.
 *
 * The desired task is specified by implementing {@link #init(R)}. The default behaviour on failure is to not retry and
 * perform no cleanup. If cleanup is necessary, the user should override the {@link #cleanup(R, Throwable)} method. If
 * the call to cleanup does not throw, {@link #init(R)} will be immediately retried.
 *
 * In case {@link #runWithRetry(R)} needs to be interrupted in a safe way, the user should call
 * {@link #blockUntilSafeToShutdown()}, which will block until any in-progress call to init() terminates, and potential
 * cleanup is finished, then prevent further retrying.
 */
public abstract class Callback<R> {
    private volatile boolean shutdownSignal = false;
    private Lock lock = new ReentrantLock();

    /**
     * The method to be executed. If init() returns, the callback is considered to be successful.
     */
    public abstract void init(R resource);

    /**
     * Cleanup to be done if init() throws, before init() can be attempted again. If this method throws, runWithRetry()
     * will fail and init() will not be retried. The default implementation assumes that init() throwing is terminal
     * and there is no cleanup necessary. This should be overridden by specifying any cleanup steps necessary, and the
     * method must return instead of throwing if init() can be retried
     *
     * @param initException Throwable thrown by init()
     */
    public void cleanup(R resource, Throwable initException) {
        throw Throwables.rewrapAndThrowUncheckedException(initException);
    }

    /**
     * Keep retrying init(), performing any necessary cleanup, until it succeeds unless cleanup() throws or a shutdown
     * signal has been sent.
     */
    public void runWithRetry(R resource) {
        while (!shutdownSignal) {
            try {
                lock.lock();
                if (!shutdownSignal) {
                    init(resource);
                }
                return;
            } catch (Throwable e) {
                cleanup(resource, e);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Try init() once only, performing any necessary cleanup
     */
    public boolean runOnceOnly(R resource) {
        try {
            lock.lock();
            if (!shutdownSignal) {
                init(resource);
                return true;
            }
        } catch (Throwable e) {
            cleanup(resource, e);
        } finally {
            lock.unlock();
        }
        return false;
    }

    /**
     * Send a shutdown signal and block until potential cleanup has finished running.
     */
    public void blockUntilSafeToShutdown() {
        shutdownSignal = true;
        lock.lock();
        lock.unlock();
    }

    /**
     * Factory method for a callback that does nothing.
     */
    public static <R> Callback<R> noOp() {
        return LambdaCallback.of(ignored -> { });
    }

    /**
     * A CallChain executes a list of callbacks in sequence.
     *
     * Note that a given callback is executed only once all previous callbacks in the chain have successfully executed.
     * Also, callbacks are retried independently. In other words, given a CallChain of two callbacks C1 and C2:
     *
     * <ul>
     *     <li>
     *         C1 is executed first. If it fails, C1's cleanup will be invoked, and then C1 will be retried.
     *     </li>
     *     <li>
     *         C2 is then executed. If it fails, C2's cleanup will be invoked and then C2 will be retried.
     *         (Note that we will not re-invoke C1's cleanup, nor will we execute it again.)
     *         Once C2 succeeds, the CallChain as a whole will be treated as complete.
     *     </li>
     * </ul>
     *
     * Please see the examples in CallChainTest for more detail.
     */
    public static class CallChain<T> extends Callback<T> {
        private final List<Callback<T>> callbacks;

        public CallChain(List<Callback<T>> callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public void init(T resource) {
            callbacks.forEach(callback -> callback.runWithRetry(resource));
        }

        @Override
        public void cleanup(T resource, Throwable cleanupException) {
            // Rethrows, because each callback's runWithRetry is responsible for cleanup of any resources needed
            // to be cleaned up for that task.
            throw Throwables.rewrapAndThrowUncheckedException(cleanupException);
        }
    }
}
