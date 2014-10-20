/* 
 * Copyright 2014 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more
 * contributor license agreements.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.aerospike.hadoop.mapreduce;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;

import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.AerospikeException.ScanTerminated;
import com.aerospike.client.Key;
import com.aerospike.client.policy.QueryPolicy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.query.Filter;
import com.aerospike.client.query.RecordSet;
import com.aerospike.client.query.ResultSet;
import com.aerospike.client.query.Statement;
import com.aerospike.client.Record;
import com.aerospike.client.ScanCallback;

public class AerospikeRecordReader
    extends RecordReader<AerospikeKey, AerospikeRecord>
    implements org.apache.hadoop.mapred.RecordReader<AerospikeKey,
                                                         AerospikeRecord> {

    private class KeyRecPair {
        public AerospikeKey key;
        public AerospikeRecord rec;
        public KeyRecPair(AerospikeKey key, AerospikeRecord rec) {
            this.key = key;
            this.rec = rec;
        }
    }

    private static final Log log =
        LogFactory.getLog(AerospikeRecordReader.class);

    private ASSCanReader scanReader = null;
    private ASQueryReader queryReader = null;

    private ArrayBlockingQueue<KeyRecPair> queue = 
        new ArrayBlockingQueue<KeyRecPair>(16 * 1024);

    private boolean isFinished = false;
    private boolean isError = false;
    private boolean isRunning = false;
    private String numrangeBin;
    private long numrangeBegin;
    private long numrangeEnd;
    
    private AerospikeKey currentKey;
    private AerospikeRecord currentValue;

    public class CallBack implements ScanCallback {
        @Override
        public void scanCallback(Key key, Record record)
            throws AerospikeException {
            try {
                queue.put(new KeyRecPair(new AerospikeKey(key),
                                         new AerospikeRecord(record)));
            } catch (Exception ex) {
                throw new ScanTerminated(ex);
            }
        }
    }

    public class ASSCanReader extends java.lang.Thread {

        String node;
        String host;
        int port;
        String namespace;
        String setName;

        ASSCanReader(String node, String host, int port,
                     String ns, String setName) {
            this.node = node;
            this.host = host;
            this.port = port;
            this.namespace = ns;
            this.setName = setName;
        }

        public void run() {
            try {
                AerospikeClient client = new AerospikeClient(host, port);
                try {
                    ScanPolicy scanPolicy = new ScanPolicy();
                    CallBack cb = new CallBack();
                    log.info("scan starting");
                    isRunning = true;
                    client.scanNode(scanPolicy, node, namespace, setName, cb);
                    isFinished = true;
                    log.info("scan finished");
                }
                finally {
                    client.close();
                }
            }
            catch (Exception ex) {
                log.error("exception in ASSCanReader.run: " + ex);
                isError = true;
                return;
            }
        }
    }

    public class ASQueryReader extends java.lang.Thread {

        String node;
        String host;
        int port;
        String namespace;
        String setName;
        String numrangeBin;
        long numrangeBegin;
        long numrangeEnd;

        ASQueryReader(String node, String host, int port,
                      String ns, String setName, String numrangeBin,
                      long numrangeBegin, long numrangeEnd) {
            this.node = node;
            this.host = host;
            this.port = port;
            this.namespace = ns;
            this.setName = setName;
            this.numrangeBin = numrangeBin;
            this.numrangeBegin = numrangeBegin;
            this.numrangeEnd = numrangeEnd;
        }

        public void run() {
            try {
                AerospikeClient client = new AerospikeClient(host, port);
                try {
                    Statement stmt = new Statement();
                    stmt.setNamespace(namespace);
                    stmt.setSetName(setName);
                    stmt.setFilters(Filter.range(numrangeBin,
                                                 numrangeBegin,
                                                 numrangeEnd));
                    log.info(String.format("range %s %d %d",
                                           numrangeBin,
                                           numrangeBegin,
                                           numrangeEnd));
                    stmt.setBinNames(numrangeBin);
                    QueryPolicy queryPolicy = new QueryPolicy();
                    RecordSet rs = client.query(queryPolicy, stmt);
                    isRunning = true;
                    try {
                        log.info("query starting");
                        while (rs.next()) {
                            Key key = rs.getKey();
                            Record record = rs.getRecord();
                            queue.put(new KeyRecPair(new AerospikeKey(key),
                                                     new AerospikeRecord(record)));
                        }
                    }
                    finally {
                        rs.close();
                        isFinished = true;
                        log.info("query finished");
                    }
                }
                finally {
                    client.close();
                }
            }
            catch (Exception ex) {
                isError = true;
                return;
            }
        }
    }

    public AerospikeRecordReader()
        throws IOException {
        log.info("NEW CTOR");
    }

    public AerospikeRecordReader(AerospikeSplit split)
        throws IOException {
        log.info("OLD CTOR");
        init(split);
    }

    public void init(AerospikeSplit split)
        throws IOException {
        final String type = split.getType();
        final String node = split.getNode();
        final String host = split.getHost();
        final int port = split.getPort();
        final String namespace = split.getNameSpace();
        final String setName = split.getSetName();
        this.numrangeBin = split.getNumRangeBin();
        this.numrangeBegin = split.getNumRangeBegin();
        this.numrangeEnd = split.getNumRangeEnd();

        if (type.equals("scan")) {
            scanReader = new ASSCanReader(node, host, port, namespace, setName);
            scanReader.start();
        } else if (type.equals("numrange")) {
            queryReader = new ASQueryReader(node, host, port, namespace,
                                            setName, numrangeBin,
                                            numrangeBegin, numrangeEnd);
            queryReader.start();
        }

        log.info("node: " + node);
    }

    public AerospikeKey createKey() { return new AerospikeKey(); }

    public AerospikeRecord createValue() { return new AerospikeRecord(); }

    protected AerospikeKey setCurrentKey(AerospikeKey oldApiKey,
                                         AerospikeKey newApiKey,
                                         AerospikeKey keyval) {

        if (oldApiKey == null) {
            oldApiKey = new AerospikeKey();
            oldApiKey.set(keyval);
        }

        // new API might not be used
        if (newApiKey != null) {
            newApiKey.set(keyval);
        }
        return oldApiKey;
    }

    protected AerospikeRecord setCurrentValue(AerospikeRecord oldApiVal,
                                              AerospikeRecord newApiVal,
                                              AerospikeRecord val) {
        if (oldApiVal == null) {
            oldApiVal = new AerospikeRecord();
            oldApiVal.set(val);
        }

        // new API might not be used
        if (newApiVal != null) {
            newApiVal.set(val);
        }
        return oldApiVal;
    }

    public synchronized boolean next(AerospikeKey key, AerospikeRecord value)
        throws IOException {

        final int waitMSec = 1000;
        int trials = 5;

        try {
            KeyRecPair pair;
            while (true) {
                if (isError)
                    return false;
                
                if (!isRunning) {
                    Thread.sleep(100);
                    continue;
                }
            
                if (!isFinished && queue.size() == 0) {
                    if (trials == 0) {
                        log.error("SCAN TIMEOUT");
                        return false;
                    }
                    log.info("queue empty: waiting...");
                    Thread.sleep(waitMSec);
                    trials--;
                } else if (isFinished && queue.size() == 0) {
                    return false;
                } else if (queue.size() != 0) {
                    pair = queue.take();
                    break;
                }
            }

            // log.info("key=" + pair.key + ", val=" + pair.rec);

            currentKey = setCurrentKey(currentKey, key, pair.key);
            currentValue = setCurrentValue(currentValue, value, pair.rec);
        }
        catch (Exception ex) {
            log.error("exception in AerospikeRecordReader.next: " + ex);
            throw new IOException("exception in AerospikeRecordReader.next", ex);
        }
        return true;
    }

    public float getProgress() {
        if (isFinished)
            return 1.0f;
        else
            return 0.0f;
    }

    public synchronized long getPos() throws IOException {
        return 0;
    }

    public synchronized void close() throws IOException {
        if (scanReader != null) {
            try {
                scanReader.join();
            }
            catch (Exception ex) {
                throw new IOException("exception in AerospikeRecordReader.close",
                                      ex);
            }
            scanReader = null;
        }
        if (queryReader != null) {
            try {
                queryReader.join();
            }
            catch (Exception ex) {
                throw new IOException("exception in AerospikeRecordReader.close",
                                      ex);
            }
            queryReader = null;
        }
    }

    // ---------------- NEW API ----------------

    @Override
    public void initialize(InputSplit split, TaskAttemptContext context)
        throws IOException {
        log.info("INITIALIZE");
        init((AerospikeSplit) split);
    }

    @Override
    public boolean nextKeyValue() throws IOException {
        // new API call routed to old API
        if (currentKey == null) {
            currentKey = createKey();
        }
        if (currentValue == null) {
            currentValue = createValue();
        }

        // FIXME: does the new API mandate a new instance each time (?)
        return next(currentKey, currentValue);
    }

    @Override
    public AerospikeKey getCurrentKey() throws IOException {
        return currentKey;
    }

    @Override
    public AerospikeRecord getCurrentValue() {
        return currentValue;
    }
}

// Local Variables:
// mode: java
// c-basic-offset: 4
// tab-width: 4
// indent-tabs-mode: nil
// End:
// vim: softtabstop=4:shiftwidth=4:expandtab
