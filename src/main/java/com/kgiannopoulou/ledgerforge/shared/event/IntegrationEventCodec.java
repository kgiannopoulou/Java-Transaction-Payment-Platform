package com.kgiannopoulou.ledgerforge.shared.event;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** JSON wire format for integration events. The type travels separately, as a Kafka header. */
@Component
public class IntegrationEventCodec {

    /** Wire name -> record class, derived from the sealed hierarchy so it cannot drift. */
    @SuppressWarnings("unchecked")
    private static final Map<String, Class<? extends IntegrationEvent>> TYPES =
            Arrays.stream(IntegrationEvent.class.getPermittedSubclasses())
                    .map(c -> (Class<? extends IntegrationEvent>) c)
                    .collect(Collectors.toUnmodifiableMap(IntegrationEvent::typeOf, Function.identity()));

    private final JsonMapper jsonMapper;

    public IntegrationEventCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String encode(IntegrationEvent event) {
        return jsonMapper.writeValueAsString(event);
    }

    public IntegrationEvent decode(String type, String json) {
        Class<? extends IntegrationEvent> eventClass = type == null ? null : TYPES.get(type);
        if (eventClass == null) {
            throw new MalformedEventException("Unknown event type '" + type + "'");
        }
        try {
            return jsonMapper.readValue(json, eventClass);
        } catch (RuntimeException e) {
            throw new MalformedEventException("Cannot decode " + type + " event: " + e.getMessage(), e);
        }
    }
}
