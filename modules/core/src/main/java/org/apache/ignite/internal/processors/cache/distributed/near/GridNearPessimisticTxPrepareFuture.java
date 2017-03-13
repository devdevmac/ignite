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

package org.apache.ignite.internal.processors.cache.distributed.near;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.cluster.ClusterTopologyCheckedException;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheEntryEx;
import org.apache.ignite.internal.processors.cache.GridCacheMessage;
import org.apache.ignite.internal.processors.cache.GridCacheMvccCandidate;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.cache.distributed.GridDistributedTxMapping;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionTopology;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtTxMapping;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtTxPrepareResponse;
import org.apache.ignite.internal.processors.cache.transactions.IgniteInternalTx;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxEntry;
import org.apache.ignite.internal.transactions.IgniteTxRollbackCheckedException;
import org.apache.ignite.internal.transactions.IgniteTxTimeoutCheckedException;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.typedef.C1;
import org.apache.ignite.internal.util.typedef.CI1;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiTuple;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.internal.processors.cache.GridCacheOperation.TRANSFORM;
import static org.apache.ignite.transactions.TransactionState.PREPARED;
import static org.apache.ignite.transactions.TransactionState.PREPARING;

/**
 *
 */
public class GridNearPessimisticTxPrepareFuture extends GridNearTxPrepareFutureAdapter {
    /**
     * @param cctx Context.
     * @param tx Transaction.
     */
    public GridNearPessimisticTxPrepareFuture(GridCacheSharedContext cctx, GridNearTxLocal tx) {
        super(cctx, tx);

        assert tx.pessimistic() : tx;
    }

    /** {@inheritDoc} */
    @Override protected boolean ignoreFailure(Throwable err) {
        return IgniteCheckedException.class.isAssignableFrom(err.getClass());
    }

