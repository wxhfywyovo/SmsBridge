package com.smsbridge.pc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 短信历史：内存里保留最近 300 条，同时追加写入 history.jsonl。 */
public final class HistoryStore {
    private static final int MAX_MEMORY = 300;

    public record Entry(String id, String from, String body, String code, long ts, boolean copied) {
        public Json toJson() {
            Json j = Json.obj()
                    .put("id", id)
                    .put("from", from)
                    .put("body", body)
                    .put("ts", ts)
                    .put("copied", copied);
            if (code != null) {
                j.put("code", code);
            }
            return j;
        }

        public static Entry fromJson(Json j) {
            return new Entry(
                    j.getString("id", ""),
                    j.getString("from", ""),
                    j.getString("body", ""),
                    j.getString("code", null),
                    j.getLong("ts", 0L),
                    j.getBool("copied", false));
        }
    }

    private final Path file;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final LinkedHashSet<String> ids = new LinkedHashSet<>();

    public HistoryStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized boolean contains(String id) {
        return id != null && ids.contains(id);
    }

    public synchronized void add(Entry entry) {
        if (entry == null || entry.id().isEmpty() || ids.contains(entry.id())) {
            return;
        }
        entries.add(entry);
        ids.add(entry.id());
        while (entries.size() > MAX_MEMORY) {
            Entry removed = entries.remove(0);
            ids.remove(removed.id());
        }
        append(entry);
    }

    public synchronized List<Entry> recent(int limit) {
        int from = Math.max(0, entries.size() - limit);
        return new ArrayList<>(entries.subList(from, entries.size()));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
        ids.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - MAX_MEMORY);
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Entry e = Entry.fromJson(Json.parse(line));
                    if (!e.id().isEmpty() && !ids.contains(e.id())) {
                        entries.add(e);
                        ids.add(e.id());
                    }
                } catch (RuntimeException ignored) {
                    // 跳过损坏的行
                }
            }
        } catch (IOException ignored) {
            // 读不到就当空历史
        }
    }

    private void append(Entry entry) {
        try {
            if (Files.exists(file) && Files.size(file) > 2_000_000) {
                compact();
                return;
            }
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(entry.toJson().toJson());
                w.newLine();
            }
        } catch (IOException ignored) {
            // 磁盘写入失败不影响实时同步
        }
    }

    private void compact() {
        Path tmp = file.resolveSibling("history.jsonl.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Entry e : entries) {
                w.write(e.toJson().toJson());
                w.newLine();
            }
        } catch (IOException ignored) {
            return;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
