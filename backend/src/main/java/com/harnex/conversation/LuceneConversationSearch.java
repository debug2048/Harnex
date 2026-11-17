package com.harnex.conversation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.LoggerFactory;

/** Rebuildable search projection. The cursor is committed inside the Lucene commit that contains its documents. */
public final class LuceneConversationSearch implements ConversationSearch, AutoCloseable {
    private static final String ANALYZER_VERSION = "conversation-cjk-1";
    private static final int MAX_SEARCH_SNAPSHOTS = 32;
    private final LinkedHashMap<String, SearchSnapshot> snapshots = new LinkedHashMap<>(16, .75f, true);
    private final ConversationRepository repository;
    private final String scopeId;
    private final CJKAnalyzer analyzer = new CJKAnalyzer();
    private final ScheduledExecutorService scheduler;
    private FSDirectory directory;
    private IndexWriter writer;
    private SearcherManager searchers;
    private long indexedThrough;
    private State state = State.REBUILDING;
    private boolean closed;
    private record SearchSnapshot(String id, String query, String conversationId, long indexedThrough,
        State state, IndexSearcher searcher) {}

    public LuceneConversationSearch(Path scopeDirectory, String scopeId, ConversationRepository repository) {
        this(scopeDirectory, scopeId, repository, true);
    }
    public LuceneConversationSearch(Path scopeDirectory, String scopeId, ConversationRepository repository, boolean background) {
        this.repository = repository;
        this.scopeId = scopeId;
        try {
            directory = FSDirectory.open(scopeDirectory.resolve("indexes/conversations"));
            boolean exists = DirectoryReader.indexExists(directory);
            boolean rebuild = !exists;
            if (exists) {
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    Map<String, String> metadata = reader.getIndexCommit().getUserData();
                    rebuild = !ANALYZER_VERSION.equals(metadata.get("analyzer")) || !scopeId.equals(metadata.get("scope"));
                    if (!rebuild) indexedThrough = Long.parseLong(metadata.getOrDefault("through", "0"));
                }
            }
            writer = new IndexWriter(directory, new IndexWriterConfig(analyzer).setOpenMode(
                rebuild ? IndexWriterConfig.OpenMode.CREATE : IndexWriterConfig.OpenMode.CREATE_OR_APPEND));
            searchers = new SearcherManager(writer, null);
            if (rebuild) indexedThrough = 0;
            catchUp(500);
        } catch (Exception failure) {
            state = State.UNAVAILABLE;
            LoggerFactory.getLogger(getClass()).warn("Conversation search is unavailable; history remains readable: {}", failure.toString());
        }
        scheduler = background ? Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "conversation-search"); thread.setDaemon(true); return thread;
        }) : null;
        if (scheduler != null) scheduler.scheduleWithFixedDelay(() -> {
            synchronized (this) { if (!closed && writer != null && searchers != null) catchUp(500); }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override public synchronized SearchPage search(String scope, String query, String conversationId, String cursor, int limit) {
        if (!scopeId.equals(scope)) throw new ConversationException("scope_mismatch", "search belongs to another scope");
        if (limit < 1 || limit > 100 || query == null || query.isBlank() || query.length() > 1000) {
            throw new ConversationException("bad_request", "search requires a query and a limit of 1..100");
        }
        if (closed || writer == null || searchers == null) return new SearchPage(List.of(), null, State.UNAVAILABLE, indexedThrough);
        ScoreDoc after = null;
        SearchSnapshot snapshot = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = cursor.split(":", -1);
                if (parts.length != 2 && parts.length != 3) throw new NumberFormatException();
                snapshot = snapshots.get(parts[0]);
                if (snapshot == null || !snapshot.query().equals(query)
                    || !Objects.equals(snapshot.conversationId(), conversationId)) {
                    throw new ConversationException("search_changed", "Search snapshot expired or query changed; restart this search");
                }
                if (parts.length == 2) {
                    if (!"start".equals(parts[1])) throw new NumberFormatException();
                } else {
                    int doc = Integer.parseInt(parts[1]);
                    float score = Float.parseFloat(parts[2]);
                    if (doc < 0 || doc >= snapshot.searcher().getIndexReader().maxDoc() || !Float.isFinite(score)) throw new NumberFormatException();
                    after = new ScoreDoc(doc, score);
                }
            } catch (NumberFormatException invalid) { throw new ConversationException("invalid_cursor", "invalid search cursor"); }
        } else {
            catchUp(500);
            if (state == State.UNAVAILABLE) return new SearchPage(List.of(), null, state, indexedThrough);
        }
        IndexSearcher searcher = null;
        boolean retained = snapshot != null;
        try {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(new String[] { "title", "content" }, analyzer);
            parser.setDefaultOperator(QueryParser.Operator.AND);
            Query text;
            try { text = parser.parse(query); }
            catch (ParseException invalidSyntax) { text = parser.parse(QueryParser.escape(query)); }
            BooleanQuery.Builder selected = new BooleanQuery.Builder()
                .add(new TermQuery(new Term("scope", scopeId)), BooleanClause.Occur.FILTER)
                .add(text, BooleanClause.Occur.MUST);
            if (conversationId != null) selected.add(new TermQuery(new Term("conversation", conversationId)), BooleanClause.Occur.FILTER);
            searcher = snapshot == null ? searchers.acquire() : snapshot.searcher();
            var results = searcher.searchAfter(after, selected.build(), limit + 1);
            List<Hit> hits = new ArrayList<>();
            List<ScoreDoc> hitPositions = new ArrayList<>();
            int position = 0;
            ScoreDoc scanned = after;
            while (position < results.scoreDocs.length && hits.size() < limit) {
                scanned = results.scoreDocs[position++];
                Document doc = searcher.storedFields().document(scanned.doc);
                ConversationMeta meta = repository.loadConversation(scopeId, doc.get("conversation")).orElse(null);
                if (meta == null) continue; // The index is never the deletion or ownership authority.
                String messageId = doc.get("message");
                String recordId = doc.get("executionRecord");
                long revision = Long.parseLong(doc.get("revision"));
                if (messageId != null) {
                    MessageRecord actual = repository.findMessage(scopeId, meta.conversationId(), messageId).orElse(null);
                    if (actual == null || actual.revision() != revision) continue;
                }
                if (recordId != null) {
                    ExecutionSearchRecord actual = repository.findExecutionRecord(scopeId, meta.conversationId(), recordId).orElse(null);
                    if (actual == null || actual.deleted() || actual.sourceRevision() != revision) continue;
                }
                hits.add(new Hit(meta.conversationId(), messageId, doc.get("run"), doc.get("toolCall"), doc.get("kind"),
                    meta.title(), doc.get("snippet"), revision, recordId));
                hitPositions.add(scanned);
            }
            boolean more = position < results.scoreDocs.length || results.scoreDocs.length == limit + 1;
            // Retain only the reader, never all hits or bodies. A later tool-result commit must not invalidate paging.
            if (snapshot == null && (!hits.isEmpty() || more)) {
                snapshot = new SearchSnapshot(UUID.randomUUID().toString().replace("-", ""), query,
                    conversationId, indexedThrough, state, searcher);
                snapshots.put(snapshot.id(), snapshot); retained = true;
                while (snapshots.size() > MAX_SEARCH_SNAPSHOTS) {
                    var oldest = snapshots.entrySet().iterator();
                    SearchSnapshot evicted = oldest.next().getValue(); oldest.remove();
                    searchers.release(evicted.searcher());
                }
            }
            String next = more && scanned != null && snapshot != null ? cursor(snapshot, scanned) : null;
            List<String> itemCursors = new ArrayList<>();
            if (snapshot != null) for (var positionAfterHit : hitPositions) itemCursors.add(cursor(snapshot, positionAfterHit));
            return new SearchPage(List.copyOf(hits), next, snapshot == null ? state : snapshot.state(),
                snapshot == null ? indexedThrough : snapshot.indexedThrough(), snapshot == null ? null : cursor(snapshot, after),
                List.copyOf(itemCursors));
        } catch (ConversationException failure) { throw failure; }
        catch (Exception failure) {
            state = State.UNAVAILABLE;
            return new SearchPage(List.of(), null, state, indexedThrough);
        } finally {
            if (searcher != null && !retained) try { searchers.release(searcher); } catch (IOException ignored) { }
        }
    }

    private static String cursor(SearchSnapshot snapshot, ScoreDoc after) {
        return snapshot.id() + (after == null ? ":start" : ":" + after.doc + ":" + after.score);
    }
    private void releaseSnapshots() {
        for (SearchSnapshot snapshot : snapshots.values()) {
            try { searchers.release(snapshot.searcher()); } catch (IOException ignored) { }
        }
        snapshots.clear();
    }

    private void catchUp(int limit) {
        long previous = indexedThrough;
        try {
            var changes = repository.readCommittedChanges(scopeId, indexedThrough, limit);
            for (var change : changes.items()) apply(change);
            long next = changes.nextCursor();
            if (next != indexedThrough) {
                writer.setLiveCommitData(Map.of("through", Long.toString(next), "scope", scopeId, "analyzer", ANALYZER_VERSION).entrySet());
                writer.commit();
                searchers.maybeRefreshBlocking();
                indexedThrough = next;
            }
            state = changes.hasMore() ? State.CATCHING_UP : State.READY;
        } catch (Exception failure) {
            indexedThrough = previous;
            state = State.UNAVAILABLE;
            LoggerFactory.getLogger(getClass()).warn("Conversation index update failed at change {}: {}", previous, failure.toString());
        }
    }

    private void apply(ConversationRepository.CommittedChange change) throws IOException {
        CommitReceipt receipt = change.receipt();
        ConversationMeta meta = repository.loadConversation(scopeId, receipt.conversationId()).orElse(null);
        if (meta == null) { writer.deleteDocuments(new Term("conversation", receipt.conversationId())); return; }
        Document summary = document(meta, "conversation:" + meta.conversationId(), "CONVERSATION", meta.metadataRevision());
        summary.add(new TextField("title", meta.title(), Field.Store.NO));
        summary.add(new StoredField("snippet", meta.preview()));
        writer.updateDocument(new Term("id", "conversation:" + meta.conversationId()), summary);
        for (String id : receipt.messageIds()) {
            MessageRecord message = repository.findMessage(scopeId, meta.conversationId(), id).orElse(null);
            String key = "message:" + meta.conversationId() + ":" + id;
            if (message == null || message.plainText().isBlank()) { writer.deleteDocuments(new Term("id", key)); continue; }
            Document doc = document(meta, key, "MESSAGE", message.revision());
            doc.add(new StoredField("message", id));
            if (message.runId() != null) doc.add(new StoredField("run", message.runId()));
            content(doc, message.plainText());
            writer.updateDocument(new Term("id", key), doc);
        }
        for (String id : change.executionRecordIds()) {
            ExecutionSearchRecord record = repository.findExecutionRecord(scopeId, meta.conversationId(), id).orElse(null);
            String key = "execution:" + meta.conversationId() + ":" + id;
            if (record == null || record.deleted()) { writer.deleteDocuments(new Term("id", key)); continue; }
            Document doc = document(meta, key, "TOOL_RESULT", record.sourceRevision());
            doc.add(new StoredField("executionRecord", id));
            if (record.runId() != null) doc.add(new StoredField("run", record.runId()));
            if (record.toolCallId() != null) doc.add(new StoredField("toolCall", record.toolCallId()));
            if (record.title() != null) doc.add(new TextField("title", record.title(), Field.Store.NO));
            content(doc, record.text() == null ? "" : record.text());
            writer.updateDocument(new Term("id", key), doc);
        }
    }
    private Document document(ConversationMeta meta, String id, String kind, long revision) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.NO));
        doc.add(new StringField("scope", scopeId, Field.Store.NO));
        doc.add(new StringField("conversation", meta.conversationId(), Field.Store.YES));
        doc.add(new StoredField("kind", kind));
        doc.add(new StoredField("revision", Long.toString(revision)));
        return doc;
    }
    private static void content(Document doc, String text) {
        doc.add(new TextField("content", text, Field.Store.NO));
        doc.add(new StoredField("snippet", text.substring(0, Math.min(400, text.length()))));
    }

    /** Rebuild only the disposable index; the source journal and history are never changed. */
    public synchronized void rebuild() {
        if (closed) throw new IllegalStateException("search is closed");
        try {
            releaseSnapshots();
            if (searchers != null) searchers.close();
            if (writer != null) writer.close();
            writer = new IndexWriter(directory, new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE));
            searchers = new SearcherManager(writer, null);
            indexedThrough = 0;
            state = State.REBUILDING;
            catchUp(500);
        } catch (IOException failure) {
            state = State.UNAVAILABLE;
            throw new ConversationException("search_unavailable", "search index could not be rebuilt", failure);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (scheduler != null) scheduler.shutdownNow();
        releaseSnapshots();
        try { if (searchers != null) searchers.close(); } catch (IOException ignored) { }
        try { if (writer != null) writer.close(); } catch (IOException ignored) { }
        try { if (directory != null) directory.close(); } catch (IOException ignored) { }
        analyzer.close();
    }
}