    /** {@inheritDoc} */
    @Override public boolean onNodeLeft(UUID nodeId) {
        for (IgniteInternalFuture<?> fut : futures()) {
            MiniFuture f = (MiniFuture)fut;

            if (f.primary().id().equals(nodeId)) {
                ClusterTopologyCheckedException e = new ClusterTopologyCheckedException("Remote node left grid: " +
                    nodeId);

                e.retryReadyFuture(cctx.nextAffinityReadyFuture(tx.topologyVersion()));

                f.onPrimaryLeft(e);
            }
            else
                f.checkDhtFailed(nodeId);
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public void onPrimaryResponse(UUID nodeId, GridNearTxPrepareResponse res) {
        if (!isDone()) {
            assert res.clientRemapVersion() == null : res;

            MiniFuture f = miniFuture(res.miniId());

            if (f != null) {
                assert f.primary().id().equals(nodeId);

                f.onPrimaryResponse(res);
            }
            else {
                if (msgLog.isDebugEnabled()) {
                    msgLog.debug("Near pessimistic prepare, failed to find mini future [txId=" + tx.nearXidVersion() +
                        ", node=" + nodeId +
                        ", res=" + res +
                        ", fut=" + this + ']');
                }
            }
        }
        else {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Near pessimistic prepare, response for finished future [txId=" + tx.nearXidVersion() +
                    ", node=" + nodeId +
                    ", res=" + res +
                    ", fut=" + this + ']');
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void onDhtResponse(UUID nodeId, GridDhtTxPrepareResponse res) {
        assert res.nearNodeResponse() : res;

        MiniFuture f = miniFuture(res.miniId());

        if (f != null)
            f.onDhtResponse(nodeId, res);
        else {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Near pessimistic prepare, failed to find mini future [txId=" + tx.nearXidVersion() +
                    ", node=" + nodeId +
                    ", res=" + res +
                    ", fut=" + this + ']');
            }
        }
    }

    /**
     * Finds pending mini future by the given mini ID.
     *
     * @param miniId Mini ID to find.
     * @return Mini future.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    private MiniFuture miniFuture(int miniId) {
        // We iterate directly over the futs collection here to avoid copy.
        synchronized (sync) {
            int size = futuresCountNoLock();

            // Avoid iterator creation.
            for (int i = 0; i < size; i++) {
                MiniFuture mini = (MiniFuture)future(i);

                if (mini.futureId() == miniId) {
                    if (!mini.isDone())
                        return mini;
                    else
                        return null;
                }
            }
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override public void prepare() {
        if (!tx.state(PREPARING)) {
            if (tx.setRollbackOnly()) {
                if (tx.remainingTime() == -1)
                    onDone(new IgniteTxTimeoutCheckedException("Transaction timed out and was rolled back: " + tx));
                else
                    onDone(new IgniteCheckedException("Invalid transaction state for prepare " +
                        "[state=" + tx.state() + ", tx=" + this + ']'));
            }
            else
                onDone(new IgniteTxRollbackCheckedException("Invalid transaction state for prepare " +
                    "[state=" + tx.state() + ", tx=" + this + ']'));

            return;
        }

        try {
            tx.userPrepare();

            cctx.mvcc().addFuture(this);

            preparePessimistic();
        }
        catch (IgniteCheckedException e) {
            onDone(e);
        }
    }

    /**
     *
     */
    private void preparePessimistic() {
        // TODO IGNITE-4768: need detect on lock step?
        boolean dhtReplyNear = true;

        Map<IgniteBiTuple<ClusterNode, Boolean>, GridDistributedTxMapping> mappings = new HashMap<>();

        AffinityTopologyVersion topVer = tx.topologyVersion();

        GridDhtTxMapping txMapping = new GridDhtTxMapping();

        for (IgniteTxEntry txEntry : tx.allEntries()) {
            txEntry.clearEntryReadVersion();

            GridCacheContext cacheCtx = txEntry.context();

            List<ClusterNode> nodes;

            if (!cacheCtx.isLocal()) {
                GridDhtPartitionTopology top = cacheCtx.topology();

                nodes = top.nodes(cacheCtx.affinity().partition(txEntry.key()), topVer);

                if (dhtReplyNear &&
                    (!top.rebalanceFinished(topVer) || cctx.discovery().hasNearCache(cacheCtx.cacheId(), topVer) || nodes.size() == 1))
                    dhtReplyNear = false;
            }
            else
                nodes = cacheCtx.affinity().nodesByKey(txEntry.key(), topVer);

            ClusterNode primary = F.first(nodes);

            boolean near = cacheCtx.isNear();

            IgniteBiTuple<ClusterNode, Boolean> key = F.t(primary, near);

            GridDistributedTxMapping nodeMapping = mappings.get(key);

            if (nodeMapping == null) {
                nodeMapping = new GridDistributedTxMapping(primary);

                nodeMapping.near(cacheCtx.isNear());

                mappings.put(key, nodeMapping);
            }

            txEntry.nodeId(primary.id());

            nodeMapping.add(txEntry);

            txMapping.addMapping(nodes);
        }

        tx.transactionNodes(txMapping.transactionNodes());

        checkOnePhase(txMapping);

        // TODO IGNITE-4768.
        if (dhtReplyNear && tx.onePhaseCommit())
            dhtReplyNear = false;

        tx.dhtReplyNear(dhtReplyNear);

        long timeout = tx.remainingTime();

        if (timeout == -1) {
            onDone(new IgniteTxTimeoutCheckedException("Transaction timed out and was rolled back: " + tx));

            return;
        }

        int miniId = 0;

        for (final GridDistributedTxMapping m : mappings.values()) {
            final ClusterNode primary = m.primary();

            GridNearTxPrepareRequest req = new GridNearTxPrepareRequest(
                futId,
                tx.topologyVersion(),
                tx,
                timeout,
                m.reads(),
                m.writes(),
                m.near(),
                txMapping.transactionNodes(),
                dhtReplyNear,
                true,
                tx.onePhaseCommit(),
                tx.needReturnValue() && tx.implicit(),
                tx.implicitSingle(),
                m.explicitLock(),
                tx.subjectId(),
                tx.taskNameHash(),
                false,
                tx.activeCachesDeploymentEnabled());

            for (IgniteTxEntry txEntry : m.entries()) {
                if (txEntry.op() == TRANSFORM)
                    req.addDhtVersion(txEntry.txKey(), null);
            }

            final MiniFuture fut = new MiniFuture(m, ++miniId, req);

            req.miniId(fut.futureId());

            add(fut);

            if (primary.isLocal()) {
                IgniteInternalFuture<GridNearTxPrepareResponse> prepFut = cctx.tm().txHandler().prepareTx(primary.id(),
                    tx,
                    req);

                prepFut.listen(new CI1<IgniteInternalFuture<GridNearTxPrepareResponse>>() {
                    @Override public void apply(IgniteInternalFuture<GridNearTxPrepareResponse> prepFut) {
                        try {
                            fut.onPrimaryResponse(prepFut.get());
                        }
                        catch (IgniteCheckedException e) {
                            fut.onError(e);
                        }
                    }
                });
            }
            else {
                if (!sendPrimaryRequest(primary, fut, req))
                    break;
            }
        }

        markInitialized();
    }

    /**
     * @param primary Primary node.
     * @param fut Future.
     * @param req Request.
     * @return {@code False} if failed to send request.
     */
    private boolean sendPrimaryRequest(ClusterNode primary,  MiniFuture fut, GridCacheMessage req) {
        try {
            cctx.io().send(primary, req, tx.ioPolicy());

            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Near pessimistic prepare, sent request [txId=" + tx.nearXidVersion() +
                    ", node=" + primary.id() + ']');
            }

            return true;
        }
        catch (ClusterTopologyCheckedException e) {
            e.retryReadyFuture(cctx.nextAffinityReadyFuture(tx.topologyVersion()));

            fut.onPrimaryLeft(e);

            return false;
        }
        catch (IgniteCheckedException e) {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Near pessimistic prepare, failed send request [txId=" + tx.nearXidVersion() +
                    ", node=" + primary.id() + ", err=" + e + ']');
            }

            fut.onError(e);

            return false;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean onOwnerChanged(GridCacheEntryEx entry, GridCacheMvccCandidate owner) {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean onDone(@Nullable IgniteInternalTx res, @Nullable Throwable err) {
        if (err != null)
            ERR_UPD.compareAndSet(GridNearPessimisticTxPrepareFuture.this, null, err);

        err = this.err;

        if (err == null || tx.needCheckBackup())
            tx.state(PREPARED);

        if (super.onDone(tx, err)) {
            cctx.mvcc().removeMvccFuture(this);

            return true;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        Collection<String> futs = F.viewReadOnly(futures(), new C1<IgniteInternalFuture<?>, String>() {
            @Override public String apply(IgniteInternalFuture<?> f) {
                return "[node=" + ((MiniFuture)f).primary().id() +
                    ", loc=" + ((MiniFuture)f).primary().isLocal() +
                    ", done=" + f.isDone() + "]";
            }
        });

        return S.toString(GridNearPessimisticTxPrepareFuture.class, this,
            "innerFuts", futs,
            "super", super.toString());
    }

    /**
     *
     */
    private class MiniFuture extends GridFutureAdapter {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private final int futId;

        /** */
        private GridDistributedTxMapping m;

        /** */
        private final Set<UUID> dhtNodes;

        /** */
        private boolean primaryProcessed;

        /**
         * @param m Mapping.
         * @param futId Mini future ID.
         * @param req Request.
         */
        MiniFuture(GridDistributedTxMapping m, int futId, GridNearTxPrepareRequest req) {
            this.m = m;
            this.futId = futId;

            // TODO: IGNITE-4768, check nodes alive.
            if (req.dhtReplyNear()) {
                dhtNodes = new HashSet<>(req.transactionNodes().get(m.primary().id()));

                assert !F.isEmpty(dhtNodes) : dhtNodes;
            }
            else
                dhtNodes = null;
        }

        /**
         * @return Future ID.
         */
        int futureId() {
            return futId;
        }

        /**
         * @return Node ID.
         */
        public ClusterNode primary() {
            return m.primary();
        }

        /**
         * @param res Response.
         */
        void onPrimaryResponse(GridNearTxPrepareResponse res) {
            if (res.error() != null)
                onError(res.error());
            else {
                assert dhtNodes == null;

                processPrimaryPrepareResponse(m, res);

                onDone();
            }
        }

        /**
         * @param failedNodeId Failed node ID.
         */
        void checkDhtFailed(UUID failedNodeId) {
            if (dhtNodes == null)
                return;

            boolean done = false;
            GridNearTxPrimaryPrepareCheckRequest checkReq = null;

            synchronized (dhtNodes) {
                if (dhtNodes.remove(failedNodeId) && dhtNodes.isEmpty()) {
                    if (primaryProcessed)
                        done = true;
                    else
                        checkReq = new GridNearTxPrimaryPrepareCheckRequest();
                }
            }

            if (checkReq != null) {
                if (cctx.localNodeId().equals(primary().id())) {
                    // TODO IGNITE-4768.
                }
                else
                    sendPrimaryRequest(primary(), this, checkReq);
            }
            else if (done)
                onDone();
        }

        /**
         * @param nodeId Node ID.
         * @param res Response.
         */
        void onDhtResponse(UUID nodeId, GridDhtTxPrepareResponse res) {
            assert dhtNodes != null;

            boolean done;

            synchronized (dhtNodes) {
                primaryProcessed = true;

                done = dhtNodes.remove(nodeId) && dhtNodes.isEmpty();
            }

            if (done)
                onDone();
        }

        /**
         * @param e Error.
         */
        void onPrimaryLeft(ClusterTopologyCheckedException e) {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Near pessimistic prepare, mini future node left [txId=" + tx.nearXidVersion() +
                    ", nodeId=" + m.primary().id() + ']');
            }

            if (tx.onePhaseCommit()) {
                tx.markForBackupCheck();

                // Do not fail future for one-phase transaction right away.
                onDone();
            }

            onError(e);
        }

        /**
         * @param e Error.
         */
        void onError(Throwable e) {
            if (isDone()) {
                U.warn(log, "Received error when future is done [fut=" + this + ", err=" + e + ", tx=" + tx + ']');

                return;
            }

            if (log.isDebugEnabled())
                log.debug("Error on tx prepare [fut=" + this + ", err=" + e + ", tx=" + tx +  ']');

            if (ERR_UPD.compareAndSet(GridNearPessimisticTxPrepareFuture.this, null, e))
                tx.setRollbackOnly();

            onDone(e);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(MiniFuture.class, this, "done", isDone(), "cancelled", isCancelled(), "err", error());
        }
    }
}
