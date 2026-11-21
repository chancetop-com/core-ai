package ai.core.server.evaluation.service;

import ai.core.server.evaluation.EvalHashing;
import ai.core.server.evaluation.domain.DatasetRevisionEntity;
import ai.core.server.evaluation.domain.EvalCaseEntity;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.store.EvaluationStore;
import ai.core.server.file.FileService;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates an uploaded JSONL dataset and persists an immutable revision.
 *
 * @author stephen
 */
public class EvalDatasetImporter {
    @Inject
    FileService fileService;
    @Inject
    EvaluationStore evaluationStore;
    @Inject
    EvalDatasetParser parser;

    public DatasetRevisionEntity importJsonl(EvaluationEntity evaluation, String fileId, String name, String actorId) {
        var record = fileService.getOwned(fileId, actorId);
        var content = decode(fileService.getBytes(record));
        var parsed = parser.parse(content);
        var revisionNumber = evaluationStore.nextDatasetRevision(evaluation.id);
        var revision = new DatasetRevisionEntity();
        revision.id = UUID.randomUUID().toString();
        revision.evaluationId = evaluation.id;
        revision.revision = revisionNumber;
        revision.status = "READY";
        revision.caseCount = parsed.cases.size();
        revision.createdBy = actorId;
        revision.createdAt = ZonedDateTime.now();
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schema_version", "core-ai-eval/v1");
        manifest.put("name", name == null || name.isBlank() ? record.fileName : name.trim());
        manifest.put("source_file_name", record.fileName);
        manifest.put("source_file_hash", record.contentHash);
        manifest.put("case_count", parsed.cases.size());
        manifest.put("splits", splitCounts(parsed.cases));
        manifest.put("imported_by", actorId);
        manifest.put("imported_at", revision.createdAt.toString());
        revision.manifestJson = JsonUtil.toJson(manifest);
        revision.contentHash = contentHash(parsed.cases, manifest);
        evaluationStore.insertDatasetRevision(revision);
        var cases = new ArrayList<EvalCaseEntity>(parsed.cases.size());
        for (var item : parsed.cases) {
            var entity = new EvalCaseEntity();
            entity.id = UUID.randomUUID().toString();
            entity.datasetRevisionId = revision.id;
            entity.caseId = item.caseId();
            entity.caseHash = item.caseHash();
            entity.split = item.split();
            entity.weight = item.weight();
            entity.bodyJson = item.bodyJson();
            cases.add(entity);
        }
        evaluationStore.insertCases(cases);
        return revision;
    }

    private String decode(byte[] bytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new EvalValidationException("content", "dataset must be valid UTF-8", e);
        }
    }

    private String contentHash(List<EvalDatasetParser.ParsedCase> cases, Map<String, Object> manifest) {
        var sorted = new ArrayList<>(cases);
        sorted.sort(java.util.Comparator.comparing(EvalDatasetParser.ParsedCase::caseId));
        var hashes = new ArrayList<String>(sorted.size());
        for (var item : sorted) hashes.add(item.caseId() + ":" + item.caseHash());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schema_version", "core-ai-eval/v1");
        payload.put("name", manifest.get("name"));
        payload.put("source_file_hash", manifest.get("source_file_hash"));
        payload.put("cases", hashes);
        return EvalHashing.hashObject(payload);
    }

    private Map<String, Integer> splitCounts(List<EvalDatasetParser.ParsedCase> cases) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var item : cases) counts.merge(item.split(), 1, Integer::sum);
        return counts;
    }
}
