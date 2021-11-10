/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.jdk;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.impl.UnmanagedMemorySupport;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.annotate.Uninterruptible;

/**
 * An uninterruptible hashtable with a fixed size that uses chaining in case of a collision.
 */
public abstract class AbstractUninterruptibleHashtable<T extends UninterruptibleEntry<T>> implements UninterruptibleHashtable<T> {
    private static final int DEFAULT_TABLE_LENGTH = 2053;

    private final T[] table;

    private int size;

    @Platforms(Platform.HOSTED_ONLY.class)
    public AbstractUninterruptibleHashtable() {
        this(DEFAULT_TABLE_LENGTH);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public AbstractUninterruptibleHashtable(int primeLength) {
        this.table = createTable(primeLength);
        this.size = 0;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    protected abstract T[] createTable(int length);

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected abstract boolean isEqual(T a, T b);

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected abstract T copyToHeap(T valueOnStack);

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected T copyToHeap(T pointerOnStack, UnsignedWord sizeToAlloc) {
        T pointerOnHeap = ImageSingletons.lookup(UnmanagedMemorySupport.class).malloc(sizeToAlloc);
        if (pointerOnHeap.isNonNull()) {
            UnmanagedMemoryUtil.copy((Pointer) pointerOnStack, (Pointer) pointerOnHeap, sizeToAlloc);
            return pointerOnHeap;
        }
        return WordFactory.nullPointer();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected abstract void free(T t);

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected T insertEntry(T valueOnStack) {
        int index = Integer.remainderUnsigned(valueOnStack.getHash(), DEFAULT_TABLE_LENGTH);
        T newEntry = copyToHeap(valueOnStack);
        if (newEntry.isNonNull()) {
            T existingEntry = table[index];
            newEntry.setNext(existingEntry);
            table[index] = newEntry;
            size++;
            return newEntry;
        }
        return WordFactory.nullPointer();
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public int getSize() {
        return size;
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public T[] getTable() {
        return table;
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public T get(T valueOnStack) {
        int index = Integer.remainderUnsigned(valueOnStack.getHash(), DEFAULT_TABLE_LENGTH);
        T entry = table[index];
        while (entry.isNonNull()) {
            if (isEqual(valueOnStack, entry)) {
                return entry;
            }
            entry = entry.getNext();
        }
        return WordFactory.nullPointer();
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public T getOrPut(T valueOnStack) {
        assert valueOnStack.isNonNull();

        T entry = get(valueOnStack);
        if (entry.isNonNull()) {
            return WordFactory.nullPointer();
        } else {
            return insertEntry(valueOnStack);
        }
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean putIfAbsent(T valueOnStack) {
        assert valueOnStack.isNonNull();

        T existingEntry = get(valueOnStack);
        if (existingEntry.isNonNull()) {
            return false;
        } else {
            T newEntry = insertEntry(valueOnStack);
            return newEntry.isNonNull();
        }
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void clear() {
        for (int i = 0; i < table.length; i++) {
            T entry = table[i];
            while (entry.isNonNull()) {
                T tmp = entry;
                entry = entry.getNext();
                free(tmp);
            }
            table[i] = WordFactory.nullPointer();
        }
        size = 0;
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void teardown() {
        clear();
    }
}