package com.memory.xzp.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import lombok.Data;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能体写操作执行请求。
 *
 * <p>业务参数只能来自服务端预览载荷。任何额外字段都按参数篡改拒绝。</p>
 */
@Data
public class AgentExecuteActionRequest {

    private String pendingActionId;
    private String confirmationToken;
    private String idempotencyKey;

    @JsonDeserialize(using = StrictBooleanDeserializer.class)
    private Boolean confirmed;

    @JsonIgnore
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }

    /**
     * Jackson normally coerces JSON strings such as {@code "true"} into booleans.
     * Confirmation is a security boundary, so only literal JSON booleans are accepted.
     */
    public static final class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {

        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_TRUE) {
                return Boolean.TRUE;
            }
            if (token == JsonToken.VALUE_FALSE) {
                return Boolean.FALSE;
            }
            throw InvalidFormatException.from(
                    parser,
                    "confirmed必须是JSON布尔值",
                    parser.getValueAsString(),
                    Boolean.class
            );
        }
    }
}
