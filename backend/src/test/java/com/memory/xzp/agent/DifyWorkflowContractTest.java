package com.memory.xzp.agent;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifyWorkflowContractTest {

    private static final Path WORKFLOW_PATH =
            Path.of("..", "docs", "云忆相册助手-write.yml");
    private static final Path OPENAPI_PATH =
            Path.of("..", "docs", "dify-agent-openapi.yaml");

    @Test
    void workflowIsValidYamlWithUniqueResolvableGraphReferences() throws IOException {
        Map<String, Object> root = loadWorkflow();
        Map<String, Object> graph = graph(root);
        List<Map<String, Object>> nodes = objects(graph.get("nodes"));
        List<Map<String, Object>> edges = objects(graph.get("edges"));

        Set<String> nodeIds = nodes.stream()
                .map(node -> string(node.get("id")))
                .collect(Collectors.toSet());
        assertEquals(nodes.size(), nodeIds.size(), "Dify node IDs must be unique");
        assertFalse(nodeIds.contains(""));

        for (Map<String, Object> edge : edges) {
            assertTrue(nodeIds.contains(string(edge.get("source"))),
                    () -> "Missing edge source: " + edge.get("source"));
            assertTrue(nodeIds.contains(string(edge.get("target"))),
                    () -> "Missing edge target: " + edge.get("target"));
        }
    }

    @Test
    void readPlannerRoutesToAtMostOneWhitelistedTool() throws IOException {
        Map<String, Object> root = loadWorkflow();
        List<Map<String, Object>> edges = objects(graph(root).get("edges"));

        Set<String> allowedReadTargets = Set.of(
                "tool_capabilities",
                "tool_search_tag",
                "tool_advanced_search",
                "tool_analyze_library",
                "tool_discover_similar_files",
                "tool_list_albums",
                "tool_list_location_albums",
                "tool_list_model_albums",
                "tool_list_tags",
                "tool_list_people"
        );
        Set<String> routedTargets = edges.stream()
                .filter(edge -> "route_read_tool".equals(edge.get("source")))
                .map(edge -> string(edge.get("target")))
                .collect(Collectors.toSet());

        assertTrue(allowedReadTargets.containsAll(
                routedTargets.stream()
                        .filter(target -> !"answer_read_direct".equals(target))
                        .collect(Collectors.toSet())));
        assertEquals(allowedReadTargets.size(),
                routedTargets.stream().filter(allowedReadTargets::contains).count());

        Map<String, Long> incomingCounts = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            String target = string(edge.get("target"));
            if (allowedReadTargets.contains(target)) {
                incomingCounts.merge(target, 1L, Long::sum);
            }
        }
        allowedReadTargets.forEach(target ->
                assertEquals(1L, incomingCounts.getOrDefault(target, 0L),
                        "Each read tool must have exactly one routed input"));
    }

    @Test
    void executeToolsAcceptOnlyPendingCredentialsAndConfirmation() throws IOException {
        Map<String, Object> root = loadWorkflow();
        List<Map<String, Object>> nodes = objects(graph(root).get("nodes"));

        for (String nodeId : List.of(
                "tool_execute_album_action",
                "tool_execute_tag_action",
                "tool_submit_image_tag_task",
                "tool_execute_apply_suggested_tags",
                "tool_execute_p3_action",
                "tool_execute_p4_action"
        )) {
            Map<String, Object> node = node(nodes, nodeId);
            Map<String, Object> data = object(node.get("data"));
            Map<String, Object> parameters = object(data.get("tool_parameters"));
            assertEquals(Set.of(
                    "pendingActionId",
                    "confirmationToken",
                    "idempotencyKey",
                    "conversationId",
                    "confirmed"
            ), parameters.keySet(), nodeId + " must not accept mutable business parameters");
            assertEquals(Boolean.TRUE,
                    object(parameters.get("confirmed")).get("value"),
                    nodeId + " must send an explicit boolean true");
        }
    }

    @Test
    void assignersOverwriteConversationStateExplicitly() throws IOException {
        List<Map<String, Object>> nodes = objects(graph(loadWorkflow()).get("nodes"));
        for (Map<String, Object> node : nodes) {
            Map<String, Object> data = object(node.get("data"));
            if (!"assigner".equals(string(data.get("type")))) {
                continue;
            }
            for (Map<String, Object> item : objects(data.get("items"))) {
                assertEquals("over-write", item.get("operation"));
                assertEquals("over-write", item.get("write_mode"));
            }
        }
    }

    @Test
    void uncertainExecuteFailuresQueryStatusWithoutClearingCredentials() throws IOException {
        List<Map<String, Object>> edges = objects(graph(loadWorkflow()).get("edges"));
        for (String toolId : List.of(
                "tool_execute_album_action",
                "tool_execute_tag_action",
                "tool_submit_image_tag_task",
                "tool_execute_apply_suggested_tags",
                "tool_execute_p3_action",
                "tool_execute_p4_action",
                "tool_cancel_pending_action"
        )) {
            Map<String, Object> failEdge = edges.stream()
                    .filter(edge -> toolId.equals(edge.get("source")))
                    .filter(edge -> "fail-branch".equals(edge.get("sourceHandle")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing fail edge for " + toolId));
            assertEquals("tool_get_pending_action_status", failEdge.get("target"));
        }
    }

    @Test
    void workflowUsesConversationPendingStateWithoutHiddenComments() throws IOException {
        String yamlText = Files.readString(WORKFLOW_PATH);
        assertFalse(yamlText.contains("CLOUD_ALBUM_PENDING"));
        assertFalse(yamlText.contains("latest assistant preview message"));
        assertFalse(yamlText.contains("recover all required pending"));

        Map<String, Object> root = loadWorkflow();
        List<Map<String, Object>> conversationVariables =
                objects(object(root.get("workflow")).get("conversation_variables"));
        Set<String> names = conversationVariables.stream()
                .map(value -> string(value.get("name")))
                .collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "pending_action_id",
                "pending_confirmation_token",
                "pending_idempotency_key",
                "pending_family",
                "pending_expires_at"
        )));
    }

    @Test
    void writeCredentialsNeverFlowIntoLlmOrAnswerNodes() throws IOException {
        Map<String, Object> root = loadWorkflow();
        List<Map<String, Object>> nodes = objects(graph(root).get("nodes"));
        List<Map<String, Object>> edges = objects(graph(root).get("edges"));

        Set<String> modelOrAnswerIds = nodes.stream()
                .filter(node -> {
                    String type = string(object(node.get("data")).get("type"));
                    return "llm".equals(type) || "answer".equals(type);
                })
                .map(node -> string(node.get("id")))
                .collect(Collectors.toSet());
        Set<String> sensitiveToolIds = Set.of(
                "tool_preview_album_action",
                "tool_preview_tag_action",
                "tool_execute_album_action",
                "tool_execute_tag_action",
                "tool_submit_image_tag_task",
                "tool_execute_apply_suggested_tags",
                "tool_preview_p3_action",
                "tool_preview_p4_action",
                "tool_execute_p3_action",
                "tool_execute_p4_action",
                "tool_cancel_pending_action"
        );

        for (Map<String, Object> edge : edges) {
            assertFalse(
                    sensitiveToolIds.contains(string(edge.get("source")))
                            && modelOrAnswerIds.contains(string(edge.get("target"))),
                    () -> "Sensitive write output flows directly to model/answer: " + edge.get("id")
            );
        }
        for (Map<String, Object> node : nodes) {
            if (!"llm".equals(string(object(node.get("data")).get("type")))) {
                continue;
            }
            String modelData = String.valueOf(node.get("data"));
            assertFalse(modelData.contains("confirmationToken"));
            assertFalse(modelData.contains("pending_confirmation_token"));
            assertFalse(modelData.contains("idempotencyKey"));
        }
    }

    @Test
    void openApiExecuteContractIsFailClosed() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> openApi = (Map<String, Object>) new Yaml().load(
                Files.readString(OPENAPI_PATH)
        );
        Map<String, Object> components = object(openApi.get("components"));
        Map<String, Object> schemas = object(components.get("schemas"));
        Map<String, Object> execute = object(schemas.get("AgentExecuteActionRequest"));

        assertEquals(Boolean.FALSE, execute.get("additionalProperties"));
        assertEquals(Set.of(
                "pendingActionId",
                "confirmationToken",
                "idempotencyKey",
                "confirmed"
        ), object(execute.get("properties")).keySet());
        assertEquals(
                List.of(Boolean.TRUE),
                object(object(execute.get("properties")).get("confirmed")).get("enum")
        );

        Map<String, Object> preview = object(schemas.get("AgentActionPreview"));
        Map<String, Object> previewProperties = object(preview.get("properties"));
        assertFalse(object(previewProperties.get("confirmationToken")).containsKey("writeOnly"));
        assertFalse(object(previewProperties.get("idempotencyKey")).containsKey("writeOnly"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadWorkflow() throws IOException {
        assertTrue(Files.isRegularFile(WORKFLOW_PATH), "Workflow export is missing");
        Object parsed = new Yaml().load(Files.readString(WORKFLOW_PATH));
        assertNotNull(parsed);
        return (Map<String, Object>) parsed;
    }

    private static Map<String, Object> graph(Map<String, Object> root) {
        return object(object(root.get("workflow")).get("graph"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertTrue(value instanceof Map<?, ?>, () -> "Expected object but got: " + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        assertTrue(value instanceof List<?>, () -> "Expected list but got: " + value);
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> node(List<Map<String, Object>> nodes, String id) {
        return nodes.stream()
                .filter(value -> id.equals(value.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing node: " + id));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
