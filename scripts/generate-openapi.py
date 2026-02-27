#!/usr/bin/env python3
"""
Generate OpenAPI 3.0 specifications for the Corey's Attractor REST API v1.

Outputs:
  src/main/resources/api/openapi.json   -- OpenAPI 3.0 JSON (served at /api/v1/openapi.json)
  src/main/resources/api/openapi.yaml   -- OpenAPI 3.0 YAML (served at /api/v1/openapi.yaml)

The application also serves these at runtime from the classpath:
  GET /api/v1/openapi.json
  GET /api/v1/openapi.yaml
  GET /api/v1/swagger.json  (alias for openapi.json)
  GET /api/v1/docs          (Swagger UI)

Usage:
  python3 scripts/generate-openapi.py
  make openapi
"""

import json
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(SCRIPT_DIR)
OUT_DIR = os.path.join(ROOT_DIR, "src", "main", "resources", "api")

# ─────────────────────────────────────────────────────────────────────────────
# Reusable schema fragments
# ─────────────────────────────────────────────────────────────────────────────

def ref(name):
    return {"$ref": f"#/components/schemas/{name}"}


def error_responses(*extra_codes):
    """Standard error response set, plus any additional codes."""
    codes = {
        "400": {
            "description": "Bad request — missing or invalid field",
            "content": {"application/json": {"schema": ref("Error")}},
        },
        "404": {
            "description": "Resource not found",
            "content": {"application/json": {"schema": ref("Error")}},
        },
        "500": {
            "description": "Internal server error",
            "content": {"application/json": {"schema": ref("Error")}},
        },
    }
    if "409" in extra_codes:
        codes["409"] = {
            "description": "Conflict — invalid state transition",
            "content": {"application/json": {"schema": ref("Error")}},
        }
    return codes


def pipeline_path_param():
    return {
        "name": "id",
        "in": "path",
        "required": True,
        "description": "Pipeline run ID",
        "schema": {"type": "string"},
    }


# ─────────────────────────────────────────────────────────────────────────────
# Schemas
# ─────────────────────────────────────────────────────────────────────────────

