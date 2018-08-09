/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */

package com.orientechnologies.orient.core.storage.index.sbtreebonsai.local.v2;

import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.concur.lock.OLockManager;
import com.orientechnologies.common.concur.lock.OPartitionedLockManager;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.common.types.OModifiableInteger;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OSBTreeBonsaiLocalException;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.component.sbtreebonsai.OCreateSBTreeBonsaiOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.component.sbtreebonsai.OSBTreeBonsaiPutOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.component.sbtreebonsai.OSBTreeBonsaiRemoveOperation;
import com.orientechnologies.orient.core.storage.index.sbtree.local.OSBTree;
import com.orientechnologies.orient.core.storage.index.sbtreebonsai.local.OBonsaiBucketPointer;
import com.orientechnologies.orient.core.storage.index.sbtreebonsai.local.OSBTreeBonsaiAbstract;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.Change;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.OBonsaiCollectionPointer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.locks.Lock;

/**
 * Tree-based dictionary algorithm. Similar to {@link OSBTree} but uses subpages of disk cache that is more efficient for small data
 * structures.
 * Oriented for usage of several instances inside of one file.
 * Creation of several instances that represent the same collection is not allowed.
 *
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @author Artem Orobets
 * @see OSBTree
 * @since 1.6.0
 */
public final class OSBTreeBonsaiLocalV2<K, V> extends OSBTreeBonsaiAbstract<K, V> {
  public static final  int                BINARY_VERSION    = 1;
  private static final OLockManager<Long> FILE_LOCK_MANAGER = new OPartitionedLockManager<>();

  private static final int                  PAGE_SIZE  = OGlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024;
  static final         OBonsaiBucketPointer SYS_BUCKET = new OBonsaiBucketPointer(0, 0, BINARY_VERSION);

  private volatile OBonsaiBucketPointer rootBucketPointer;

  private final Comparator<? super K> comparator = ODefaultComparator.INSTANCE;

  private volatile Long fileId = -1L;

  private OBinarySerializer<K> keySerializer;
  private OBinarySerializer<V> valueSerializer;

  private final int maxBucketSizeInBytes;

  public OSBTreeBonsaiLocalV2(String name, String dataFileExtension, OAbstractPaginatedStorage storage, int maxBucketSizeInBytes) {
    super(name, dataFileExtension, storage);
    this.maxBucketSizeInBytes = maxBucketSizeInBytes;
  }

