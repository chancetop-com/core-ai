package ai.core.memory.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Graph store interface for entity-relationship memory storage.
 * Reserved for future implementation (Mem0^g style).
 *
 * @author xander
 */
public interface GraphMemoryStore {

    /**
     * Add an entity node.
     *
     * @param node the entity node
     */
    void addEntity(EntityNode node);

    /**
     * Add a relation edge between entities.
     *
     * @param edge the relation edge
     */
    void addRelation(RelationEdge edge);

    /**
     * Get an entity by ID.
     *
     * @param entityId the entity ID
     * @return optional entity node
     */
    Optional<EntityNode> getEntity(String entityId);

    /**
     * Get entities related to the given entity within depth.
     *
     * @param entityId the source entity ID
     * @param depth    traversal depth
     * @return list of related entity nodes
     */
    List<EntityNode> getRelatedEntities(String entityId, int depth);

    /**
     * Get relationships for an entity.
     *
     * @param entityId the entity ID
     * @return list of relation edges
     */
    List<RelationEdge> getRelationships(String entityId);

    /**
     * Find paths between two entities.
     *
     * @param fromId   source entity ID
     * @param toId     target entity ID
     * @param maxDepth maximum path depth
     * @return list of paths (each path is a list of entity nodes)
     */
    List<List<EntityNode>> findPaths(String fromId, String toId, int maxDepth);

    /**
     * Delete an entity and its relationships.
     *
     * @param entityId the entity ID
     */
    void deleteEntity(String entityId);

    /**
     * Delete a relation edge.
     *
     * @param edgeId the edge ID
     */
    void deleteRelation(String edgeId);

    /**
     * Entity node representing a person, place, object, or concept.
     */
    record EntityNode(
        String id,
        String name,
        EntityType type,
        Map<String, Object> attributes
    ) { }

    /**
     * Relation edge between two entities.
     */
    record RelationEdge(
        String id,
        String sourceId,
        String targetId,
        String relationType,
        Map<String, Object> properties,
        double weight
    ) { }

    /**
     * Entity type enumeration.
     */
    enum EntityType {
        PERSON,
        PLACE,
        OBJECT,
        CONCEPT,
        EVENT,
        ORGANIZATION
    }
}