def build_schemas():
    return {
        "Error": {
            "type": "object",
            "required": ["error", "code"],
            "properties": {
                "error": {"type": "string", "example": "pipeline not found"},
                "code": {
                    "type": "string",
                    "enum": ["NOT_FOUND", "BAD_REQUEST", "INVALID_STATE", "INTERNAL_ERROR",
                             "RENDER_ERROR", "GENERATION_ERROR"],
                    "example": "NOT_FOUND",
                },
            },
        },
        "StageRecord": {
            "type": "object",
            "required": ["index", "name", "nodeId", "status", "hasLog"],
            "properties": {
                "index": {"type": "integer", "description": "Zero-based stage index"},
                "name": {"type": "string", "description": "Stage label"},
                "nodeId": {"type": "string", "description": "DOT node ID"},
                "status": {
                    "type": "string",
                    "enum": ["pending", "running", "completed", "failed",
                             "retrying", "diagnosing", "repairing"],
                },
                "startedAt": {"type": "integer", "nullable": True, "description": "Epoch millis"},
                "durationMs": {"type": "integer", "nullable": True},
                "error": {"type": "string", "nullable": True},
                "hasLog": {"type": "boolean"},
            },
        },
        "Pipeline": {
            "type": "object",
            "description": "Full pipeline run (includes dotSource)",
            "required": ["id", "displayName", "fileName", "status", "archived",
                         "hasFailureReport", "simulate", "autoApprove", "familyId",
                         "stages", "logs"],
            "properties": {
                "id": {"type": "string", "example": "run-1700000000000-1"},
                "displayName": {"type": "string", "example": "sapphire-fox"},
                "fileName": {"type": "string", "example": "pipeline.dot"},
                "dotSource": {
                    "type": "string",
                    "description": "Full DOT graph source (included in single-item GET only)",
                },
                "status": {
                    "type": "string",
                    "enum": ["idle", "running", "completed", "failed", "cancelled", "paused"],
                    "example": "completed",
                },
                "archived": {"type": "boolean", "example": False},
                "hasFailureReport": {"type": "boolean", "example": False},
                "simulate": {"type": "boolean", "example": False},
                "autoApprove": {"type": "boolean", "example": True},
                "familyId": {"type": "string"},
                "originalPrompt": {"type": "string"},
                "startedAt": {"type": "integer", "nullable": True, "example": 1700000000000},
                "finishedAt": {"type": "integer", "nullable": True, "example": 1700000060000},
                "currentNode": {"type": "string", "nullable": True},
                "stages": {"type": "array", "items": ref("StageRecord")},
                "logs": {"type": "array", "items": {"type": "string"},
                         "description": "Up to 50 most recent log lines"},
            },
        },
        "PipelineListItem": {
            "allOf": [ref("Pipeline")],
            "description": "Pipeline summary in list responses — dotSource is omitted",
        },
        "PipelineCreateRequest": {
            "type": "object",
            "required": ["dotSource"],
            "properties": {
                "dotSource": {"type": "string", "description": "DOT graph source"},
                "fileName": {"type": "string", "default": "", "example": "pipeline.dot"},
                "simulate": {"type": "boolean", "default": False,
                             "description": "Use simulation backend (no LLM calls)"},
                "autoApprove": {"type": "boolean", "default": True,
                                "description": "Auto-approve human checkpoints"},
                "originalPrompt": {"type": "string", "default": ""},
                "familyId": {"type": "string", "default": "",
                             "description": "Family ID; omit to start a new family"},
            },
        },
        "PipelineCreateResponse": {
            "type": "object",
            "required": ["id", "status"],
            "properties": {
                "id": {"type": "string"},
                "status": {"type": "string", "example": "running"},
            },
        },
        "PipelinePatchRequest": {
            "type": "object",
            "properties": {
                "dotSource": {"type": "string",
                              "description": "Updated DOT source (only allowed on idle/completed/failed runs)"},
                "originalPrompt": {"type": "string"},
            },
        },
        "DeleteResponse": {
            "type": "object",
            "required": ["deleted"],
            "properties": {"deleted": {"type": "boolean"}},
        },
        "ActionResponse": {
            "type": "object",
            "description": "Generic lifecycle action result",
            "properties": {
                "paused": {"type": "boolean"},
                "archived": {"type": "boolean"},
                "unarchived": {"type": "boolean"},
                "cancelled": {"type": "boolean"},
                "id": {"type": "string"},
                "status": {"type": "string"},
            },
        },
        "IterationCreateRequest": {
            "type": "object",
            "required": ["dotSource"],
            "properties": {
                "dotSource": {"type": "string"},
                "originalPrompt": {"type": "string"},
                "fileName": {"type": "string"},
            },
        },
        "IterationCreateResponse": {
            "type": "object",
            "required": ["id", "familyId", "status"],
            "properties": {
                "id": {"type": "string"},
                "familyId": {"type": "string"},
                "status": {"type": "string"},
            },
        },
        "FamilyMember": {
            "type": "object",
            "required": ["id", "displayName", "fileName", "createdAt", "status", "versionNum"],
            "properties": {
                "id": {"type": "string"},
                "displayName": {"type": "string"},
                "fileName": {"type": "string"},
                "createdAt": {"type": "integer"},
                "status": {"type": "string"},
                "versionNum": {"type": "integer"},
                "originalPrompt": {"type": "string"},
            },
        },
        "FamilyResponse": {
            "type": "object",
            "required": ["familyId", "members"],
            "properties": {
                "familyId": {"type": "string"},
                "members": {"type": "array", "items": ref("FamilyMember")},
            },
        },
        "ArtifactInfo": {
            "type": "object",
            "required": ["path", "size", "isText"],
            "properties": {
                "path": {"type": "string", "description": "Relative path within artifacts directory"},
                "size": {"type": "integer", "description": "File size in bytes"},
                "isText": {"type": "boolean", "description": "True for text-based extensions"},
            },
        },
        "ArtifactListResponse": {
            "type": "object",
            "required": ["files", "truncated"],
            "properties": {
                "files": {"type": "array", "items": ref("ArtifactInfo"), "maxItems": 500},
                "truncated": {"type": "boolean",
                              "description": "True if more than 500 artifacts exist"},
            },
        },
        "StagesResponse": {
            "type": "object",
            "required": ["stages"],
            "properties": {
                "stages": {"type": "array", "items": ref("StageRecord")},
            },
        },
        "DotValidateRequest": {
            "type": "object",
            "required": ["dotSource"],
            "properties": {"dotSource": {"type": "string"}},
        },
        "Diagnostic": {
            "type": "object",
            "required": ["severity", "message"],
            "properties": {
                "severity": {"type": "string", "enum": ["error", "warning", "info"]},
                "message": {"type": "string"},
                "nodeId": {"type": "string", "nullable": True},
            },
        },
        "DotValidateResponse": {
            "type": "object",
            "required": ["valid", "diagnostics"],
            "properties": {
                "valid": {"type": "boolean"},
                "diagnostics": {"type": "array", "items": ref("Diagnostic")},
            },
        },
        "DotRenderRequest": {
            "type": "object",
            "required": ["dotSource"],
            "properties": {"dotSource": {"type": "string"}},
        },
        "DotRenderResponse": {
            "type": "object",
            "required": ["svg"],
            "properties": {"svg": {"type": "string", "description": "SVG string from Graphviz"}},
        },
        "DotGenerateRequest": {
            "type": "object",
            "required": ["prompt"],
            "properties": {"prompt": {"type": "string"}},
        },
        "DotSourceResponse": {
            "type": "object",
            "required": ["dotSource"],
            "properties": {"dotSource": {"type": "string"}},
        },
        "DotFixRequest": {
            "type": "object",
            "required": ["dotSource"],
            "properties": {
                "dotSource": {"type": "string"},
                "error": {"type": "string", "default": "",
                          "description": "Validation error or failure context to guide the fix"},
            },
        },
        "DotIterateRequest": {
            "type": "object",
            "required": ["baseDot", "changes"],
            "properties": {
                "baseDot": {"type": "string", "description": "Existing DOT source to iterate on"},
                "changes": {"type": "string", "description": "Natural-language description of desired changes"},
            },
        },
        "Setting": {
            "type": "object",
            "required": ["key", "value"],
            "properties": {
                "key": {"type": "string", "example": "fireworks_enabled"},
                "value": {"type": "string", "example": "true"},
            },
        },
        "SettingsResponse": {
            "type": "object",
            "description": "Map of all known setting keys to their current values",
            "additionalProperties": {"type": "string"},
            "example": {"fireworks_enabled": "true"},
        },
        "SettingUpdateRequest": {
            "type": "object",
            "required": ["value"],
            "properties": {"value": {"type": "string"}},
        },
        "ModelInfo": {
            "type": "object",
            "required": ["id", "provider", "displayName", "contextWindow",
                         "supportsTools", "supportsVision", "supportsReasoning"],
            "properties": {
                "id": {"type": "string", "example": "claude-sonnet-4-5"},
                "provider": {"type": "string", "example": "anthropic"},
                "displayName": {"type": "string", "example": "Claude Sonnet 4.5"},
                "contextWindow": {"type": "integer", "example": 200000},
                "maxOutput": {"type": "integer", "nullable": True},
                "supportsTools": {"type": "boolean"},
                "supportsVision": {"type": "boolean"},
                "supportsReasoning": {"type": "boolean"},
                "inputCostPerMillion": {"type": "number", "nullable": True},
                "outputCostPerMillion": {"type": "number", "nullable": True},
                "aliases": {"type": "array", "items": {"type": "string"}},
            },
        },
        "ModelsResponse": {
            "type": "object",
            "required": ["models"],
            "properties": {
                "models": {"type": "array", "items": ref("ModelInfo")},
            },
        },
        "SseDelta": {
            "type": "object",
            "description": "SSE event data frame",
            "properties": {
                "delta": {"type": "string", "description": "Incremental text chunk"},
                "done": {"type": "boolean", "description": "True on final frame"},
                "dotSource": {"type": "string", "description": "Complete result on final frame"},
                "error": {"type": "string", "description": "Present if generation failed"},
            },
        },
    }


