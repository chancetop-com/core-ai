package ai.core.cli.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rebuilds {@code .core-ai/knowledge/MEMORY.md} from the pages that actually exist on disk.
 *
 * <p>The index is a moving target: extraction writes pages, users delete or rename them, and the LLM
 * may forget to update the table. This maintainer treats the filesystem as the source of truth,
 * keeps curated descriptions from the existing index, and drops rows for files that no longer exist.
 * It is idempotent and runs as part of a maintenance pass, not during a task turn.
 */
public final class MemoryIndexMaintainer {
    private static final List<String> TYPES = List.of("project", "user", "feedback", "reference");
    private static final Map<String, String> SECTION_TITLES = Map.of(
            "project", "Project",
            "user", "User",
            "feedback", "Feedback",
            "reference", "Reference");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final int MAX_DESCRIPTION_CHARS = 240;

    public static Result reconcile(Path workspace) throws IOException {
        Path knowledgeDir = workspace.resolve(".core-ai/knowledge");
        Path indexPath = knowledgeDir.resolve("MEMORY.md");
        Map<String, List<Row>> existing = readIndex(indexPath);

        var sections = new LinkedHashMap<String, List<Row>>();
        Map<String, Row> oldRows = new HashMap<>(256);
        int added = 0;
        int removed = 0;
        int updated = 0;
        int total = 0;

        for (var type : TYPES) {
            oldRows.clear();
            for (var row : existing.getOrDefault(type, List.of())) {
                oldRows.put(row.file, row);
            }

            var pages = listPages(knowledgeDir.resolve(type));
            var rows = new ArrayList<Row>(pages.size());
            for (var file : pages) {
                String relative = type + "/" + file.getFileName();
                Row old = oldRows.remove(relative);
                Row row = toRow(file, relative, old);
                rows.add(row);
                total++;
                if (old == null) {
                    added++;
                } else if (!old.sameAs(row)) {
                    updated++;
                }
            }
            removed += oldRows.size();
            rows.sort(Comparator.comparing(Row::updated).reversed().thenComparing(Row::file));
            sections.put(type, rows);
        }

        String rendered = render(sections);
        String current = Files.isRegularFile(indexPath) ? Files.readString(indexPath, StandardCharsets.UTF_8) : "";
        if (!rendered.equals(current)) {
            Files.createDirectories(knowledgeDir);
            Files.writeString(indexPath, rendered, StandardCharsets.UTF_8);
        }
        return new Result(added, removed, updated, total);
    }

    private static Map<String, List<Row>> readIndex(Path indexPath) throws IOException {
        var result = new LinkedHashMap<String, List<Row>>();
        if (!Files.isRegularFile(indexPath)) return result;

        String section = null;
        for (var line : Files.readAllLines(indexPath, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("## ")) {
                section = sectionKey(trimmed.substring(3).strip());
                result.computeIfAbsent(section, key -> new ArrayList<>());
                continue;
            }
            if (section == null || !trimmed.startsWith("|")) continue;
            if (trimmed.startsWith("| File ") || trimmed.startsWith("|---")) continue;
            Row row = parseRow(trimmed);
            if (row != null) {
                result.get(section).add(row);
            }
        }
        return result;
    }

    private static Row parseRow(String line) {
        var cells = splitCells(line);
        if (cells.size() < 5) return null;

        String file = cells.getFirst();
        String description = cells.get(1);
        String created = cells.get(cells.size() - 2);
        String updated = cells.getLast();
        if (!isDate(updated)) return null;

        String whenToUse;
        if (!isDate(created)) {
            // a historical row had an unescaped pipe inside "When to Use", shifting Created;
            // rebuild the text and let Created fall back to the file's creation date
            whenToUse = String.join(" | ", cells.subList(2, cells.size() - 1));
            created = "";
        } else if (cells.size() == 5) {
            whenToUse = cells.get(2);
        } else {
            whenToUse = String.join(" | ", cells.subList(2, cells.size() - 2));
        }
        return new Row(file, description, whenToUse, created, updated);
    }

    private static List<String> splitCells(String line) {
        var cells = new ArrayList<String>();
        var current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '|') {
                cells.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!cells.isEmpty() && cells.getFirst().isEmpty()) cells.removeFirst();
        if (!cells.isEmpty() && cells.getLast().isEmpty()) cells.removeLast();
        return cells;
    }

    private static boolean isDate(String value) {
        return value != null && DATE.matcher(value).matches();
    }

    private static String sectionKey(String title) {
        return title.toLowerCase(Locale.ROOT);
    }

    private static List<Path> listPages(Path typeDir) throws IOException {
        var files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(typeDir, "*.md")) {
            for (var file : stream) files.add(file);
        } catch (java.nio.file.NoSuchFileException e) {
            return files;
        }
        return files;
    }

    private static Row toRow(Path file, String relative, Row old) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        String title = title(file);
        String description = old != null && !old.description.isBlank()
                ? old.description
                : truncate(title, MAX_DESCRIPTION_CHARS);
        String whenToUse = old != null && !old.whenToUse.isBlank() ? old.whenToUse : "-";
        String created = old != null && !old.created.isBlank() ? old.created : date(attributes.creationTime().toInstant());
        String updated = date(attributes.lastModifiedTime().toInstant());
        return new Row(relative, description, whenToUse, created, updated);
    }

    private static String title(Path file) {
        try {
            var first = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElse("");
            if (!first.isEmpty()) {
                return first.startsWith("#") ? first.replaceFirst("^#+\\s*", "").strip() : first;
            }
        } catch (IOException ignored) {
            // fall through to the file name
        }
        Path fileName = file.getFileName();
        String name = fileName != null ? fileName.toString() : file.toString();
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private static String date(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private static String render(Map<String, List<Row>> sections) {
        var sb = new StringBuilder(4096);
        sb.append("# Knowledge Index\n\n");
        for (var type : TYPES) {
            sb.append("## ").append(SECTION_TITLES.get(type))
                    .append("\n\n| File | Description | When to Use | Created | Updated |\n|---|---|---|---|---|\n");
            for (var row : sections.getOrDefault(type, List.of())) {
                sb.append("| ").append(cell(row.file))
                        .append(" | ").append(cell(row.description))
                        .append(" | ").append(cell(row.whenToUse))
                        .append(" | ").append(cell(row.created))
                        .append(" | ").append(cell(row.updated))
                        .append(" |\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String cell(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ').strip();
    }

    private MemoryIndexMaintainer() {
    }

    public record Result(int added, int removed, int updated, int total) {
        public boolean changed() {
            return added + removed + updated > 0;
        }
    }

    private record Row(String file, String description, String whenToUse, String created, String updated) {
        boolean sameAs(Row other) {
            return file.equals(other.file)
                    && description.equals(other.description)
                    && whenToUse.equals(other.whenToUse)
                    && created.equals(other.created)
                    && updated.equals(other.updated);
        }
    }
}
