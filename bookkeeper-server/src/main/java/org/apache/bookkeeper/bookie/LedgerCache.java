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

package org.apache.bookkeeper.bookie;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.bookkeeper.meta.LedgerManager;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class maps a ledger entry number into a location (entrylogid, offset) in
 * an entry log file. It does user level caching to more efficiently manage disk
 * head scheduling.
 */
public class LedgerCache {
    private final static Logger LOG = LoggerFactory.getLogger(LedgerDescriptor.class);

    final File ledgerDirectories[];

    public LedgerCache(ServerConfiguration conf, LedgerManager alm) {
        this.ledgerDirectories = conf.getLedgerDirs();
        this.openFileLimit = conf.getOpenFileLimit();
        this.pageSize = conf.getPageSize();
        this.entriesPerPage = pageSize / 8;
        
        if (conf.getPageLimit() <= 0) {
            // allocate half of the memory to the page cache
            this.pageLimit = (int)((Runtime.getRuntime().maxMemory() / 3) / this.pageSize);
        } else {
            this.pageLimit = conf.getPageLimit();
        }
        LOG.info("maxMemory = " + Runtime.getRuntime().maxMemory());
        LOG.info("openFileLimit is " + openFileLimit + ", pageSize is " + pageSize + ", pageLimit is " + pageLimit);
        activeLedgerManager = alm;
        // Retrieve all of the active ledgers.
        getActiveLedgers();
    }
    /**
     * the list of potentially clean ledgers
     */
    LinkedList<Long> cleanLedgers = new LinkedList<Long>();

    /**
     * the list of potentially dirty ledgers
     */
    LinkedList<Long> dirtyLedgers = new LinkedList<Long>();

    HashMap<Long, FileInfo> fileInfoCache = new HashMap<Long, FileInfo>();

    LinkedList<Long> openLedgers = new LinkedList<Long>();

    // Manage all active ledgers in LedgerManager
    // so LedgerManager has knowledge to garbage collect inactive/deleted ledgers
    final LedgerManager activeLedgerManager;

    final int openFileLimit;
    final int pageSize;
    final int pageLimit;
    final int entriesPerPage;

    /**
     * @return page size used in ledger cache
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * @return entries per page used in ledger cache
     */
    public int getEntriesPerPage() {
        return entriesPerPage;
    }

    /**
     * @return page limitation in ledger cache
     */
    public int getPageLimit() {
        return pageLimit;
    }

    // The number of pages that have actually been used
    private int pageCount = 0;
    HashMap<Long, HashMap<Long,LedgerEntryPage>> pages = new HashMap<Long, HashMap<Long,LedgerEntryPage>>();

    /**
     * @return number of page used in ledger cache
     */
    public int getNumUsedPages() {
        return pageCount;
    }

    private void putIntoTable(HashMap<Long, HashMap<Long,LedgerEntryPage>> table, LedgerEntryPage lep) {
        HashMap<Long, LedgerEntryPage> map = table.get(lep.getLedger());
        if (map == null) {
            map = new HashMap<Long, LedgerEntryPage>();
            table.put(lep.getLedger(), map);
        }
        map.put(lep.getFirstEntry(), lep);
    }

    private static LedgerEntryPage getFromTable(HashMap<Long, HashMap<Long,LedgerEntryPage>> table, Long ledger, Long firstEntry) {
        HashMap<Long, LedgerEntryPage> map = table.get(ledger);
        if (map != null) {
            return map.get(firstEntry);
        }
        return null;
    }

    synchronized private LedgerEntryPage getLedgerEntryPage(Long ledger, Long firstEntry, boolean onlyDirty) {
        LedgerEntryPage lep = getFromTable(pages, ledger, firstEntry);
        try {
            if (onlyDirty && lep.isClean()) {
                return null;
            }
            return lep;
        } finally {
            if (lep != null) {
                lep.usePage();
            }
        }
    }

    public void putEntryOffset(long ledger, long entry, long offset) throws IOException {
        int offsetInPage = (int) (entry % entriesPerPage);
        // find the id of the first entry of the page that has the entry
        // we are looking for
        long pageEntry = entry-offsetInPage;
        LedgerEntryPage lep = getLedgerEntryPage(ledger, pageEntry, false);
        if (lep == null) {
            // find a free page
            lep = grabCleanPage(ledger, pageEntry);
            updatePage(lep);
            synchronized(this) {
                putIntoTable(pages, lep);
            }
        }
        if (lep != null) {
            lep.setOffset(offset, offsetInPage*8);
            lep.releasePage();
            return;
        }
    }