# ─────────────────────────────────────────────────────────────────────────────
# Paths
# ─────────────────────────────────────────────────────────────────────────────

def build_paths():
    pid = pipeline_path_param()

    json_200 = lambda schema, desc="": {
        "description": desc or "Success",
        "content": {"application/json": {"schema": ref(schema)}},
    }

    sse_200 = {
        "description": "Server-Sent Events stream. Each frame: `data: {json}\\n\\n`. "
                       "Heartbeat frames: `: heartbeat\\n\\n`.",
        "content": {"text/event-stream": {"schema": ref("SseDelta")}},
    }

    return {
        # ── Pipelines collection ──────────────────────────────────────────────
        "/pipelines": {
            "get": {
                "operationId": "listPipelines",
                "summary": "List all pipeline runs",
                "description": "Returns all runs ordered by creation time. The `dotSource` field is "
                               "omitted from list responses; use `GET /pipelines/{id}` to retrieve it.",
                "tags": ["pipelines"],
                "responses": {
                    "200": {
                        "description": "Array of pipeline run summaries",
                        "content": {"application/json": {
                            "schema": {"type": "array", "items": ref("PipelineListItem")},
                        }},
                    },
                },
            },
            "post": {
                "operationId": "createPipeline",
                "summary": "Submit and run a new pipeline",
                "tags": ["pipelines"],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref("PipelineCreateRequest")}},
                },
                "responses": {
                    "201": json_200("PipelineCreateResponse", "Pipeline submitted"),
                    **error_responses(),
                },
            },
        },

        "/pipelines/import": {
            "post": {
                "operationId": "importPipeline",
                "summary": "Import a pipeline from a ZIP archive",
                "description": "Accepts a raw `application/zip` request body containing a "
                               "`pipeline-meta.json` entry. Query param `onConflict` controls "
                               "behaviour when the run ID already exists: `skip` (default), "
                               "`overwrite`, or `rename`.",
                "tags": ["pipelines"],
                "parameters": [
                    {
                        "name": "onConflict",
                        "in": "query",
                        "schema": {"type": "string", "enum": ["skip", "overwrite", "rename"],
                                   "default": "skip"},
                    },
                ],
                "requestBody": {
                    "required": True,
                    "content": {"application/zip": {"schema": {"type": "string", "format": "binary"}}},
                },
                "responses": {
                    "200": {
                        "description": "Import result",
                        "content": {"application/json": {
                            "schema": {
                                "type": "object",
                                "properties": {
                                    "imported": {"type": "boolean"},
                                    "id": {"type": "string"},
                                    "action": {"type": "string",
                                               "enum": ["imported", "skipped", "overwritten", "renamed"]},
                                },
                            },
                        }},
                    },
                    **error_responses(),
                },
            },
        },

        # ── Single pipeline ───────────────────────────────────────────────────
        "/pipelines/{id}": {
            "get": {
                "operationId": "getPipeline",
                "summary": "Get a single pipeline run (includes dotSource)",
                "tags": ["pipelines"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("Pipeline"),
                    **error_responses(),
                },
            },
            "patch": {
                "operationId": "patchPipeline",
                "summary": "Update mutable pipeline metadata",
                "description": "Allowed on idle, completed, or failed pipelines only. "
                               "Running or paused pipelines return 409.",
                "tags": ["pipelines"],
                "parameters": [pid],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref("PipelinePatchRequest")}},
                },
                "responses": {
                    "200": json_200("Pipeline"),
                    **error_responses("409"),
                },
            },
            "delete": {
                "operationId": "deletePipeline",
                "summary": "Permanently delete a pipeline run",
                "description": "Removes the run from memory, database, and deletes its log directory. "
                               "Returns 409 if the run is running or paused.",
                "tags": ["pipelines"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("DeleteResponse"),
                    **error_responses("409"),
                },
            },
        },

        # ── Lifecycle actions ─────────────────────────────────────────────────
        "/pipelines/{id}/rerun": {
            "post": {
                "operationId": "rerunPipeline",
                "summary": "Re-run a completed, failed, or cancelled pipeline",
                "tags": ["pipelines", "lifecycle"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("PipelineCreateResponse"),
                    **error_responses("409"),
                },
            },
        },
        "/pipelines/{id}/pause": {
            "post": {
                "operationId": "pausePipeline",
                "summary": "Pause a running pipeline",
                "tags": ["pipelines", "lifecycle"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("ActionResponse"),
                    **error_responses("409"),
                },
            },
        },
        "/pipelines/{id}/resume": {
            "post": {
                "operationId": "resumePipeline",
                "summary": "Resume a paused pipeline",
                "tags": ["pipelines", "lifecycle"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("ActionResponse"),
                    **error_responses("409"),
                },
            },
        },
        "/pipelines/{id}/cancel": {
            "post": {
                "operationId": "cancelPipeline",
                "summary": "Cancel a running or paused pipeline",
                "tags": ["pipelines", "lifecycle"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("ActionResponse"),
                    **error_responses("409"),
                },
            },
        },
        "/pipelines/{id}/archive": {
            "post": {
                "operationId": "archivePipeline",
                "summary": "Archive a pipeline run",
                "tags": ["pipelines", "lifecycle"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("ActionResponse"),
                    **error_responses(),
                },
            },
        },
        "/pipelines/{id}/unarchive": {
            "post": {
                "operationId": "unarchivePipeline",
                "summary": "Unarchive a pipeline run",
                "tags": ["pipelines", "lifecycle"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("ActionResponse"),
                    **error_responses(),
                },
            },
        },
        "/pipelines/{id}/iterations": {
            "post": {
                "operationId": "createIteration",
                "summary": "Create a new iteration in the same family",
                "description": "Submits a new pipeline run that shares the parent's familyId.",
                "tags": ["pipelines", "lifecycle"],
                "parameters": [pid],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref("IterationCreateRequest")}},
                },
                "responses": {
                    "201": json_200("IterationCreateResponse"),
                    **error_responses(),
                },
            },
        },
        "/pipelines/{id}/family": {
            "get": {
                "operationId": "getPipelineFamily",
                "summary": "List all runs in the same family (lineage)",
                "tags": ["pipelines"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("FamilyResponse"),
                    **error_responses(),
                },
            },
        },

        # ── Artifacts & files ─────────────────────────────────────────────────
        "/pipelines/{id}/artifacts": {
            "get": {
                "operationId": "listArtifacts",
                "summary": "List artifact files for a pipeline run",
                "tags": ["artifacts"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("ArtifactListResponse"),
                    **error_responses(),
                },
            },
        },
        "/pipelines/{id}/artifacts/{path}": {
            "get": {
                "operationId": "getArtifact",
                "summary": "Download a single artifact file",
                "description": "Serves the file with `text/plain` for text extensions or "
                               "`application/octet-stream` otherwise. Path traversal attempts "
                               "return 404.",
                "tags": ["artifacts"],
                "parameters": [
                    pid,
                    {
                        "name": "path",
                        "in": "path",
                        "required": True,
                        "description": "Relative path within the artifacts directory",
                        "schema": {"type": "string"},
                    },
                ],
                "responses": {
                    "200": {
                        "description": "File content",
                        "content": {
                            "text/plain": {"schema": {"type": "string"}},
                            "application/octet-stream": {"schema": {"type": "string", "format": "binary"}},
                        },
                    },
                    **error_responses(),
                },
            },
        },
        "/pipelines/{id}/artifacts.zip": {
            "get": {
                "operationId": "downloadArtifactsZip",
                "summary": "Download all artifacts as a ZIP archive",
                "tags": ["artifacts"],
                "parameters": [pid],
                "responses": {
                    "200": {
                        "description": "ZIP archive",
                        "content": {"application/zip": {"schema": {"type": "string", "format": "binary"}}},
                    },
                    **error_responses(),
                },
            },
        },
        "/pipelines/{id}/export": {
            "get": {
                "operationId": "exportPipeline",
                "summary": "Export a pipeline as a ZIP (pipeline-meta.json)",
                "tags": ["artifacts"],
                "parameters": [pid],
                "responses": {
                    "200": {
                        "description": "ZIP archive containing pipeline-meta.json",
                        "content": {"application/zip": {"schema": {"type": "string", "format": "binary"}}},
                    },
                    **error_responses(),
                },
            },
        },
        "/pipelines/{id}/failure-report": {
            "get": {
                "operationId": "getFailureReport",
                "summary": "Retrieve the LLM failure diagnosis report",
                "tags": ["artifacts"],
                "parameters": [pid],
                "responses": {
                    "200": {
                        "description": "failure_report.json content",
                        "content": {"application/json": {"schema": {"type": "object"}}},
                    },
                    **error_responses(),
                },
            },
        },
        "/pipelines/{id}/stages": {
            "get": {
                "operationId": "getStages",
                "summary": "List stage records for a pipeline run",
                "tags": ["pipelines"],
                "parameters": [pid],
                "responses": {
                    "200": json_200("StagesResponse"),
                    **error_responses(),
                },
            },
        },
        "/pipelines/{id}/stages/{nodeId}/log": {
            "get": {
                "operationId": "getStageLog",
                "summary": "Retrieve the live log for a specific stage",
                "tags": ["pipelines"],
                "parameters": [
                    pid,
                    {
                        "name": "nodeId",
                        "in": "path",
                        "required": True,
                        "description": "DOT node ID",
                        "schema": {"type": "string"},
                    },
                ],
                "responses": {
                    "200": {
                        "description": "Log content as plain text",
                        "content": {"text/plain": {"schema": {"type": "string"}}},
                    },
                    **error_responses(),
                },
            },
        },

        # ── DOT tooling ───────────────────────────────────────────────────────
        "/dot/validate": {
            "post": {
                "operationId": "validateDot",
                "summary": "Validate a DOT graph source",
                "tags": ["dot"],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref("DotValidateRequest")}},
                },
                "responses": {
                    "200": json_200("DotValidateResponse"),
                    **error_responses(),
                },
            },
        },
        "/dot/render": {
            "post": {
                "operationId": "renderDot",
                "summary": "Render DOT source to SVG using Graphviz",
                "description": "Requires Graphviz (`dot` binary) to be installed.",
                "tags": ["dot"],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref("DotRenderRequest")}},
                },
                "responses": {
                    "200": json_200("DotRenderResponse"),
                    **error_responses(),
                },
            },
        },
        "/dot/generate": {
            "post": {
                "operationId": "generateDot",
                "summary": "Generate a DOT graph from a natural-language prompt (synchronous)",
                "tags": ["dot"],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref("DotGenerateRequest")}},
                },
                "responses": {
                    "200": json_200("DotSourceResponse"),
                    **error_responses(),
                },
            },
        },
        "/dot/generate/stream": {
            "get": {
                "operationId": "generateDotStream",
                "summary": "Generate a DOT graph with streaming SSE deltas",
                "tags": ["dot"],
                "parameters": [
                    {"name": "prompt", "in": "query", "required": True,
                     "schema": {"type": "string"}},
                ],
                "responses": {"200": sse_200, **error_responses()},
            },
        },
        "/dot/fix": {
            "post": {
                "operationId": "fixDot",
                "summary": "Fix a broken DOT graph (synchronous)",
                "tags": ["dot"],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref("DotFixRequest")}},
                },
                "responses": {
                    "200": json_200("DotSourceResponse"),
                    **error_responses(),
                },
            },
        },
        "/dot/fix/stream": {
            "get": {
                "operationId": "fixDotStream",
                "summary": "Fix a broken DOT graph with streaming SSE deltas",
                "tags": ["dot"],
                "parameters": [
                    {"name": "dotSource", "in": "query", "required": True,
                     "schema": {"type": "string"}},
                    {"name": "error", "in": "query", "required": False,
                     "schema": {"type": "string"}},
                ],
                "responses": {"200": sse_200, **error_responses()},
            },
        },
        "/dot/iterate": {
            "post": {
                "operationId": "iterateDot",
                "summary": "Iterate on an existing DOT graph (synchronous)",
                "tags": ["dot"],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref("DotIterateRequest")}},
                },
                "responses": {
                    "200": json_200("DotSourceResponse"),
                    **error_responses(),
                },
            },
        },
        "/dot/iterate/stream": {
            "get": {
                "operationId": "iterateDotStream",
                "summary": "Iterate on an existing DOT graph with streaming SSE deltas",
                "tags": ["dot"],
                "parameters": [
                    {"name": "baseDot", "in": "query", "required": True,
                     "schema": {"type": "string"}},
                    {"name": "changes", "in": "query", "required": True,
                     "schema": {"type": "string"}},
                ],
                "responses": {"200": sse_200, **error_responses()},
            },
        },

        # ── Settings ──────────────────────────────────────────────────────────
        "/settings": {
            "get": {
                "operationId": "getSettings",
                "summary": "Get all settings",
                "description": "Returns a JSON object mapping each known setting key to its value. "
                               "Defaults to `\"true\"` if a setting has never been explicitly set.",
                "tags": ["settings"],
                "responses": {
                    "200": json_200("SettingsResponse"),
                },
            },
        },
        "/settings/{key}": {
            "get": {
                "operationId": "getSetting",
                "summary": "Get a single setting",
                "tags": ["settings"],
                "parameters": [
                    {"name": "key", "in": "path", "required": True,
                     "schema": {"type": "string", "enum": ["fireworks_enabled"]},
                     "example": "fireworks_enabled"},
                ],
                "responses": {
                    "200": json_200("Setting"),
                    **error_responses(),
                },
            },
            "put": {
                "operationId": "putSetting",
                "summary": "Update a setting",
                "tags": ["settings"],
                "parameters": [
                    {"name": "key", "in": "path", "required": True,
                     "schema": {"type": "string", "enum": ["fireworks_enabled"]}},
                ],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref("SettingUpdateRequest")}},
                },
                "responses": {
                    "200": json_200("Setting"),
                    **error_responses(),
                },
            },
        },

        # ── Models ────────────────────────────────────────────────────────────
        "/models": {
            "get": {
                "operationId": "listModels",
                "summary": "List all available LLM models",
                "tags": ["models"],
                "responses": {
                    "200": json_200("ModelsResponse"),
                },
            },
        },

        # ── Events (SSE) ──────────────────────────────────────────────────────
        "/events": {
            "get": {
                "operationId": "getEvents",
                "summary": "All-pipelines Server-Sent Events stream",
                "description": "Streams full pipeline snapshot JSON on every state change. "
                               "Delivers an initial snapshot on connect.",
                "tags": ["events"],
                "responses": {
                    "200": {
                        "description": "SSE stream. Each `data:` frame is a JSON pipeline snapshot.",
                        "content": {"text/event-stream": {"schema": {"type": "string"}}},
                    },
                },
            },
        },
        "/events/{id}": {
            "get": {
                "operationId": "getPipelineEvents",
                "summary": "Single-pipeline Server-Sent Events stream",
                "description": "Same as `/events` but filtered to the specified pipeline. "
                               "Returns 404 immediately if the pipeline does not exist.",
                "tags": ["events"],
                "parameters": [
                    {"name": "id", "in": "path", "required": True,
                     "schema": {"type": "string"}},
                ],
                "responses": {
                    "200": {
                        "description": "Filtered SSE stream",
                        "content": {"text/event-stream": {"schema": {"type": "string"}}},
                    },
                    **error_responses(),
                },
            },
        },

        # ── API documentation ─────────────────────────────────────────────────
        "/openapi.json": {
            "get": {
                "operationId": "getOpenApiJson",
                "summary": "OpenAPI 3.0 specification (JSON)",
                "tags": ["docs"],
                "responses": {
                    "200": {
                        "description": "OpenAPI 3.0 JSON",
                        "content": {"application/json": {"schema": {"type": "object"}}},
                    },
                    "404": {"description": "Spec not generated — run `make openapi`"},
                },
            },
        },
        "/openapi.yaml": {
            "get": {
                "operationId": "getOpenApiYaml",
                "summary": "OpenAPI 3.0 specification (YAML)",
                "tags": ["docs"],
                "responses": {
                    "200": {
                        "description": "OpenAPI 3.0 YAML",
                        "content": {"application/yaml": {"schema": {"type": "string"}}},
                    },
                    "404": {"description": "Spec not generated — run `make openapi`"},
                },
            },
        },
        "/swagger.json": {
            "get": {
                "operationId": "getSwaggerJson",
                "summary": "Alias for /openapi.json (Swagger-compatible)",
                "tags": ["docs"],
                "responses": {
                    "200": {
                        "description": "OpenAPI 3.0 JSON",
                        "content": {"application/json": {"schema": {"type": "object"}}},
                    },
                },
            },
        },
        "/docs": {
            "get": {
                "operationId": "getSwaggerUi",
                "summary": "Swagger UI — interactive API explorer",
                "tags": ["docs"],
                "responses": {
                    "200": {
                        "description": "Swagger UI HTML page",
                        "content": {"text/html": {"schema": {"type": "string"}}},
                    },
                },
            },
        },
    }


