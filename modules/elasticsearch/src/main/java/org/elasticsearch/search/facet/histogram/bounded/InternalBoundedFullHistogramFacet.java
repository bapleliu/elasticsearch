/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.facet.histogram.bounded;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.histogram.HistogramFacet;
import org.elasticsearch.search.facet.histogram.InternalHistogramFacet;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * @author kimchy (shay.banon)
 */
public class InternalBoundedFullHistogramFacet extends InternalHistogramFacet {

    private static final String STREAM_TYPE = "fBdHistogram";

    public static void registerStreams() {
        Streams.registerStream(STREAM, STREAM_TYPE);
    }

    static Stream STREAM = new Stream() {
        @Override public Facet readFacet(String type, StreamInput in) throws IOException {
            return readHistogramFacet(in);
        }
    };

    @Override public String streamType() {
        return STREAM_TYPE;
    }


    /**
     * A histogram entry representing a single entry within the result of a histogram facet.
     */
    public static class FullEntry implements Entry {
        long key;
        long count;
        long totalCount;
        double total;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;

        public FullEntry(long key, long count, double min, double max, long totalCount, double total) {
            this.key = key;
            this.count = count;
            this.min = min;
            this.max = max;
            this.totalCount = totalCount;
            this.total = total;
        }

        @Override public long key() {
            return key;
        }

        @Override public long getKey() {
            return key();
        }

        @Override public long count() {
            return count;
        }

        @Override public long getCount() {
            return count();
        }

        @Override public double total() {
            return total;
        }

        @Override public double getTotal() {
            return total();
        }

        @Override public long totalCount() {
            return totalCount;
        }

        @Override public long getTotalCount() {
            return this.totalCount;
        }

        @Override public double mean() {
            return total / totalCount;
        }

        @Override public double getMean() {
            return total / totalCount;
        }

        @Override public double min() {
            return this.min;
        }

        @Override public double getMin() {
            return this.min;
        }

        @Override public double max() {
            return this.max;
        }

        @Override public double getMax() {
            return this.max;
        }
    }

    private String name;

    private ComparatorType comparatorType;

    Object[] entries;
    transient boolean entriesSafe;
    int size;
    long interval;
    long offset;
    boolean normalized;

    private InternalBoundedFullHistogramFacet() {
    }

    public InternalBoundedFullHistogramFacet(String name, ComparatorType comparatorType, long interval, long offset, int size, Object[] entries) {
        this.name = name;
        this.comparatorType = comparatorType;
        this.interval = interval;
        this.offset = offset;
        this.size = size;
        this.entries = entries;
        this.entriesSafe = false;
    }

    @Override public String name() {
        return this.name;
    }

    @Override public String getName() {
        return name();
    }

    @Override public String type() {
        return TYPE;
    }

    @Override public String getType() {
        return type();
    }

    @Override public List<FullEntry> entries() {
        normalize();
        return (List) Arrays.asList(entries);
    }

    @Override public List<FullEntry> getEntries() {
        return entries();
    }

    @Override public Iterator<Entry> iterator() {
        return (Iterator) entries().iterator();
    }

    @Override public Facet reduce(String name, List<Facet> facets) {
        if (facets.size() == 1) {
            // we need to sort it
            InternalBoundedFullHistogramFacet internalFacet = (InternalBoundedFullHistogramFacet) facets.get(0);
            if (!internalFacet.entriesSafe) {
                FullEntry[] newEntries = new FullEntry[size];
                System.arraycopy(internalFacet.entries, 0, newEntries, 0, size);
                internalFacet.entries = newEntries;
                internalFacet.entriesSafe = true;
            }
            if (comparatorType != ComparatorType.KEY) {
                Arrays.sort(internalFacet.entries, (Comparator) comparatorType.comparator());
            }
            return internalFacet;
        }

        Object[] agg = null;
        for (Facet facet : facets) {
            InternalBoundedFullHistogramFacet internalFacet = (InternalBoundedFullHistogramFacet) facet;
            if (internalFacet.entriesSafe) {
                agg = internalFacet.entries;
                break;
            }
        }
        if (agg == null) {
            agg = new FullEntry[size];
        }

        for (Facet facet : facets) {
            InternalBoundedFullHistogramFacet internalFacet = (InternalBoundedFullHistogramFacet) facet;
            if (agg == internalFacet.entries) { // this is the one we copied
                continue;
            }
            for (int i = 0; i < size; i++) {
                FullEntry aggEntry = (FullEntry) agg[i];
                FullEntry entry = (FullEntry) internalFacet.entries[i];
                if (aggEntry == null) {
                    agg[i] = entry;
                } else if (entry != null) {
                    aggEntry.count += entry.count;
                    aggEntry.totalCount += entry.totalCount;
                    aggEntry.total += entry.total;
                    if (entry.min < aggEntry.min) {
                        aggEntry.min = entry.min;
                    }
                    if (entry.max > aggEntry.max) {
                        aggEntry.max = entry.max;
                    }
                }
            }
        }
        InternalBoundedFullHistogramFacet internalFacet = (InternalBoundedFullHistogramFacet) facets.get(0);
        internalFacet.entries = agg;
        internalFacet.entriesSafe = true;

        return internalFacet;
    }

