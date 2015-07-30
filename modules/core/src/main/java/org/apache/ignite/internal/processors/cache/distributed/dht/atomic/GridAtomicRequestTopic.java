/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.dht.atomic;

import org.apache.ignite.internal.util.typedef.internal.*;

import java.io.*;

/**
 */
class GridAtomicRequestTopic implements Externalizable {
    /** */
    private static final long serialVersionUID = 0L;

    /** */
    private int cacheId;

    /** */
    private int part;

    /** */
    private boolean near;

    /**
     * For {@link Externalizable}.
     */
    public GridAtomicRequestTopic() {
        // No-op.
    }

    /**
     * @param cacheId Cache ID.
     * @param part Partition.
     * @param near Near flag.
     */
    GridAtomicRequestTopic(int cacheId, int part, boolean near) {
        this.cacheId = cacheId;
        this.part = part;
        this.near = near;
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass())
            return false;

        GridAtomicRequestTopic topic = (GridAtomicRequestTopic)o;

        return cacheId == topic.cacheId && part == topic.part && near == topic.near;
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        int res = cacheId;

        res = 31 * res + part;
        res = 31 * res + (near ? 1 : 0);

        return res;
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(cacheId);
        out.writeInt(part);
        out.writeBoolean(near);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        cacheId = in.readInt();
        part = in.readInt();
        near = in.readBoolean();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridAtomicRequestTopic.class, this);
    }
}