# ─────────────────────────────────────────────────────────────────────────────
# Top-level spec
# ─────────────────────────────────────────────────────────────────────────────

def build_spec():
    return {
        "openapi": "3.0.3",
        "info": {
            "title": "Corey's Attractor REST API",
            "description": (
                "Full-featured REST API for the Corey's Attractor pipeline orchestration system. "
                "All endpoints are under `/api/v1/`. Existing browser-facing `/api/*` routes "
                "continue to work unchanged.\n\n"
                "**Authentication**: none (auth is out of scope for v1 — routes are structured "
                "to allow middleware insertion in a future sprint).\n\n"
                "**Error envelope**: all error responses use `{\"error\":\"...\",\"code\":\"...\"}`."
            ),
            "version": "1.0.0",
            "contact": {
                "name": "Corey's Attractor",
                "url": "https://github.com/coreydaley/coreys-attractor",
            },
            "license": {
                "name": "Apache 2.0",
                "url": "https://www.apache.org/licenses/LICENSE-2.0",
            },
        },
        "servers": [
            {"url": "/api/v1", "description": "REST API v1 (relative to server root)"},
        ],
        "tags": [
            {"name": "pipelines", "description": "Pipeline run CRUD and metadata"},
            {"name": "lifecycle", "description": "Pipeline lifecycle actions (rerun, pause, resume, cancel, archive)"},
            {"name": "artifacts", "description": "Artifact files, logs, failure reports, export/import"},
            {"name": "dot", "description": "DOT graph tooling — validate, render, generate, fix, iterate"},
            {"name": "settings", "description": "Application settings"},
            {"name": "models", "description": "LLM model catalog"},
            {"name": "events", "description": "Server-Sent Events streams"},
            {"name": "docs", "description": "API documentation endpoints"},
        ],
        "paths": build_paths(),
        "components": {
            "schemas": build_schemas(),
        },
    }