    public long getEntryOffset(long ledger, long entry) throws IOException {
        int offsetInPage = (int) (entry%entriesPerPage);
        // find the id of the first entry of the page that has the entry
        // we are looking for
        long pageEntry = entry-offsetInPage;
        LedgerEntryPage lep = getLedgerEntryPage(ledger, pageEntry, false);
        try {
            if (lep == null) {
                lep = grabCleanPage(ledger, pageEntry);
                synchronized(this) {
                    putIntoTable(pages, lep);
                }
                updatePage(lep);

            }
            return lep.getOffset(offsetInPage*8);
        } finally {
            if (lep != null) {
                lep.releasePage();
            }
        }
    }

    static final String getLedgerName(long ledgerId) {
        int parent = (int) (ledgerId & 0xff);
        int grandParent = (int) ((ledgerId & 0xff00) >> 8);
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(grandParent));
        sb.append('/');
        sb.append(Integer.toHexString(parent));
        sb.append('/');
        sb.append(Long.toHexString(ledgerId));
        sb.append(".idx");
        return sb.toString();
    }

    static final private void checkParents(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent.exists()) {
            return;
        }
        if (parent.mkdirs() == false) {
            throw new IOException("Counldn't mkdirs for " + parent);
        }
    }

    static final private Random rand = new Random();

    static final private File pickDirs(File dirs[]) {
        return dirs[rand.nextInt(dirs.length)];
    }

    FileInfo getFileInfo(Long ledger, byte masterKey[]) throws IOException {
        synchronized(fileInfoCache) {
            FileInfo fi = fileInfoCache.get(ledger);
            if (fi == null) {
                String ledgerName = getLedgerName(ledger);
                File lf = null;
                for(File d: ledgerDirectories) {
                    lf = new File(d, ledgerName);
                    if (lf.exists()) {
                        break;
                    }
                    lf = null;
                }
                if (lf == null) {
                    if (masterKey == null) {
                        throw new Bookie.NoLedgerException(ledger);
                    }
                    File dir = pickDirs(ledgerDirectories);
                    lf = new File(dir, ledgerName);
                    checkParents(lf);
                    // A new ledger index file has been created for this Bookie.
                    // Add this new ledger to the set of active ledgers.
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("New ledger index file created for ledgerId: " + ledger);
                    }
                    activeLedgerManager.addActiveLedger(ledger, true);
                }
                if (openLedgers.size() > openFileLimit) {
                    fileInfoCache.remove(openLedgers.removeFirst()).close();
                }
                fi = new FileInfo(lf, masterKey);
                fileInfoCache.put(ledger, fi);
                openLedgers.add(ledger);
            }
            if (fi != null) {
                fi.use();
            }
            return fi;
        }
    }
    private void updatePage(LedgerEntryPage lep) throws IOException {
        if (!lep.isClean()) {
            throw new IOException("Trying to update a dirty page");
        }
        FileInfo fi = null;
        try {
            fi = getFileInfo(lep.getLedger(), null);
            long pos = lep.getFirstEntry()*8;
            if (pos >= fi.size()) {
                lep.zeroPage();
            } else {
                lep.readPage(fi);
            }
        } finally {
            if (fi != null) {
                fi.release();
            }
        }
    }

    void flushLedger(boolean doAll) throws IOException {
        synchronized(dirtyLedgers) {
            if (dirtyLedgers.isEmpty()) {
                synchronized(this) {
                    for(Long l: pages.keySet()) {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Adding " + Long.toHexString(l) + " to dirty pages");
                        }
                        dirtyLedgers.add(l);
                    }
                }
            }
            if (dirtyLedgers.isEmpty()) {
                return;
            }
            while(!dirtyLedgers.isEmpty()) {
                Long l = dirtyLedgers.removeFirst();
                LinkedList<Long> firstEntryList;
                synchronized(this) {
                    HashMap<Long, LedgerEntryPage> pageMap = pages.get(l);
                    if (pageMap == null || pageMap.isEmpty()) {
                        continue;
                    }
                    firstEntryList = new LinkedList<Long>();
                    for(Map.Entry<Long, LedgerEntryPage> entry: pageMap.entrySet()) {
                        LedgerEntryPage lep = entry.getValue();
                        if (lep.isClean()) {
                            if (LOG.isTraceEnabled()) {
                                LOG.trace("Page is clean " + lep);
                            }
                            continue;
                        }
                        firstEntryList.add(lep.getFirstEntry());
                    }
                }
                // Now flush all the pages of a ledger
                List<LedgerEntryPage> entries = new ArrayList<LedgerEntryPage>(firstEntryList.size());
                FileInfo fi = null;
                try {
                    for(Long firstEntry: firstEntryList) {
                        LedgerEntryPage lep = getLedgerEntryPage(l, firstEntry, true);
                        if (lep != null) {
                            entries.add(lep);
                        }
                    }
                    Collections.sort(entries, new Comparator<LedgerEntryPage>() {
                        @Override
                        public int compare(LedgerEntryPage o1, LedgerEntryPage o2) {
                            return (int)(o1.getFirstEntry()-o2.getFirstEntry());
                        }
                    });
                    ArrayList<Integer> versions = new ArrayList<Integer>(entries.size());
                    fi = getFileInfo(l, null);
                    int start = 0;
                    long lastOffset = -1;
                    for(int i = 0; i < entries.size(); i++) {
                        versions.add(i, entries.get(i).getVersion());
                        if (lastOffset != -1 && (entries.get(i).getFirstEntry() - lastOffset) != entriesPerPage) {
                            // send up a sequential list
                            int count = i - start;
                            if (count == 0) {
                                System.out.println("Count cannot possibly be zero!");
                            }
                            writeBuffers(l, entries, fi, start, count);
                            start = i;
                        }
                        lastOffset = entries.get(i).getFirstEntry();
                    }
                    if (entries.size()-start == 0 && entries.size() != 0) {
                        System.out.println("Nothing to write, but there were entries!");
                    }
                    writeBuffers(l, entries, fi, start, entries.size()-start);
                    synchronized(this) {
                        for(int i = 0; i < entries.size(); i++) {
                            LedgerEntryPage lep = entries.get(i);
                            lep.setClean(versions.get(i));
                        }
                    }
                } finally {
                    for(LedgerEntryPage lep: entries) {
                        lep.releasePage();
                    }
                    if (fi != null) {
                        fi.release();
                    }
                }
                if (!doAll) {
                    break;
                }
                // Yeild. if we are doing all the ledgers we don't want to block other flushes that
                // need to happen
                try {
                    dirtyLedgers.wait(1);
                } catch (InterruptedException e) {
                    // just pass it on
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void writeBuffers(Long ledger,
                              List<LedgerEntryPage> entries, FileInfo fi,
                              int start, int count) throws IOException {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Writing " + count + " buffers of " + Long.toHexString(ledger));
        }
        if (count == 0) {
            //System.out.println("Count is zero!");
            return;
        }
        ByteBuffer buffs[] = new ByteBuffer[count];
        for(int j = 0; j < count; j++) {
            buffs[j] = entries.get(start+j).getPageToWrite();
            if (entries.get(start+j).getLedger() != ledger) {
                throw new IOException("Writing to " + ledger + " but page belongs to " + entries.get(start+j).getLedger());
            }
        }
        long totalWritten = 0;
        while(buffs[buffs.length-1].remaining() > 0) {
            long rc = fi.write(buffs, entries.get(start+0).getFirstEntry()*8);
            if (rc <= 0) {
                throw new IOException("Short write to ledger " + ledger + " rc = " + rc);
            }
            //System.out.println("Wrote " + rc + " to " + ledger);
            totalWritten += rc;
        }
        if (totalWritten != count * pageSize) {
            throw new IOException("Short write to ledger " + ledger + " wrote " + totalWritten + " expected " + count * pageSize);
        }
    }
    private LedgerEntryPage grabCleanPage(long ledger, long entry) throws IOException {
        if (entry % entriesPerPage != 0) {
            throw new IllegalArgumentException(entry + " is not a multiple of " + entriesPerPage);
        }
        synchronized(this) {
            if (pageCount  < pageLimit) {
                // let's see if we can allocate something
                LedgerEntryPage lep = new LedgerEntryPage(pageSize, entriesPerPage);
                lep.setLedger(ledger);
                lep.setFirstEntry(entry);

                // note, this will not block since it is a new page
                lep.usePage();
                pageCount++;
                return lep;
            }
        }

        outerLoop:
        while(true) {
            synchronized(cleanLedgers) {
                if (cleanLedgers.isEmpty()) {
                    flushLedger(false);
                    synchronized(this) {
                        for(Long l: pages.keySet()) {
                            cleanLedgers.add(l);
                        }
                    }
                }
                synchronized(this) {
                    Long cleanLedger = cleanLedgers.getFirst();
                    Map<Long, LedgerEntryPage> map = pages.get(cleanLedger);
                    if (map == null || map.isEmpty()) {
                        cleanLedgers.removeFirst();
                        continue;
                    }
                    Iterator<Map.Entry<Long, LedgerEntryPage>> it = map.entrySet().iterator();
                    LedgerEntryPage lep = it.next().getValue();
                    while((lep.inUse() || !lep.isClean())) {
                        if (!it.hasNext()) {
                            continue outerLoop;
                        }
                        lep = it.next().getValue();
                    }
                    it.remove();
                    if (map.isEmpty()) {
                        pages.remove(lep.getLedger());
                    }
                    lep.usePage();
                    lep.zeroPage();
                    lep.setLedger(ledger);
                    lep.setFirstEntry(entry);
                    return lep;
                }
            }
        }
    }

    public long getLastEntry(long ledgerId) {
        long lastEntry = 0;
        // Find the last entry in the cache
        synchronized(this) {
            Map<Long, LedgerEntryPage> map = pages.get(ledgerId);
            if (map != null) {
                for(LedgerEntryPage lep: map.values()) {
                    if (lep.getFirstEntry() + entriesPerPage < lastEntry) {
                        continue;
                    }
                    lep.usePage();
                    long highest = lep.getLastEntry();
                    if (highest > lastEntry) {
                        lastEntry = highest;
                    }
                    lep.releasePage();
                }
            }
        }

        return lastEntry;
    }

    /**
     * This method will look within the ledger directories for the ledger index
     * files. That will comprise the set of active ledgers this particular
     * BookieServer knows about that have not yet been deleted by the BookKeeper
     * Client. This is called only once during initialization.
     */
    private void getActiveLedgers() {
        // Ledger index files are stored in a file hierarchy with a parent and
        // grandParent directory. We'll have to go two levels deep into these
        // directories to find the index files.
        for (File ledgerDirectory : ledgerDirectories) {
            for (File grandParent : ledgerDirectory.listFiles()) {
                if (grandParent.isDirectory()) {
                    for (File parent : grandParent.listFiles()) {
                        if (parent.isDirectory()) {
                            for (File index : parent.listFiles()) {
                                if (!index.isFile() || !index.getName().endsWith(".idx")) {
                                    continue;
                                }
                                // We've found a ledger index file. The file name is the
                                // HexString representation of the ledgerId.
                                String ledgerIdInHex = index.getName().substring(0, index.getName().length() - 4);
                                activeLedgerManager.addActiveLedger(Long.parseLong(ledgerIdInHex, 16), true);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * This method is called whenever a ledger is deleted by the BookKeeper Client
     * and we want to remove all relevant data for it stored in the LedgerCache.
     */
    void deleteLedger(long ledgerId) throws IOException {
        if (LOG.isDebugEnabled())
            LOG.debug("Deleting ledgerId: " + ledgerId);
        // Delete the ledger's index file and close the FileInfo
        FileInfo fi = getFileInfo(ledgerId, null);
        fi.delete();
        fi.close();

        // Remove it from the active ledger manager
        activeLedgerManager.removeActiveLedger(ledgerId);

        // Now remove it from all the other lists and maps.
        // These data structures need to be synchronized first before removing entries.
        synchronized(this) {
            pages.remove(ledgerId);
        }
        synchronized(fileInfoCache) {
            fileInfoCache.remove(ledgerId);
        }
        synchronized(cleanLedgers) {
            cleanLedgers.remove(ledgerId);
        }
        synchronized(dirtyLedgers) {
            dirtyLedgers.remove(ledgerId);
        }
        synchronized(openLedgers) {
            openLedgers.remove(ledgerId);
        }
    }

}
