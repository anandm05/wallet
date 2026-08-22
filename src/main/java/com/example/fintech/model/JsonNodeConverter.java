package com.example.fintech.model;

import com.fasterxml.jackson.databind.*;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class JsonNodeConverter implements AttributeConverter<JsonNode, String> {
 private static final ObjectMapper MAPPER = new ObjectMapper();
 public String convertToDatabaseColumn(JsonNode value) { return value == null ? null : value.toString(); }
 public JsonNode convertToEntityAttribute(String value) { try { return value == null ? null : MAPPER.readTree(value); } catch (Exception e) { throw new IllegalArgumentException("Invalid outbox JSON", e); } }
}