# ─────────────────────────────────────────────────────────────────────────────
# YAML serialization (no external dependencies required)
# ─────────────────────────────────────────────────────────────────────────────

import re as _re

_NEEDS_QUOTE = _re.compile(
    r'^[&*!,\[\]{}#|>\'"%@`~?]'   # starts with special char
    r'|^[-:] '                      # starts with "- " or ": "
    r'|: '                          # contains ": "
    r'| #'                          # contains " #"
    r'|^\s|\s$'                     # leading/trailing whitespace
    r'|^$'                          # empty string
)
_YAML_KEYWORDS = {
    'true', 'false', 'yes', 'no', 'on', 'off', 'null', '~',
    'True', 'False', 'Yes', 'No', 'On', 'Off', 'Null',
}
_LOOKS_NUMERIC = _re.compile(r'^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$')


def _yaml_str(s):
    """Return a YAML scalar for a Python string."""
    if ('\n' in s or
            _NEEDS_QUOTE.search(s) or
            s.lower() in {k.lower() for k in _YAML_KEYWORDS} or
            _LOOKS_NUMERIC.match(s)):
        escaped = (s.replace('\\', '\\\\')
                    .replace('"', '\\"')
                    .replace('\n', '\\n')
                    .replace('\r', '\\r')
                    .replace('\t', '\\t'))
        return f'"{escaped}"'
    return s