    private void normalize() {
        if (normalized) {
            return;
        }
        normalized = true;
        for (int i = 0; i < size; i++) {
            FullEntry entry = (FullEntry) entries[i];
            if (entry == null) {
                entries[i] = new FullEntry((i * interval) + offset, 0, Double.NaN, Double.NaN, 0, 0);
            } else {
                entry.key = (i * interval) + offset;
            }
        }
    }

    static final class Fields {
        static final XContentBuilderString _TYPE = new XContentBuilderString("_type");
        static final XContentBuilderString ENTRIES = new XContentBuilderString("entries");
        static final XContentBuilderString KEY = new XContentBuilderString("key");
        static final XContentBuilderString COUNT = new XContentBuilderString("count");
        static final XContentBuilderString TOTAL = new XContentBuilderString("total");
        static final XContentBuilderString TOTAL_COUNT = new XContentBuilderString("total_count");
        static final XContentBuilderString MEAN = new XContentBuilderString("mean");
        static final XContentBuilderString MIN = new XContentBuilderString("min");
        static final XContentBuilderString MAX = new XContentBuilderString("max");
    }

    @Override public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(name);
        builder.field(Fields._TYPE, HistogramFacet.TYPE);
        builder.startArray(Fields.ENTRIES);
        for (int i = 0; i < size; i++) {
            FullEntry entry = (FullEntry) entries[i];
            builder.startObject();
            if (normalized) {
                builder.field(Fields.KEY, entry.key());
            } else {
                builder.field(Fields.KEY, (i * interval) + offset);
            }
            if (entry == null) {
                builder.field(Fields.COUNT, 0);
                builder.field(Fields.TOTAL, 0);
                builder.field(Fields.TOTAL_COUNT, 0);
            } else {
                builder.field(Fields.COUNT, entry.count());
                builder.field(Fields.MIN, entry.min());
                builder.field(Fields.MAX, entry.max());
                builder.field(Fields.TOTAL, entry.total());
                builder.field(Fields.TOTAL_COUNT, entry.totalCount());
                builder.field(Fields.MEAN, entry.mean());
            }
            builder.endObject();
        }
        builder.endArray();
        builder.endObject();
        return builder;
    }

    public static InternalBoundedFullHistogramFacet readHistogramFacet(StreamInput in) throws IOException {
        InternalBoundedFullHistogramFacet facet = new InternalBoundedFullHistogramFacet();
        facet.readFrom(in);
        return facet;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        name = in.readUTF();
        comparatorType = ComparatorType.fromId(in.readByte());

        offset = in.readLong();
        interval = in.readVLong();
        size = in.readVInt();
        entriesSafe = true;
        entries = new FullEntry[size];
        for (int i = 0; i < size; i++) {
            if (in.readBoolean()) {
                entries[i] = new FullEntry(i, in.readVLong(), in.readDouble(), in.readDouble(), in.readVLong(), in.readDouble());
            }
        }
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        out.writeUTF(name);
        out.writeByte(comparatorType.id());
        out.writeLong(offset);
        out.writeVLong(interval);
        out.writeVInt(size);
        for (int i = 0; i < size; i++) {
            FullEntry entry = (FullEntry) entries[i];
            if (entry == null) {
                out.writeBoolean(false);
            } else {
                out.writeBoolean(true);
//                out.writeLong(entry.key);
                out.writeVLong(entry.count);
                out.writeDouble(entry.min);
                out.writeDouble(entry.max);
                out.writeVLong(entry.totalCount);
                out.writeDouble(entry.total);
            }
        }
    }
}