  public void create(OBinarySerializer<K> keySerializer, OBinarySerializer<V> valueSerializer) {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(false);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException("Error during sbtree creation", this), e);
    }

    Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(-1L);
    try {
      this.keySerializer = keySerializer;
      this.valueSerializer = valueSerializer;

      if (isFileExists(getFullName()))
        this.fileId = openFile(getFullName());
      else
        this.fileId = addFile(getFullName());

      initAfterCreate(atomicOperation);

      logComponentOperation(atomicOperation,
          new OCreateSBTreeBonsaiOperation(atomicOperation.getOperationUnitId(), fileId, rootBucketPointer, getName()));
      endAtomicOperation(false, null);
    } catch (Exception e) {
      rollback(e);
      throw OException.wrapException(new OSBTreeBonsaiLocalException("Error creation of sbtree with name" + getName(), this), e);
    } finally {
      lock.unlock();
    }
  }

  private void initAfterCreate(OAtomicOperation atomicOperation) throws IOException {
    initSysBucket(atomicOperation);

    final AllocationResult allocationResult = allocateBucketForWrite(atomicOperation);
    OCacheEntry rootCacheEntry = allocationResult.getCacheEntry();
    this.rootBucketPointer = allocationResult.getPointer();

    OSBTreeBonsaiBucketV2<K, V> rootBucket = null;
    try {
      rootBucket = new OSBTreeBonsaiBucketV2<>(rootCacheEntry, this.rootBucketPointer.getPageOffset(), true, keySerializer,
          valueSerializer, this, maxBucketSizeInBytes);
      rootBucket.setTreeSize(0);
    } finally {
      releasePageFromWrite(rootBucket, atomicOperation);
    }
  }

  @Override
  public long getFileId() {
    final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
    try {
      return fileId;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public OBonsaiBucketPointer getRootBucketPointer() {
    return rootBucketPointer;
  }

  @Override
  public OBonsaiCollectionPointer getCollectionPointer() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        return new OBonsaiCollectionPointer(fileId, rootBucketPointer);
      } finally {
        lock.unlock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public V get(K key) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        BucketSearchResult bucketSearchResult = findBucket(key);
        if (bucketSearchResult.itemIndex < 0)
          return null;

        OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

        OCacheEntry keyBucketCacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
        try {
          OSBTreeBonsaiBucketV2<K, V> keyBucket = new OSBTreeBonsaiBucketV2<>(keyBucketCacheEntry, bucketPointer.getPageOffset(),
              keySerializer, valueSerializer, this, maxBucketSizeInBytes);
          return keyBucket.getEntry(bucketSearchResult.itemIndex).value;
        } finally {
          releasePageFromRead(keyBucketCacheEntry);
        }
      } finally {
        lock.unlock();
      }
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during retrieving  of sbtree with name " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public boolean put(K key, V value) {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(true);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException("Error during sbtree entrie put", this), e);
    }

    final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      BucketSearchResult bucketSearchResult = findBucket(key);
      OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

      OCacheEntry keyBucketCacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);
      OSBTreeBonsaiBucketV2<K, V> keyBucket = new OSBTreeBonsaiBucketV2<>(keyBucketCacheEntry, bucketPointer.getPageOffset(),
          keySerializer, valueSerializer, this, maxBucketSizeInBytes);

      final boolean itemFound = bucketSearchResult.itemIndex >= 0;

      final int valueLength = valueSerializer.getFixedLength();
      final byte[] rawValue = new byte[valueLength];
      valueSerializer.serializeNativeObject(value, rawValue, 0);

      final int keyLength = keySerializer.getObjectSize(key);
      final byte[] rawKey = new byte[keyLength];
      keySerializer.serializeNativeObject(key, rawKey, 0);

      final OSBTreeBonsaiPutOperation putOperation;
      if (itemFound) {
        final byte[] prevRawValue = keyBucket.getRawValue(bucketSearchResult.itemIndex, keyLength);
        keyBucket.updateRawValue(bucketSearchResult.itemIndex, keyLength, rawValue, prevRawValue);

        putOperation = new OSBTreeBonsaiPutOperation(atomicOperation.getOperationUnitId(), fileId, rootBucketPointer, rawKey,
            rawValue, prevRawValue);
      } else {
        putOperation = new OSBTreeBonsaiPutOperation(atomicOperation.getOperationUnitId(), fileId, rootBucketPointer, rawKey,
            rawValue, null);

        int insertionIndex = -bucketSearchResult.itemIndex - 1;

        while (!keyBucket.addLeafEntry(insertionIndex, rawKey, rawValue)) {
          releasePageFromWrite(keyBucket, atomicOperation);

          bucketSearchResult = splitBucket(bucketSearchResult.path, insertionIndex, key, atomicOperation);
          bucketPointer = bucketSearchResult.getLastPathItem();

          insertionIndex = bucketSearchResult.itemIndex;

          keyBucketCacheEntry = loadPageForWrite(fileId, bucketSearchResult.getLastPathItem().getPageIndex(), false);

          keyBucket = new OSBTreeBonsaiBucketV2<>(keyBucketCacheEntry, bucketPointer.getPageOffset(), keySerializer,
              valueSerializer, this, maxBucketSizeInBytes);
        }
      }

      releasePageFromWrite(keyBucket, atomicOperation);

      if (!itemFound) {
        updateSize(1, atomicOperation);
      }

      logComponentOperation(atomicOperation, putOperation);
      endAtomicOperation(false, null);

      return true;
    } catch (IOException e) {
      rollback(e);
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during index update with key " + key + " and value " + value, this),
              e);
    } finally {
      lock.unlock();
    }
  }

  public void rollbackPut(byte[] rawKey, byte[] rawValue, OAtomicOperation atomicOperation) {
    K key = keySerializer.deserializeNativeObject(rawKey, 0);
    final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      BucketSearchResult bucketSearchResult = findBucket(key);
      OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

      OCacheEntry keyBucketCacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);
      OSBTreeBonsaiBucketV2<K, V> keyBucket = new OSBTreeBonsaiBucketV2<>(keyBucketCacheEntry, bucketPointer.getPageOffset(),
          keySerializer, valueSerializer, this, maxBucketSizeInBytes);

      final boolean itemFound = bucketSearchResult.itemIndex >= 0;

      final OSBTreeBonsaiPutOperation putOperation;
      if (itemFound) {
        final byte[] prevRawValue = keyBucket.getRawValue(bucketSearchResult.itemIndex, rawKey.length);
        keyBucket.updateRawValue(bucketSearchResult.itemIndex, rawKey.length, rawValue, prevRawValue);

        putOperation = new OSBTreeBonsaiPutOperation(atomicOperation.getOperationUnitId(), fileId, rootBucketPointer, rawKey,
            rawValue, prevRawValue);
      } else {
        putOperation = new OSBTreeBonsaiPutOperation(atomicOperation.getOperationUnitId(), fileId, rootBucketPointer, rawKey,
            rawValue, null);

        int insertionIndex = -bucketSearchResult.itemIndex - 1;

        while (!keyBucket.addLeafEntry(insertionIndex, rawKey, rawValue)) {
          releasePageFromWrite(keyBucket, atomicOperation);

          bucketSearchResult = splitBucket(bucketSearchResult.path, insertionIndex, key, atomicOperation);
          bucketPointer = bucketSearchResult.getLastPathItem();

          insertionIndex = bucketSearchResult.itemIndex;

          keyBucketCacheEntry = loadPageForWrite(fileId, bucketSearchResult.getLastPathItem().getPageIndex(), false);

          keyBucket = new OSBTreeBonsaiBucketV2<>(keyBucketCacheEntry, bucketPointer.getPageOffset(), keySerializer,
              valueSerializer, this, maxBucketSizeInBytes);
        }
      }

      releasePageFromWrite(keyBucket, atomicOperation);

      if (!itemFound) {
        updateSize(1, atomicOperation);
      }

      logComponentOperation(atomicOperation, putOperation);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException("Error during index update with key " + key, this), e);
    } finally {
      lock.unlock();
    }
  }

  private void rollback(Exception e) {
    try {
      endAtomicOperation(true, e);
    } catch (IOException e1) {
      OLogManager.instance().error(this, "Error during sbtree operation  rollback", e1);
    }
  }

  public void close(boolean flush) {
    Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      readCache.closeFile(fileId, flush, writeCache);
    } finally {
      lock.unlock();
    }
  }

  public void close() {
    close(true);
  }

  /**
   * Removes all entries from bonsai tree. Put all but the root page to free list for further reuse.
   */
  @Override
  public void clear() {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(true);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException("Error during sbtree entrie clear", this), e);
    }

    final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      final Queue<OBonsaiBucketPointer> subTreesToDelete = new LinkedList<>();

      OSBTreeBonsaiBucketV2<K, V> rootBucket = null;
      OCacheEntry cacheEntry = loadPageForWrite(fileId, rootBucketPointer.getPageIndex(), false);
      try {
        rootBucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, rootBucketPointer.getPageOffset(), keySerializer, valueSerializer,
            this, maxBucketSizeInBytes);

        addChildrenToQueue(subTreesToDelete, rootBucket);

        rootBucket.shrink(0);
        rootBucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, rootBucketPointer.getPageOffset(), true, keySerializer,
            valueSerializer, this, maxBucketSizeInBytes);

        rootBucket.setTreeSize(0);
      } finally {
        releasePageFromWrite(rootBucket, atomicOperation);
      }

      recycleSubTrees(subTreesToDelete, atomicOperation);

      endAtomicOperation(false, null);
    } catch (IOException e) {
      rollback(e);

      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during clear of sbtree with name " + getName(), this), e);
    } catch (RuntimeException e) {
      rollback(e);

      throw e;
    } finally {
      lock.unlock();
    }
  }

  private void addChildrenToQueue(Queue<OBonsaiBucketPointer> subTreesToDelete, OSBTreeBonsaiBucketV2<K, V> rootBucket) {
    if (!rootBucket.isLeaf()) {
      final int size = rootBucket.size();
      if (size > 0)
        subTreesToDelete.add(rootBucket.getEntry(0).leftChild);

      for (int i = 0; i < size; i++) {
        final OSBTreeBonsaiBucketV2.SBTreeEntry<K, V> entry = rootBucket.getEntry(i);
        subTreesToDelete.add(entry.rightChild);
      }
    }
  }

  private void recycleSubTrees(Queue<OBonsaiBucketPointer> subTreesToDelete, OAtomicOperation atomicOperation) throws IOException {
    OBonsaiBucketPointer head = OBonsaiBucketPointer.NULL;
    OBonsaiBucketPointer tail = subTreesToDelete.peek();

    int bucketCount = 0;
    while (!subTreesToDelete.isEmpty()) {
      final OBonsaiBucketPointer bucketPointer = subTreesToDelete.poll();
      assert bucketPointer != null;

      OSBTreeBonsaiBucketV2<K, V> bucket = null;
      OCacheEntry cacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);
      try {
        bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, bucketPointer.getPageOffset(), keySerializer, valueSerializer, this,
            maxBucketSizeInBytes);

        addChildrenToQueue(subTreesToDelete, bucket);

        bucket.setFreeListPointer(head);
        bucket.setDeleted();
        head = bucketPointer;
      } finally {
        releasePageFromWrite(bucket, atomicOperation);
      }
      bucketCount++;
    }

    if (head.isValid()) {
      OSysBucketV2 sysBucket = null;
      final OCacheEntry sysCacheEntry = loadPageForWrite(fileId, SYS_BUCKET.getPageIndex(), false);
      try {
        sysBucket = new OSysBucketV2(sysCacheEntry, maxBucketSizeInBytes);

        assert tail != null;
        attachFreeListHead(tail, sysBucket.getFreeListHead(), atomicOperation);
        sysBucket.setFreeListHead(head);
        sysBucket.setFreeListLength(sysBucket.freeListLength() + bucketCount);

      } finally {
        releasePageFromWrite(sysBucket, atomicOperation);
      }
    }
  }

  private void attachFreeListHead(OBonsaiBucketPointer bucketPointer, OBonsaiBucketPointer freeListHead,
      OAtomicOperation atomicOperation) throws IOException {
    OSBTreeBonsaiBucketV2<K, V> bucket = null;
    OCacheEntry cacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);
    try {
      bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, bucketPointer.getPageOffset(), keySerializer, valueSerializer, this,
          maxBucketSizeInBytes);

      bucket.setFreeListPointer(freeListHead);
    } finally {
      releasePageFromWrite(bucket, atomicOperation);
    }
  }

  /**
   * Deletes a whole tree. Puts all its pages to free list for further reusage.
   */
  @Override
  public void delete() {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(false);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException("Error during sbtree deletion", this), e);
    }

    final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      final Queue<OBonsaiBucketPointer> subTreesToDelete = new LinkedList<>();
      subTreesToDelete.add(rootBucketPointer);
      recycleSubTrees(subTreesToDelete, atomicOperation);

      endAtomicOperation(false, null);
    } catch (Exception e) {
      rollback(e);

      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during delete of sbtree with name " + getName(), this), e);
    } finally {
      lock.unlock();
    }
  }

  public void rollbackDelete(OAtomicOperation atomicOperation) {
    final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      final Queue<OBonsaiBucketPointer> subTreesToDelete = new LinkedList<>();
      subTreesToDelete.add(rootBucketPointer);
      recycleSubTrees(subTreesToDelete, atomicOperation);
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during delete of sbtree with name " + getName(), this), e);
    } finally {
      lock.unlock();
    }
  }

  public boolean load(OBonsaiBucketPointer rootBucketPointer) {
    Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      this.rootBucketPointer = rootBucketPointer;
      this.fileId = openFile(getFullName());

      OCacheEntry rootCacheEntry = loadPageForRead(this.fileId, this.rootBucketPointer.getPageIndex(), false);
      try {
        OSBTreeBonsaiBucketV2<K, V> rootBucket = new OSBTreeBonsaiBucketV2<>(rootCacheEntry, this.rootBucketPointer.getPageOffset(),
            keySerializer, valueSerializer, this, maxBucketSizeInBytes);
        //noinspection unchecked
        keySerializer = (OBinarySerializer<K>) storage.getComponentsFactory().binarySerializerFactory
            .getObjectSerializer(rootBucket.getKeySerializerId());
        //noinspection unchecked
        valueSerializer = (OBinarySerializer<V>) storage.getComponentsFactory().binarySerializerFactory
            .getObjectSerializer(rootBucket.getValueSerializerId());

        return !rootBucket.isDeleted();

      } finally {
        releasePageFromRead(rootCacheEntry);
      }

    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException("Exception during loading of sbtree " + fileId, this), e);
    } finally {
      lock.unlock();
    }
  }

  private void updateSize(long diffSize, OAtomicOperation atomicOperation) throws IOException {
    OCacheEntry rootCacheEntry = loadPageForWrite(fileId, rootBucketPointer.getPageIndex(), false);

    OSBTreeBonsaiBucketV2<K, V> rootBucket = null;
    try {
      rootBucket = new OSBTreeBonsaiBucketV2<>(rootCacheEntry, rootBucketPointer.getPageOffset(), keySerializer, valueSerializer,
          this, maxBucketSizeInBytes);
      rootBucket.setTreeSize(rootBucket.getTreeSize() + diffSize);
    } finally {
      releasePageFromWrite(rootBucket, atomicOperation);
    }
  }

  @Override
  public long size() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        OCacheEntry rootCacheEntry = loadPageForRead(fileId, rootBucketPointer.getPageIndex(), false);
        try {
          OSBTreeBonsaiBucketV2 rootBucket = new OSBTreeBonsaiBucketV2<>(rootCacheEntry, rootBucketPointer.getPageOffset(),
              keySerializer, valueSerializer, this, maxBucketSizeInBytes);
          return rootBucket.getTreeSize();
        } finally {
          releasePageFromRead(rootCacheEntry);
        }
      } finally {
        lock.unlock();
      }
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during retrieving of size of index " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public V remove(K key) {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(true);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException("Error during sbtree entrie removal", this), e);
    }

    Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      BucketSearchResult bucketSearchResult = findBucket(key);
      if (bucketSearchResult.itemIndex < 0) {
        endAtomicOperation(false, null);
        return null;
      }

      OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

      OCacheEntry keyBucketCacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);
      final V removed;

      OSBTreeBonsaiBucketV2<K, V> keyBucket = null;
      final byte[][] rawEntry;
      try {
        keyBucket = new OSBTreeBonsaiBucketV2<>(keyBucketCacheEntry, bucketPointer.getPageOffset(), keySerializer, valueSerializer,
            this, maxBucketSizeInBytes);

        rawEntry = keyBucket.getRawLeafEntry(bucketSearchResult.itemIndex);
        removed = valueSerializer.deserializeNativeObject(rawEntry[1], 0);

        keyBucket.remove(bucketSearchResult.itemIndex, rawEntry[0], rawEntry[1]);
      } finally {
        releasePageFromWrite(keyBucket, atomicOperation);
      }
      updateSize(-1, atomicOperation);

      logComponentOperation(atomicOperation,
          new OSBTreeBonsaiRemoveOperation(atomicOperation.getOperationUnitId(), fileId, rootBucketPointer, rawEntry[0],
              rawEntry[1]));

      endAtomicOperation(false, null);
      return removed;
    } catch (IOException e) {
      rollback(e);

      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during removing key " + key + " from sbtree " + getName(), this),
              e);
    } catch (RuntimeException e) {
      rollback(e);

      throw e;
    } finally {
      lock.unlock();
    }
  }

  public void rollbackRemove(byte[] rawKey, OAtomicOperation atomicOperation) {
    K key = keySerializer.deserializeNativeObject(rawKey, 0);
    Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      BucketSearchResult bucketSearchResult = findBucket(key);
      OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

      OCacheEntry keyBucketCacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);

      OSBTreeBonsaiBucketV2<K, V> keyBucket = null;
      final byte[][] rawEntry;
      try {
        keyBucket = new OSBTreeBonsaiBucketV2<>(keyBucketCacheEntry, bucketPointer.getPageOffset(), keySerializer, valueSerializer,
            this, maxBucketSizeInBytes);

        rawEntry = keyBucket.getRawLeafEntry(bucketSearchResult.itemIndex);

        keyBucket.remove(bucketSearchResult.itemIndex, rawEntry[0], rawEntry[1]);
      } finally {
        releasePageFromWrite(keyBucket, atomicOperation);
      }
      updateSize(-1, atomicOperation);

      logComponentOperation(atomicOperation,
          new OSBTreeBonsaiRemoveOperation(atomicOperation.getOperationUnitId(), fileId, rootBucketPointer, rawEntry[0],
              rawEntry[1]));
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during removing key " + key + " from sbtree " + getName(), this),
              e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Collection<V> getValuesMinor(K key, boolean inclusive, final int maxValuesToFetch) {
    final List<V> result = new ArrayList<>();

    loadEntriesMinor(key, inclusive, entry -> {
      result.add(entry.getValue());
      if (maxValuesToFetch > -1 && result.size() >= maxValuesToFetch)
        return false;

      return true;
    });

    return result;
  }

  @Override
  public void loadEntriesMinor(K key, boolean inclusive, RangeResultListener<K, V> listener) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        BucketSearchResult bucketSearchResult = findBucket(key);

        OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();
        int index;
        if (bucketSearchResult.itemIndex >= 0) {
          index = inclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex - 1;
        } else {
          index = -bucketSearchResult.itemIndex - 2;
        }

        boolean firstBucket = true;
        do {
          OCacheEntry cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
          try {
            OSBTreeBonsaiBucketV2<K, V> bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, bucketPointer.getPageOffset(),
                keySerializer, valueSerializer, this, maxBucketSizeInBytes);
            if (!firstBucket)
              index = bucket.size() - 1;

            for (int i = index; i >= 0; i--) {
              if (!listener.addResult(bucket.getEntry(i))) {
                return;
              }

            }

            bucketPointer = bucket.getLeftSibling();

            firstBucket = false;

          } finally {
            releasePageFromRead(cacheEntry);
          }
        } while (bucketPointer.getPageIndex() >= 0);
      } finally {
        lock.unlock();
      }
    } catch (IOException ioe) {
      throw OException.wrapException(
          new OSBTreeBonsaiLocalException("Error during fetch of minor values for key " + key + " in sbtree " + getName(), this),
          ioe);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public Collection<V> getValuesMajor(K key, boolean inclusive, final int maxValuesToFetch) {
    final List<V> result = new ArrayList<>();

    loadEntriesMajor(key, inclusive, true, entry -> {
      result.add(entry.getValue());
      if (maxValuesToFetch > -1 && result.size() >= maxValuesToFetch)
        return false;

      return true;
    });

    return result;
  }

  /**
   * Load all entries with key greater then specified key.
   *
   * @param key       defines
   * @param inclusive if true entry with given key is included
   */
  @Override
  public void loadEntriesMajor(K key, boolean inclusive, boolean ascSortOrder, RangeResultListener<K, V> listener) {
    if (!ascSortOrder)
      throw new IllegalStateException("Descending sort order is not supported.");

    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        BucketSearchResult bucketSearchResult = findBucket(key);
        OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

        int index;
        if (bucketSearchResult.itemIndex >= 0) {
          index = inclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex + 1;
        } else {
          index = -bucketSearchResult.itemIndex - 1;
        }

        do {
          final OCacheEntry cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
          try {
            OSBTreeBonsaiBucketV2<K, V> bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, bucketPointer.getPageOffset(),
                keySerializer, valueSerializer, this, maxBucketSizeInBytes);
            int bucketSize = bucket.size();
            for (int i = index; i < bucketSize; i++) {
              if (!listener.addResult(bucket.getEntry(i))) {
                return;
              }

            }

            bucketPointer = bucket.getRightSibling();
            index = 0;
          } finally {
            releasePageFromRead(cacheEntry);
          }

        } while (bucketPointer.getPageIndex() >= 0);
      } finally {
        lock.unlock();
      }
    } catch (IOException ioe) {
      throw OException.wrapException(
          new OSBTreeBonsaiLocalException("Error during fetch of major values for key " + key + " in sbtree " + getName(), this),
          ioe);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public Collection<V> getValuesBetween(K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive,
      final int maxValuesToFetch) {
    final List<V> result = new ArrayList<>();
    loadEntriesBetween(keyFrom, fromInclusive, keyTo, toInclusive, entry -> {
      result.add(entry.getValue());
      if (maxValuesToFetch > 0 && result.size() >= maxValuesToFetch)
        return false;

      return true;
    });

    return result;
  }

  @Override
  public K firstKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        LinkedList<PagePathItemUnit> path = new LinkedList<>();

        OBonsaiBucketPointer bucketPointer = rootBucketPointer;
        OCacheEntry cacheEntry = loadPageForRead(fileId, rootBucketPointer.getPageIndex(), false);
        int itemIndex = 0;
        try {
          OSBTreeBonsaiBucketV2<K, V> bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, bucketPointer.getPageOffset(), keySerializer,
              valueSerializer, this, maxBucketSizeInBytes);

          while (true) {
            if (bucket.isLeaf()) {
              if (bucket.isEmpty()) {
                if (path.isEmpty()) {
                  return null;
                } else {
                  PagePathItemUnit pagePathItemUnit = path.removeLast();

                  bucketPointer = pagePathItemUnit.bucketPointer;
                  itemIndex = pagePathItemUnit.itemIndex + 1;
                }
              } else {
                return bucket.getKey(0);
              }
            } else {
              if (bucket.isEmpty() || itemIndex > bucket.size()) {
                if (path.isEmpty()) {
                  return null;
                } else {
                  PagePathItemUnit pagePathItemUnit = path.removeLast();

                  bucketPointer = pagePathItemUnit.bucketPointer;
                  itemIndex = pagePathItemUnit.itemIndex + 1;
                }
              } else {
                path.add(new PagePathItemUnit(bucketPointer, itemIndex));

                if (itemIndex < bucket.size()) {
                  OSBTreeBonsaiBucketV2.SBTreeEntry<K, V> entry = bucket.getEntry(itemIndex);
                  bucketPointer = entry.leftChild;
                } else {
                  OSBTreeBonsaiBucketV2.SBTreeEntry<K, V> entry = bucket.getEntry(itemIndex - 1);
                  bucketPointer = entry.rightChild;
                }

                itemIndex = 0;
              }
            }

            releasePageFromRead(cacheEntry);

            cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);

            bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, bucketPointer.getPageOffset(), keySerializer, valueSerializer, this,
                maxBucketSizeInBytes);
          }
        } finally {
          releasePageFromRead(cacheEntry);
        }
      } finally {
        lock.unlock();
      }
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during finding first key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public K lastKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        LinkedList<PagePathItemUnit> path = new LinkedList<>();

        OBonsaiBucketPointer bucketPointer = rootBucketPointer;

        OCacheEntry cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
        OSBTreeBonsaiBucketV2<K, V> bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, bucketPointer.getPageOffset(), keySerializer,
            valueSerializer, this, maxBucketSizeInBytes);

        int itemIndex = bucket.size() - 1;
        try {
          while (true) {
            if (bucket.isLeaf()) {
              if (bucket.isEmpty()) {
                if (path.isEmpty()) {
                  return null;
                } else {
                  PagePathItemUnit pagePathItemUnit = path.removeLast();

                  bucketPointer = pagePathItemUnit.bucketPointer;
                  itemIndex = pagePathItemUnit.itemIndex - 1;
                }
              } else {
                return bucket.getKey(bucket.size() - 1);
              }
            } else {
              if (itemIndex < -1) {
                if (!path.isEmpty()) {
                  PagePathItemUnit pagePathItemUnit = path.removeLast();

                  bucketPointer = pagePathItemUnit.bucketPointer;
                  itemIndex = pagePathItemUnit.itemIndex - 1;
                } else
                  return null;
              } else {
                path.add(new PagePathItemUnit(bucketPointer, itemIndex));

                if (itemIndex > -1) {
                  OSBTreeBonsaiBucketV2.SBTreeEntry<K, V> entry = bucket.getEntry(itemIndex);
                  bucketPointer = entry.rightChild;
                } else {
                  OSBTreeBonsaiBucketV2.SBTreeEntry<K, V> entry = bucket.getEntry(0);
                  bucketPointer = entry.leftChild;
                }

                itemIndex = maxBucketSizeInBytes + 1;
              }
            }

            releasePageFromRead(cacheEntry);

            cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);

            bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, bucketPointer.getPageOffset(), keySerializer, valueSerializer, this,
                maxBucketSizeInBytes);
            if (itemIndex == maxBucketSizeInBytes + 1)
              itemIndex = bucket.size() - 1;
          }
        } finally {
          releasePageFromRead(cacheEntry);
        }
      } finally {
        lock.unlock();
      }
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during finding first key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public void loadEntriesBetween(K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive,
      RangeResultListener<K, V> listener) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        BucketSearchResult bucketSearchResultFrom = findBucket(keyFrom);

        OBonsaiBucketPointer bucketPointerFrom = bucketSearchResultFrom.getLastPathItem();

        int indexFrom;
        if (bucketSearchResultFrom.itemIndex >= 0) {
          indexFrom = fromInclusive ? bucketSearchResultFrom.itemIndex : bucketSearchResultFrom.itemIndex + 1;
        } else {
          indexFrom = -bucketSearchResultFrom.itemIndex - 1;
        }

        BucketSearchResult bucketSearchResultTo = findBucket(keyTo);
        OBonsaiBucketPointer bucketPointerTo = bucketSearchResultTo.getLastPathItem();

        int indexTo;
        if (bucketSearchResultTo.itemIndex >= 0) {
          indexTo = toInclusive ? bucketSearchResultTo.itemIndex : bucketSearchResultTo.itemIndex - 1;
        } else {
          indexTo = -bucketSearchResultTo.itemIndex - 2;
        }

        int startIndex = indexFrom;
        int endIndex;
        OBonsaiBucketPointer bucketPointer = bucketPointerFrom;

        resultsLoop:
        while (true) {

          final OCacheEntry cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
          try {
            OSBTreeBonsaiBucketV2<K, V> bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, bucketPointer.getPageOffset(),
                keySerializer, valueSerializer, this, maxBucketSizeInBytes);
            if (!bucketPointer.equals(bucketPointerTo))
              endIndex = bucket.size() - 1;
            else
              endIndex = indexTo;

            for (int i = startIndex; i <= endIndex; i++) {
              if (!listener.addResult(bucket.getEntry(i))) {
                break resultsLoop;
              }

            }

            if (bucketPointer.equals(bucketPointerTo))
              break;

            bucketPointer = bucket.getRightSibling();
            if (bucketPointer.getPageIndex() < 0)
              break;

          } finally {
            releasePageFromRead(cacheEntry);
          }

          startIndex = 0;
        }
      } finally {
        lock.unlock();
      }
    } catch (IOException ioe) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException(
          "Error during fetch of values between key " + keyFrom + " and key " + keyTo + " in sbtree " + getName(), this), ioe);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public void flush() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        writeCache.flush();
      } finally {
        lock.unlock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  private BucketSearchResult splitBucket(List<OBonsaiBucketPointer> path, int keyIndex, K keyToInsert,
      OAtomicOperation atomicOperation) throws IOException {
    final OBonsaiBucketPointer bucketPointer = path.get(path.size() - 1);

    OCacheEntry bucketEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);

    OSBTreeBonsaiBucketV2<K, V> bucketToSplit = null;
    try {
      bucketToSplit = new OSBTreeBonsaiBucketV2<>(bucketEntry, bucketPointer.getPageOffset(), keySerializer, valueSerializer, this,
          maxBucketSizeInBytes);

      final boolean splitLeaf = bucketToSplit.isLeaf();
      final int bucketSize = bucketToSplit.size();

      int indexToSplit = bucketSize >>> 1;
      final K separationKey = bucketToSplit.getKey(indexToSplit);
      final List<byte[]> rightEntries = new ArrayList<>(indexToSplit);

      final int startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;

      for (int i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getRawEntry(i));
      }

      if (!bucketPointer.equals(rootBucketPointer)) {
        final AllocationResult allocationResult = allocateBucketForWrite(atomicOperation);
        OCacheEntry rightBucketEntry = allocationResult.getCacheEntry();
        final OBonsaiBucketPointer rightBucketPointer = allocationResult.getPointer();

        OSBTreeBonsaiBucketV2<K, V> newRightBucket = null;
        try {
          newRightBucket = new OSBTreeBonsaiBucketV2<>(rightBucketEntry, rightBucketPointer.getPageOffset(), splitLeaf,
              keySerializer, valueSerializer, this, maxBucketSizeInBytes);
          newRightBucket.addAll(rightEntries);

          bucketToSplit.shrink(indexToSplit);

          if (splitLeaf) {
            OBonsaiBucketPointer rightSiblingBucketPointer = bucketToSplit.getRightSibling();

            newRightBucket.setRightSibling(rightSiblingBucketPointer);
            newRightBucket.setLeftSibling(bucketPointer);

            bucketToSplit.setRightSibling(rightBucketPointer);

            if (rightSiblingBucketPointer.isValid()) {
              final OCacheEntry rightSiblingBucketEntry = loadPageForWrite(fileId, rightSiblingBucketPointer.getPageIndex(), false);

              OSBTreeBonsaiBucketV2<K, V> rightSiblingBucket = new OSBTreeBonsaiBucketV2<>(rightSiblingBucketEntry,
                  rightSiblingBucketPointer.getPageOffset(), keySerializer, valueSerializer, this, maxBucketSizeInBytes);
              try {
                rightSiblingBucket.setLeftSibling(rightBucketPointer);
              } finally {
                releasePageFromWrite(rightSiblingBucket, atomicOperation);
              }
            }
          }

          OBonsaiBucketPointer parentBucketPointer = path.get(path.size() - 2);
          OCacheEntry parentCacheEntry = loadPageForWrite(fileId, parentBucketPointer.getPageIndex(), false);

          OSBTreeBonsaiBucketV2<K, V> parentBucket = null;
          try {
            parentBucket = new OSBTreeBonsaiBucketV2<>(parentCacheEntry, parentBucketPointer.getPageOffset(), keySerializer,
                valueSerializer, this, maxBucketSizeInBytes);
            OSBTreeBonsaiBucketV2.SBTreeEntry<K, V> parentEntry = new OSBTreeBonsaiBucketV2.SBTreeEntry<>(bucketPointer,
                rightBucketPointer, separationKey, null);

            int insertionIndex = parentBucket.find(separationKey);
            assert insertionIndex < 0;

            insertionIndex = -insertionIndex - 1;
            while (!parentBucket.addEntry(insertionIndex, parentEntry, true)) {
              releasePageFromWrite(parentBucket, atomicOperation);

              BucketSearchResult bucketSearchResult = splitBucket(path.subList(0, path.size() - 1), insertionIndex, separationKey,
                  atomicOperation);

              parentBucketPointer = bucketSearchResult.getLastPathItem();
              parentCacheEntry = loadPageForWrite(fileId, parentBucketPointer.getPageIndex(), false);

              insertionIndex = bucketSearchResult.itemIndex;

              parentBucket = new OSBTreeBonsaiBucketV2<>(parentCacheEntry, parentBucketPointer.getPageOffset(), keySerializer,
                  valueSerializer, this, maxBucketSizeInBytes);
            }

          } finally {
            releasePageFromWrite(parentBucket, atomicOperation);
          }

        } finally {
          releasePageFromWrite(newRightBucket, atomicOperation);
        }

        ArrayList<OBonsaiBucketPointer> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));

        if (comparator.compare(keyToInsert, separationKey) < 0) {
          resultPath.add(bucketPointer);
          return new BucketSearchResult(keyIndex, resultPath);
        }

        resultPath.add(rightBucketPointer);
        if (splitLeaf) {
          return new BucketSearchResult(keyIndex - indexToSplit, resultPath);
        }
        return new BucketSearchResult(keyIndex - indexToSplit - 1, resultPath);

      } else {
        long treeSize = bucketToSplit.getTreeSize();

        final List<byte[]> leftEntries = new ArrayList<>(indexToSplit);

        for (int i = 0; i < indexToSplit; i++) {
          leftEntries.add(bucketToSplit.getRawEntry(i));
        }

        final AllocationResult leftAllocationResult = allocateBucketForWrite(atomicOperation);
        OCacheEntry leftBucketEntry = leftAllocationResult.getCacheEntry();
        OBonsaiBucketPointer leftBucketPointer = leftAllocationResult.getPointer();

        final AllocationResult rightAllocationResult = allocateBucketForWrite(atomicOperation);
        OCacheEntry rightBucketEntry = rightAllocationResult.getCacheEntry();
        OBonsaiBucketPointer rightBucketPointer = rightAllocationResult.getPointer();
        OSBTreeBonsaiBucketV2<K, V> newLeftBucket = null;
        try {
          newLeftBucket = new OSBTreeBonsaiBucketV2<>(leftBucketEntry, leftBucketPointer.getPageOffset(), splitLeaf, keySerializer,
              valueSerializer, this, this.maxBucketSizeInBytes);
          newLeftBucket.addAll(leftEntries);

          if (splitLeaf) {
            newLeftBucket.setRightSibling(rightBucketPointer);
          }
        } finally {
          releasePageFromWrite(newLeftBucket, atomicOperation);
        }

        OSBTreeBonsaiBucketV2<K, V> newRightBucket = null;
        try {
          newRightBucket = new OSBTreeBonsaiBucketV2<>(rightBucketEntry, rightBucketPointer.getPageOffset(), splitLeaf,
              keySerializer, valueSerializer, this, maxBucketSizeInBytes);
          newRightBucket.addAll(rightEntries);

          if (splitLeaf)
            newRightBucket.setLeftSibling(leftBucketPointer);
        } finally {
          releasePageFromWrite(newRightBucket, atomicOperation);
        }

        bucketToSplit = new OSBTreeBonsaiBucketV2<>(bucketEntry, bucketPointer.getPageOffset(), false, keySerializer,
            valueSerializer, this, maxBucketSizeInBytes);
        bucketToSplit.setTreeSize(treeSize);

        bucketToSplit
            .addEntry(0, new OSBTreeBonsaiBucketV2.SBTreeEntry<>(leftBucketPointer, rightBucketPointer, separationKey, null), true);

        ArrayList<OBonsaiBucketPointer> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));

        if (comparator.compare(keyToInsert, separationKey) < 0) {
          resultPath.add(leftBucketPointer);
          return new BucketSearchResult(keyIndex, resultPath);
        }

        resultPath.add(rightBucketPointer);

        if (splitLeaf)
          return new BucketSearchResult(keyIndex - indexToSplit, resultPath);

        return new BucketSearchResult(keyIndex - indexToSplit - 1, resultPath);
      }

    } finally {
      releasePageFromWrite(bucketToSplit, atomicOperation);
    }
  }

  private BucketSearchResult findBucket(K key) throws IOException {
    OBonsaiBucketPointer bucketPointer = rootBucketPointer;
    final ArrayList<OBonsaiBucketPointer> path = new ArrayList<>();

    while (true) {
      path.add(bucketPointer);
      final OCacheEntry bucketEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);

      final OSBTreeBonsaiBucketV2.SBTreeEntry<K, V> entry;
      try {
        final OSBTreeBonsaiBucketV2<K, V> keyBucket = new OSBTreeBonsaiBucketV2<>(bucketEntry, bucketPointer.getPageOffset(),
            keySerializer, valueSerializer, this, maxBucketSizeInBytes);
        final int index = keyBucket.find(key);

        if (keyBucket.isLeaf())
          return new BucketSearchResult(index, path);

        if (index >= 0)
          entry = keyBucket.getEntry(index);
        else {
          final int insertionIndex = -index - 1;
          if (insertionIndex >= keyBucket.size())
            entry = keyBucket.getEntry(insertionIndex - 1);
          else
            entry = keyBucket.getEntry(insertionIndex);
        }

      } finally {
        releasePageFromRead(bucketEntry);
      }

      if (comparator.compare(key, entry.key) >= 0)
        bucketPointer = entry.rightChild;
      else
        bucketPointer = entry.leftChild;
    }
  }

  private void initSysBucket(OAtomicOperation atomicOperation) throws IOException {
    OCacheEntry sysCacheEntry = loadPageForWrite(fileId, SYS_BUCKET.getPageIndex(), false);
    if (sysCacheEntry == null) {
      sysCacheEntry = addPage(fileId);
      assert sysCacheEntry.getPageIndex() == SYS_BUCKET.getPageIndex();
    }

    OSysBucketV2 sysBucket = null;
    try {
      sysBucket = new OSysBucketV2(sysCacheEntry, maxBucketSizeInBytes);
      if (sysBucket.isInitialized()) {
        releasePageFromWrite(sysBucket, atomicOperation);

        sysCacheEntry = loadPageForWrite(fileId, SYS_BUCKET.getPageIndex(), false);

        sysBucket = new OSysBucketV2(sysCacheEntry, maxBucketSizeInBytes);
        sysBucket.init();
      }
    } finally {
      releasePageFromWrite(sysBucket, atomicOperation);
    }
  }

  private AllocationResult allocateBucketForWrite(OAtomicOperation atomicOperation) throws IOException {
    OCacheEntry sysCacheEntry = loadPageForWrite(fileId, SYS_BUCKET.getPageIndex(), false);
    if (sysCacheEntry == null) {
      sysCacheEntry = addPage(fileId);
      assert sysCacheEntry.getPageIndex() == SYS_BUCKET.getPageIndex();
    }

    OSysBucketV2 sysBucket = null;
    try {
      sysBucket = new OSysBucketV2(sysCacheEntry, maxBucketSizeInBytes);
      if (sysBucket.freeListLength() > 0) {
        final AllocationResult allocationResult = reuseBucketFromFreeList(sysBucket);
        return allocationResult;
      } else {
        final OBonsaiBucketPointer freeSpacePointer = sysBucket.getFreeSpacePointer();
        if (freeSpacePointer.getPageOffset() + maxBucketSizeInBytes > PAGE_SIZE) {
          final OCacheEntry cacheEntry = addPage(fileId);
          final long pageIndex = cacheEntry.getPageIndex();
          sysBucket.setFreeSpacePointer(new OBonsaiBucketPointer((int) pageIndex, maxBucketSizeInBytes, BINARY_VERSION));

          return new AllocationResult(new OBonsaiBucketPointer((int) pageIndex, 0, BINARY_VERSION), cacheEntry);
        } else {
          sysBucket.setFreeSpacePointer(
              new OBonsaiBucketPointer(freeSpacePointer.getPageIndex(), freeSpacePointer.getPageOffset() + maxBucketSizeInBytes,
                  BINARY_VERSION));
          final OCacheEntry cacheEntry = loadPageForWrite(fileId, freeSpacePointer.getPageIndex(), false);

          return new AllocationResult(freeSpacePointer, cacheEntry);
        }
      }
    } finally {
      releasePageFromWrite(sysBucket, atomicOperation);
    }
  }

  private AllocationResult reuseBucketFromFreeList(OSysBucketV2 sysBucket) throws IOException {
    final OBonsaiBucketPointer oldFreeListHead = sysBucket.getFreeListHead();
    assert oldFreeListHead.isValid();

    OCacheEntry cacheEntry = loadPageForWrite(fileId, oldFreeListHead.getPageIndex(), false);
    final OSBTreeBonsaiBucketV2<K, V> bucket = new OSBTreeBonsaiBucketV2<>(cacheEntry, oldFreeListHead.getPageOffset(),
        keySerializer, valueSerializer, this, maxBucketSizeInBytes);

    sysBucket.setFreeListHead(bucket.getFreeListPointer());
    sysBucket.setFreeListLength(sysBucket.freeListLength() - 1);

    return new AllocationResult(oldFreeListHead, cacheEntry);
  }

  @Override
  public int getRealBagSize(Map<K, Change> changes) {
    final Map<K, Change> notAppliedChanges = new HashMap<>(changes);
    final OModifiableInteger size = new OModifiableInteger(0);
    loadEntriesMajor(firstKey(), true, true, entry -> {
      final Change change = notAppliedChanges.remove(entry.getKey());
      final int result;

      final Integer treeValue = (Integer) entry.getValue();
      if (change == null)
        result = treeValue;
      else
        result = change.applyTo(treeValue);

      size.increment(result);
      return true;
    });

    for (Change change : notAppliedChanges.values()) {
      size.increment(change.applyTo(0));
    }

    return size.intValue();
  }

  @Override
  public OBinarySerializer<K> getKeySerializer() {
    final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
    try {
      return keySerializer;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public OBinarySerializer<V> getValueSerializer() {
    final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
    try {
      return valueSerializer;
    } finally {
      lock.unlock();
    }
  }

  private static class AllocationResult {
    private final OBonsaiBucketPointer pointer;
    private final OCacheEntry          cacheEntry;

    private AllocationResult(OBonsaiBucketPointer pointer, OCacheEntry cacheEntry) {
      this.pointer = pointer;
      this.cacheEntry = cacheEntry;
    }

    private OBonsaiBucketPointer getPointer() {
      return pointer;
    }

    private OCacheEntry getCacheEntry() {
      return cacheEntry;
    }
  }

  private static class BucketSearchResult {
    private final int                             itemIndex;
    private final ArrayList<OBonsaiBucketPointer> path;

    private BucketSearchResult(int itemIndex, ArrayList<OBonsaiBucketPointer> path) {
      this.itemIndex = itemIndex;
      this.path = path;
    }

    OBonsaiBucketPointer getLastPathItem() {
      return path.get(path.size() - 1);
    }
  }

  private static final class PagePathItemUnit {
    private final OBonsaiBucketPointer bucketPointer;
    private final int                  itemIndex;

    private PagePathItemUnit(OBonsaiBucketPointer bucketPointer, int itemIndex) {
      this.bucketPointer = bucketPointer;
      this.itemIndex = itemIndex;
    }
  }

  public void debugPrintBucket(PrintStream writer) throws IOException {
    final ArrayList<OBonsaiBucketPointer> path = new ArrayList<>();
    path.add(rootBucketPointer);
    debugPrintBucket(rootBucketPointer, writer, path);
  }

  @SuppressWarnings({ "resource", "StringConcatenationInsideStringBufferAppend" })
  private void debugPrintBucket(OBonsaiBucketPointer bucketPointer, PrintStream writer, final ArrayList<OBonsaiBucketPointer> path)
      throws IOException {

    final OCacheEntry bucketEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
    OSBTreeBonsaiBucketV2.SBTreeEntry<K, V> entry;
    try {
      final OSBTreeBonsaiBucketV2<K, V> keyBucket = new OSBTreeBonsaiBucketV2<>(bucketEntry, bucketPointer.getPageOffset(),
          keySerializer, valueSerializer, this, maxBucketSizeInBytes);
      if (keyBucket.isLeaf()) {
        for (int i = 0; i < path.size(); i++)
          writer.append("\t");
        writer.append(" Leaf backet:" + bucketPointer.getPageIndex() + "|" + bucketPointer.getPageOffset());
        writer
            .append(" left bucket:" + keyBucket.getLeftSibling().getPageIndex() + "|" + keyBucket.getLeftSibling().getPageOffset());
        writer.append(
            " right bucket:" + keyBucket.getRightSibling().getPageIndex() + "|" + keyBucket.getRightSibling().getPageOffset());
        writer.append(" size:" + keyBucket.size());
        writer.append(" content: [");
        for (int index = 0; index < keyBucket.size(); index++) {
          entry = keyBucket.getEntry(index);
          writer.append(entry.getKey() + ",");
        }
        writer.append("\n");
      } else {
        for (int i = 0; i < path.size(); i++)
          writer.append("\t");
        writer.append(" node bucket:" + bucketPointer.getPageIndex() + "|" + bucketPointer.getPageOffset());
        writer
            .append(" left bucket:" + keyBucket.getLeftSibling().getPageIndex() + "|" + keyBucket.getLeftSibling().getPageOffset());
        writer.append(
            " right bucket:" + keyBucket.getRightSibling().getPageIndex() + "|" + keyBucket.getRightSibling().getPageOffset());
        writer.append("\n");
        for (int index = 0; index < keyBucket.size(); index++) {
          entry = keyBucket.getEntry(index);
          for (int i = 0; i < path.size(); i++)
            writer.append("\t");
          writer.append(" entry:" + index + " key: " + entry.getKey() + " left \n");
          OBonsaiBucketPointer next = entry.leftChild;
          path.add(next);
          debugPrintBucket(next, writer, path);
          path.remove(next);
          for (int i = 0; i < path.size(); i++)
            writer.append("\t");
          writer.append(" entry:" + index + " key: " + entry.getKey() + " right \n");
          next = entry.rightChild;
          path.add(next);
          debugPrintBucket(next, writer, path);
          path.remove(next);

        }
      }
    } finally {
      releasePageFromRead(bucketEntry);
    }

  }
}