def _dump(obj, indent):
    pad = '  ' * indent
    if obj is None:
        return 'null'
    if isinstance(obj, bool):
        return 'true' if obj else 'false'
    if isinstance(obj, int):
        return str(obj)
    if isinstance(obj, float):
        s = repr(obj)
        return s
    if isinstance(obj, str):
        return _yaml_str(obj)
    if isinstance(obj, dict):
        if not obj:
            return '{}'
        lines = []
        for k, v in obj.items():
            ky = _yaml_str(str(k))
            if isinstance(v, dict) and v:
                lines.append(f'{pad}{ky}:')
                lines.append(_dump(v, indent + 1))
            elif isinstance(v, list) and v:
                lines.append(f'{pad}{ky}:')
                lines.append(_dump(v, indent + 1))
            else:
                lines.append(f'{pad}{ky}: {_dump(v, indent + 1)}')
        return '\n'.join(lines)
    if isinstance(obj, list):
        if not obj:
            return '[]'
        lines = []
        for item in obj:
            if isinstance(item, dict) and item:
                # Inline first key after "- ", indent rest to align
                inner_str = _dump(item, indent + 1)
                inner_lines = inner_str.split('\n')
                # Strip the leading indent from the first line so it sits after "- "
                first = inner_lines[0].lstrip()
                rest = '\n'.join(inner_lines[1:])
                if rest:
                    lines.append(f'{pad}- {first}')
                    lines.append(rest)
                else:
                    lines.append(f'{pad}- {first}')
            elif isinstance(item, list) and item:
                lines.append(f'{pad}-')
                lines.append(_dump(item, indent + 1))
            else:
                lines.append(f'{pad}- {_dump(item, indent + 1)}')
        return '\n'.join(lines)
    raise TypeError(f'Unsupported type for YAML serialisation: {type(obj)}')


