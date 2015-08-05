/*
 * Copyright 2015 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.realm.internal;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.realm.RealmConfiguration;

/**
 * This class wraps access to a given Realm file on a single thread including its {@link SharedGroup}
 * and {@link ImplicitTransaction}. By nature this means that this class is not thread safe and
 * should only be used from the thread that created it.
 *
 * Realm files are using a MVCC scheme (Multiversion concurrency control), which means that multiple
 * versions of the data might exist in the same file. By default the file is always opened on the
 * latest version and it is possible to advance to the latest version by calling
 * {@link #advanceRead()}.
 *
 * Realm file access is a bit tricky as Realm must create a new reference to the file from each
 * thread. This means that any code that wants to manipulate the Realm file itself must use the
 * static methods in this class to ensure that it is safe to do so.
 */
public class SharedGroupManager implements Closeable {

    // Map how many times a Realm path has been opened across all threads.
    // This is only needed by deleteRealmFile.
    private static final Map<String, AtomicInteger> globalOpenInstanceCounter =
            new ConcurrentHashMap<String, AtomicInteger>();

    // Map between <threadId, <canonicalPath, manager>>. Allows reusing SharedGroupManager across
    // Realm types.
    private static final Map<Long, Map<String, SharedGroupManager>> managerCache =
            new HashMap<Long, Map<String, SharedGroupManager>>();

    protected SharedGroup sharedGroup;
    protected final ImplicitTransaction transaction;

    /**
     * Returns the {@link SharedGroupManager} for the given configuration on this thread.
     * Wrappers can be reused across multiple Realm instances. The underlying {@link SharedGroup}
     * is reference counted, so will not be fully closed until all SharedGroupManager references
     * has been closed.
     */
    public static synchronized SharedGroupManager getInstance(long threadId, RealmConfiguration configuration) {
        Map<String, SharedGroupManager> threadCache = managerCache.get(threadId);
        if (threadCache == null) {
            threadCache = new HashMap<String, SharedGroupManager>();
            managerCache.put(threadId, threadCache);
        }
        SharedGroupManager cachedManager = threadCache.get(configuration);
        if (cachedManager == null) {
            cachedManager = new SharedGroupManager(configuration);
            threadCache.put(configuration.getPath(), cachedManager);
        }
        acquireReference(configuration.getPath());
        return cachedManager;
    }

    /**
     * Creates a new instance of the FileWrapper for the given configuration on this thread.
     *
     * @throws IllegalArgumentException if the given configuration isn't compatible with existing
     * configurations.
     */
    private SharedGroupManager(RealmConfiguration configuration) {
        this.sharedGroup = new SharedGroup(
                configuration.getPath(),
                SharedGroup.IMPLICIT_TRANSACTION,
                configuration.getDurability(),
                configuration.getEncryptionKey());
        this.transaction = sharedGroup.beginImplicitTransaction();
    }

    /**
     * Close the underlying {@link SharedGroup} and free any native resources.
     */
    @Override
    public void close() {
        releaseReference(sharedGroup.getPath());
        sharedGroup.close();
        sharedGroup = null;
    }

    // Acquire a reference to a given Realm file. All access must be reference counted in order
    // to validate other lifecycle events like closing native resources or deleting the file.
    private static synchronized void acquireReference(String path) {
        AtomicInteger counter = globalOpenInstanceCounter.get(path);
        if (counter == null) {
            globalOpenInstanceCounter.put(path, new AtomicInteger(1));
        } else {
            counter.incrementAndGet();
        }
    }

    // Release a reference to a given Realm file.
    private static synchronized void releaseReference(String path) {
        AtomicInteger counter = globalOpenInstanceCounter.get(path);
        if (counter.decrementAndGet() == 0) {
            globalOpenInstanceCounter.remove(path);
        }
    }

    /**
     * Checks if a Realm file defined by the configuration is open on any thread.
     *
     * @return {@code true} if the Realm file is open on any thread, {@code false} otherwise.
     */
    public static boolean isOpen(String canonicalPath) {
        AtomicInteger counter = globalOpenInstanceCounter.get(canonicalPath);
        return (counter != null && counter.get() > 0);
    }

    /**
     * Checks if the Realm file is accessible.
     *
     * @return {@code true} if the file is open and data can be accessed, {@code false} otherwise.
     */
    public boolean isOpen() {
        return sharedGroup != null;
    }

    /**
     * Advance the Realm file to the latest version.
     */
    public void advanceRead() {
        transaction.advanceRead();
    }

    // Public because of migrations
    // TODO Remove for new Migration API
    public Table getTable(String tableName) {
        return transaction.getTable(tableName);
    }

    /**
     * Checks if a Realm file can be advanced to a newer version.
     */
    public boolean hasChanged() {
        return sharedGroup.hasChanged();
    }

    /**
     * Make the file writable. This will block all other threads and processes from making it writable as well.
     */
    public void promoteToWrite() {
        transaction.promoteToWrite();
    }

    /**
     * Commit any pending changes to the file and return to read-only mode.
     */
    public void commitAndContinueAsRead() {
        transaction.commitAndContinueAsRead();
    }

    /**
     * Rollback any changes to the file since it was made writable and continue in read-only mode.
     */
    public void rollbackAndContinueAsRead() {
        transaction.rollbackAndContinueAsRead();
    }

    /**
     * Checks if a given table exists.
     * @return {code true} if the table exists. {@code false} otherwise.
     */
    public boolean hasTable(String tableName) {
        return transaction.hasTable(tableName);
    }

    /**
     * Writes a copy of this Realm file to another location.
     */
    public void copyToFile(File destination, byte[] key) throws IOException {
        transaction.writeToFile(destination, key);
    }

    /**
     * Compacts a Realm file. It cannot be open when calling this method.
     */
    public static boolean compact(RealmConfiguration configuration) {
        SharedGroup sharedGroup = null;
        boolean result = false;
        try {
            sharedGroup = new SharedGroup(
                    configuration.getPath(),
                    SharedGroup.EXPLICIT_TRANSACTION,
                    SharedGroup.Durability.FULL,
                    configuration.getEncryptionKey(
            ));
            result = sharedGroup.compact();
        } finally {
            if (sharedGroup != null) {
                sharedGroup.close();
            }
        }
        return result;
    }

}