/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.bookkeeper.client;

import java.io.IOException;
import java.security.GeneralSecurityException;
import org.apache.bookkeeper.client.AsyncCallback.OpenCallback;
import org.apache.bookkeeper.client.AsyncCallback.ReadLastConfirmedCallback;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.zookeeper.data.Stat;

/**
 * Encapsulates the ledger open operation
 *
 */
class LedgerOpenOp implements DataCallback {
    static final Logger LOG = LoggerFactory.getLogger(LedgerOpenOp.class);

    final BookKeeper bk;
    final long ledgerId;
    final OpenCallback cb;
    final Object ctx;
    LedgerHandle lh;
    final byte[] passwd;
    final DigestType digestType;
    boolean doRecovery;

    /**
     * Constructor.
     *
     * @param bk
     * @param ledgerId
     * @param digestType
     * @param passwd
     * @param cb
     * @param ctx
     */
    public LedgerOpenOp(BookKeeper bk, long ledgerId, DigestType digestType, byte[] passwd, 
                        OpenCallback cb, Object ctx) {
        this.bk = bk;
        this.ledgerId = ledgerId;
        this.passwd = passwd;
        this.cb = cb;
        this.ctx = ctx;
        this.digestType = digestType;

        this.doRecovery = true;
    }

    /**
     * Inititates the ledger open operation
     */
    public void initiate() {
        /**
         * Asynchronously read the ledger metadata node.
         */

        bk.getZkHandle().getData(bk.getLedgerManager().getLedgerPath(ledgerId), false, this, ctx);
    }

    /**
     * Inititates the ledger open operation without recovery
     */
    public void initiateWithoutRecovery() {
        this.doRecovery = false;
        initiate();
    }

    /**
     * Implements ZooKeeper data callback.
     * @see org.apache.zookeeper.AsyncCallback.DataCallback#processResult(int, String, Object, byte[], Stat)
     */
    public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {

        if (rc == KeeperException.Code.NONODE.intValue()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("No such ledger: " + ledgerId, KeeperException.create(KeeperException.Code.get(rc), path));
            }
            cb.openComplete(BKException.Code.NoSuchLedgerExistsException, null, this.ctx);
            return;
        }
        if (rc != KeeperException.Code.OK.intValue()) {
            LOG.error("Could not read metadata for ledger: " + ledgerId, KeeperException.create(KeeperException.Code
                      .get(rc), path));
            cb.openComplete(BKException.Code.ZKException, null, this.ctx);
            return;
        }

        LedgerMetadata metadata;
        try {
            metadata = LedgerMetadata.parseConfig(data, stat.getVersion());
        } catch (IOException e) {
            LOG.error("Could not parse ledger metadata for ledger: " + ledgerId, e);
            cb.openComplete(BKException.Code.ZKException, null, this.ctx);
            return;
        }

        try {
            lh = new ReadOnlyLedgerHandle(bk, ledgerId, metadata, digestType, passwd);
        } catch (GeneralSecurityException e) {
            LOG.error("Security exception while opening ledger: " + ledgerId, e);
            cb.openComplete(BKException.Code.DigestNotInitializedException, null, this.ctx);
            return;
        } catch (NumberFormatException e) {
            LOG.error("Incorrectly entered parameter throttle: " + bk.getConf().getThrottleValue(), e);
            cb.openComplete(BKException.Code.IncorrectParameterException, null, this.ctx);
            return;
        }

        if (metadata.isClosed()) {
            // Ledger was closed properly
            cb.openComplete(BKException.Code.OK, lh, this.ctx);
            return;
        }

        if (doRecovery) {
            lh.recover(new GenericCallback<Void>() {
                    @Override
                    public void operationComplete(int rc, Void result) {
                        if (rc != BKException.Code.OK) {
                            cb.openComplete(BKException.Code.LedgerRecoveryException, null, LedgerOpenOp.this.ctx);
                        } else {
                            cb.openComplete(BKException.Code.OK, lh, LedgerOpenOp.this.ctx);
                        }
                    }
                });
        } else {
            lh.asyncReadLastConfirmed(new ReadLastConfirmedCallback() {

                @Override
                public void readLastConfirmedComplete(int rc,
                        long lastConfirmed, Object ctx) {
                    if (rc != BKException.Code.OK) {
                        cb.openComplete(BKException.Code.ReadException, null, LedgerOpenOp.this.ctx);
                    } else {
                        lh.lastAddConfirmed = lh.lastAddPushed = lastConfirmed;
                        cb.openComplete(BKException.Code.OK, lh, LedgerOpenOp.this.ctx);
                    }
                }
                
            }, null);
            
        }
    }
}