def to_yaml_str(obj):
    return '---\n' + _dump(obj, 0) + '\n'


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    spec = build_spec()

    # ── JSON ──────────────────────────────────────────────────────────────────
    json_path = os.path.join(OUT_DIR, "openapi.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(spec, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"[generate-openapi] written: {os.path.relpath(json_path, ROOT_DIR)}")

    # ── YAML ──────────────────────────────────────────────────────────────────
    yaml_path = os.path.join(OUT_DIR, "openapi.yaml")
    try:
        import yaml  # type: ignore
        with open(yaml_path, "w", encoding="utf-8") as f:
            yaml.dump(spec, f, default_flow_style=False, allow_unicode=True, sort_keys=False,
                      width=120, indent=2)
        print(f"[generate-openapi] written: {os.path.relpath(yaml_path, ROOT_DIR)} (via PyYAML)")
    except ImportError:
        # Fall back to the built-in serialiser
        try:
            yaml_content = to_yaml_str(spec)
            with open(yaml_path, "w", encoding="utf-8") as f:
                f.write(yaml_content)
            print(f"[generate-openapi] written: {os.path.relpath(yaml_path, ROOT_DIR)} (built-in serialiser)")
        except Exception as e:
            print(f"[generate-openapi] YAML write failed: {e}", file=sys.stderr)
            print("[generate-openapi] Install PyYAML for better YAML output: pip install pyyaml",
                  file=sys.stderr)
            sys.exit(1)

    print("[generate-openapi] done — rebuild the app to embed updated specs in the JAR")


if __name__ == "__main__":
    main()
