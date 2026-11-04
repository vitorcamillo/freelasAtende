package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
class MlLabJsonHelper {

    private final ObjectMapper objectMapper;

    List<String> lerLista(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    Map<String, Object> lerMemoria(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    List<String> lerDuvidas(String memoriaJson) {
        return asStringList(lerMemoria(memoriaJson).get("duvidas"));
    }

    List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                strings.add(item.toString());
            }
        }
        return strings;
    }

    void adicionarLimitado(List<String> items, String item, int limit) {
        if (item == null || item.isBlank()) {
            return;
        }
        items.remove(item);
        items.add(0, item);
        while (items.size() > limit) {
            items.remove(items.size() - 1);
        }
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
