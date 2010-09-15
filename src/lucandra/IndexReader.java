/**
 * Copyright 2009 T Jake Luciani
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package lucandra;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.db.SliceByNamesReadCommand;
import org.apache.cassandra.db.SliceFromReadCommand;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.SimpleAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldSelector;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.index.TermFreqVector;
import org.apache.lucene.index.TermPositions;
import org.apache.lucene.index.TermVectorMapper;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.OpenBitSet;

import solandra.SolandraFieldSelector;

public class IndexReader extends org.apache.lucene.index.IndexReader {

    private final static int numDocs = CassandraUtils.maxDocsPerShard;
    private final static byte defaultNorm = Similarity.encodeNorm(1.0f);
    
    private final static Directory mockDirectory = new RAMDirectory();
    static {

        try {
            new IndexWriter(mockDirectory, new SimpleAnalyzer(), true, MaxFieldLength.LIMITED);
        } catch (CorruptIndexException e) {
            throw new RuntimeException(e);
        } catch (LockObtainFailedException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String indexName;

    private final ThreadLocal<Map<Integer, Document>> documentCache = new ThreadLocal<Map<Integer, Document>>();
    private final ThreadLocal<Map<Term, LucandraTermEnum>> termEnumCache = new ThreadLocal<Map<Term, LucandraTermEnum>>();
    private final ThreadLocal<Map<String, byte[]>> fieldNorms = new ThreadLocal<Map<String, byte[]>>();
    private final ThreadLocal<OpenBitSet>  docsHit = new ThreadLocal<OpenBitSet>();
    
    private static final Logger logger = Logger.getLogger(IndexReader.class);

    public IndexReader(String name) {
        super();
        this.indexName = name;
    }

    public synchronized IndexReader reopen() throws CorruptIndexException, IOException {
        clearCache();

        return this;
    }

    @Override
    public synchronized IndexReader reopen(boolean openReadOnly) throws CorruptIndexException, IOException {
        return reopen();
    }

    @Override
    public synchronized IndexReader reopen(IndexCommit commit) throws CorruptIndexException, IOException {
        return reopen();
    }

    public void clearCache() {

        if (termEnumCache.get() != null)
            termEnumCache.get().clear();
        if (documentCache.get() != null)
            documentCache.get().clear();
        if (fieldNorms.get() != null)
            fieldNorms.get().clear();
        if (docsHit.get() != null)
            docsHit.get().clear(0, numDocs);
        
    }

    protected void doClose() throws IOException {
        clearCache();
    }

    protected void doCommit() throws IOException {
        clearCache();
    }

    protected void doDelete(int arg0) throws CorruptIndexException, IOException {

    }

    protected void doSetNorm(int arg0, String arg1, byte arg2) throws CorruptIndexException, IOException {

    }

    protected void doUndeleteAll() throws CorruptIndexException, IOException {

    }

    public int docFreq(Term term) throws IOException {

        LucandraTermEnum termEnum = getTermEnumCache().get(term);
        if (termEnum == null) {

            long start = System.currentTimeMillis();

            termEnum = new LucandraTermEnum(this);
            termEnum.skipTo(term);

            long end = System.currentTimeMillis();

            logger.debug("docFreq() took: " + (end - start) + "ms");

            getTermEnumCache().put(term, termEnum);
        }

        return termEnum.docFreq();
    }

    public Document document(int docNum, FieldSelector selector) throws CorruptIndexException, IOException {

        Document doc = getDocumentCache().get(docNum);

        if (doc != null) {
            logger.debug("Found doc in cache");
            return doc;
        }

        List<byte[]> fieldNames = null;

        Map<Integer, byte[]> keyMap = new HashMap<Integer, byte[]>();
        keyMap.put(docNum, CassandraUtils.hashKeyBytes(indexName.getBytes(), CassandraUtils.delimeterBytes, Integer.toHexString(docNum).getBytes()));

        // Special field selector used to carry list of other docIds to cache in
        // Parallel for Solr Performance
        if (selector != null && selector instanceof SolandraFieldSelector) {

            List<Integer> otherDocIds = ((SolandraFieldSelector) selector).getOtherDocsToCache();
            fieldNames = ((SolandraFieldSelector) selector).getFieldNames();

            logger.debug("Going to bulk load " + otherDocIds.size() + " documents");

            for (Integer otherDocNum : otherDocIds) {
                if (otherDocNum == docNum)
                    continue;

                if (getDocumentCache().containsKey(otherDocNum))
                    continue;

                byte[] docKey = Integer.toHexString(otherDocNum).getBytes();

                if (docKey == null)
                    continue;

                keyMap.put(otherDocNum, CassandraUtils.hashKeyBytes(indexName.getBytes(), CassandraUtils.delimeterBytes, docKey));
            }
        }

        ColumnParent columnParent = new ColumnParent();
        columnParent.setColumn_family(CassandraUtils.docColumnFamily);

        long start = System.currentTimeMillis();

        try {

            List<Row> rows = null;
            List<ReadCommand> readCommands = new ArrayList<ReadCommand>();
            for (byte[] key : keyMap.values()) {

                if (fieldNames == null || fieldNames.size() == 0 ) {
                    // get all columns ( except this skips meta info )
                    readCommands.add(new SliceFromReadCommand(CassandraUtils.keySpace, key, columnParent, new byte[] {}, CassandraUtils.finalTokenBytes, false, Integer.MAX_VALUE));
                } else {
                    readCommands.add(new SliceByNamesReadCommand(CassandraUtils.keySpace, key, columnParent, fieldNames));
                }
            }

            rows = StorageProxy.readProtocol(readCommands, ConsistencyLevel.ONE);

            // allow lookup by row
            Map<String, Row> rowMap = new HashMap<String, Row>(keyMap.size());
            for (Row row : rows) {
                rowMap.put(new String(row.key.key, "UTF-8"), row);
            }

            for (Map.Entry<Integer, byte[]> key : keyMap.entrySet()) {
                Document cacheDoc = new Document();

                Row row = rowMap.get(new String(key.getValue(), "UTF-8"));

                if (row == null) {
                    logger.warn("Missing document in multiget_slice for: " + key.getValue());
                    continue;
                }

                for (IColumn col : row.cf.getSortedColumns()) {

                    Field field = null;
                    String fieldName = new String(col.name());

                    // Incase __META__ slips through
                    if (Arrays.equals(col.name(), CassandraUtils.documentMetaField.getBytes())) {
                        logger.debug("Filtering out __META__ key");
                        continue;
                    }

                    byte[] value;

                    if (col.value()[col.value().length - 1] != Byte.MAX_VALUE && col.value()[col.value().length - 1] != Byte.MIN_VALUE) {
                        throw new CorruptIndexException("Lucandra field is not properly encoded: " + docNum + "(" + fieldName + ")");

                    } else if (col.value()[col.value().length - 1] == Byte.MAX_VALUE) { // Binary
                        value = new byte[col.value().length - 1];
                        System.arraycopy(col.value(), 0, value, 0, col.value().length - 1);

                        field = new Field(fieldName, value, Store.YES);
                        cacheDoc.add(field);
                    } else if (col.value()[col.value().length - 1] == Byte.MIN_VALUE) { // String
                        value = new byte[col.value().length - 1];
                        System.arraycopy(col.value(), 0, value, 0, col.value().length - 1);

                        // Check for multi-fields
                        String fieldString = new String(value, "UTF-8");

                        if (fieldString.indexOf(CassandraUtils.delimeter) >= 0) {
                            StringTokenizer tok = new StringTokenizer(fieldString, CassandraUtils.delimeter);
                            while (tok.hasMoreTokens()) {
                                field = new Field(fieldName, tok.nextToken(), Store.YES, Index.ANALYZED);
                                cacheDoc.add(field);
                            }
                        } else {

                            field = new Field(fieldName, fieldString, Store.YES, Index.ANALYZED);
                            cacheDoc.add(field);
                        }
                    }
                }

                // Mark the required doc
                if (key.getKey().equals(docNum))
                    doc = cacheDoc;

                getDocumentCache().put(key.getKey(), cacheDoc);
            }

            long end = System.currentTimeMillis();

            logger.debug("Document read took: " + (end - start) + "ms");

            return doc;

        } catch (Exception e) {
            throw new IOException(e);
        }

    }

    @Override
    public Collection getFieldNames(FieldOption fieldOption) {
        return Arrays.asList(new String[] {});
    }

    @Override
    public TermFreqVector getTermFreqVector(int docNum, String field) throws IOException {

        TermFreqVector termVector = new lucandra.TermFreqVector(indexName, field, docNum);

        return termVector;
    }

    @Override
    public void getTermFreqVector(int arg0, TermVectorMapper arg1) throws IOException {
        throw new RuntimeException();
    }

    @Override
    public void getTermFreqVector(int arg0, String arg1, TermVectorMapper arg2) throws IOException {

        throw new RuntimeException();

    }

    @Override
    public TermFreqVector[] getTermFreqVectors(int arg0) throws IOException {
        throw new RuntimeException();
    }

    @Override
    public boolean hasDeletions() {

        return false;
    }

    @Override
    public boolean isDeleted(int arg0) {

        return false;
    }

    @Override
    public int maxDoc() {
        // if (numDocs == null)
        // numDocs();

        return numDocs + 1;
    }

    @Override
    public byte[] norms(String field) throws IOException {
        return getFieldNorms().get(field);
    }

    @Override
    public void norms(String arg0, byte[] arg1, int arg2) throws IOException {

        throw new RuntimeException("This operation is not supported");

    }

    @Override
    public int numDocs() {

        return numDocs;
    }

    @Override
    public TermDocs termDocs(Term term) throws IOException {
       
        if(term == null)
            return new LucandraAllTermDocs(this);
        
        return super.termDocs(term);
    }

    @Override
    public TermDocs termDocs() throws IOException {
        return new LucandraTermDocs(this);
    }

    @Override
    public TermPositions termPositions() throws IOException {
        return new LucandraTermDocs(this);
    }

    @Override
    public TermEnum terms() throws IOException {
        return new LucandraTermEnum(this);
    }

    @Override
    public TermEnum terms(Term term) throws IOException {

        LucandraTermEnum termEnum = getTermEnumCache().get(term);

        if (termEnum == null)
            termEnum = new LucandraTermEnum(this);

        if (!termEnum.skipTo(term)) // if found in the cache then reset,
            // otherwise init.
            termEnum = null;

        return termEnum;
    }

    public void addDocumentNormalizations(Collection<IColumn> allDocs, String field) {

        Map<String, byte[]> fieldNorms = getFieldNorms();
       
        byte[] norms = fieldNorms.get(field);

        for (IColumn docInfo : allDocs) {

            int idx = CassandraUtils.readVInt(docInfo.name());

            if (idx > numDocs)
                throw new IllegalStateException("numDocs reached");

            getDocsHit().set(idx);
            
            Byte norm = null;
            IColumn normCol = docInfo.getSubColumn(CassandraUtils.normsKeyBytes);
            if (normCol != null) {
                if (normCol.value().length != 1)
                    throw new IllegalStateException("Norm for field " + field + " must be a single byte");

                norm = normCol.value()[0];
            }

            if (norm == null)
                norm = defaultNorm;

            if (norms == null) 
                norms = new byte[1024];                         

            while(norms.length <= idx && norms.length < numDocs ){
                byte[] _norms = new byte[(norms.length * 2) < numDocs ? (norms.length * 2) : (numDocs + 1)];
                System.arraycopy(norms, 0, _norms, 0, norms.length);
                norms = _norms;           
            }

            
            // find next empty position
            norms[idx] = norm;          
        }
        
        fieldNorms.put(field, norms);
    }

    public String getIndexName() {
        return indexName;
    }
    
    

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public LucandraTermEnum checkTermCache(Term term) {
        return getTermEnumCache().get(term);
    }

    public void addTermEnumCache(Term term, LucandraTermEnum termEnum) {
        getTermEnumCache().put(term, termEnum);
    }

    @Override
    public Directory directory() {
        clearCache();

        return mockDirectory;
    }

    @Override
    public long getVersion() {
        return Long.MAX_VALUE;
    }

    @Override
    public boolean isOptimized() {
        return true;
    }

    @Override
    public boolean isCurrent() {
        return true;
    }

    private Map<Term, LucandraTermEnum> getTermEnumCache() {
        Map<Term, LucandraTermEnum> c = termEnumCache.get();

        if (c == null) {
            c = new HashMap<Term, LucandraTermEnum>();
            termEnumCache.set(c);
        }

        return c;
    }

    private Map<Integer, Document> getDocumentCache() {
        Map<Integer, Document> c = documentCache.get();

        if (c == null) {
            c = new HashMap<Integer, Document>();
            documentCache.set(c);
        }

        return c;
    }

    public Map<String, byte[]> getFieldNorms() {
        Map<String, byte[]> c = fieldNorms.get();

        if (c == null) {
            c = new HashMap<String, byte[]>(10);
            fieldNorms.set(c);
        }

        return c;
    }
    
    public OpenBitSet getDocsHit(){
        OpenBitSet h = docsHit.get();
        
        if(h == null){
            h = new OpenBitSet(numDocs);
            
            docsHit.set(h);
        }
        
        return h;
    }
    

}
