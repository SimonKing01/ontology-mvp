package com.example.ontology.objectdata.store;

/**
 * obj_link 一行。
 */
public record LinkRow(String linkId, String linkType, String fromObjectId, String toObjectId) {
}
