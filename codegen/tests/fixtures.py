"""Small synthetic OpenAPI documents for the generator tests (independent of openapi/)."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

VERIFICATION: dict[str, Any] = {
    "openapi": "3.0.0",
    "info": {"title": "Test", "version": "1"},
    "paths": {
        "/v3/pets/": {
            "get": {
                "summary": "List pets",
                "parameters": [
                    {"name": "limit", "in": "query", "schema": {"type": "integer"}},
                    {"name": "offset", "in": "query", "schema": {"type": "integer"}},
                    {"name": "kind", "in": "query", "schema": {"type": "string", "enum": ["cat", "dog"]}},
                ],
                "responses": {
                    "200": {
                        "description": "",
                        "content": {
                            "application/json": {
                                "schema": {
                                    "type": "object",
                                    "properties": {
                                        "count": {"type": "integer"},
                                        "next": {"type": "string", "nullable": True},
                                        "previous": {"type": "string", "nullable": True},
                                        "results": {"type": "array", "items": {"$ref": "#/components/schemas/Pet"}},
                                    },
                                }
                            }
                        },
                    }
                },
            },
            "post": {
                "summary": "Create a pet",
                "parameters": [
                    {"name": "Idempotency-Key", "in": "header", "schema": {"type": "string"}},
                    {"name": "node_id", "in": "query", "schema": {"type": "string"}},
                ],
                "requestBody": {
                    "required": True,
                    "content": {
                        "multipart/form-data": {
                            "schema": {
                                "type": "object",
                                "required": ["name"],
                                "properties": {
                                    "name": {"type": "string"},
                                    "node_id": {"type": "string"},
                                    "photo": {"type": "string", "format": "binary"},
                                    "tags": {"type": "array", "items": {"type": "string"}},
                                },
                            }
                        },
                        "application/json": {
                            "schema": {
                                "type": "object",
                                "required": ["name"],
                                "properties": {"name": {"type": "string"}, "node_id": {"type": "string"}},
                            }
                        },
                    },
                },
                "responses": {
                    "200": {"description": "", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Pet"}}}},
                    "202": {
                        "description": "",
                        "content": {"application/json": {"schema": {"type": "object", "properties": {"job_id": {"type": "string"}}}}},
                    },
                },
            },
        },
        "/v3/pets/{pet_id}/": {
            "parameters": [{"name": "pet_id", "in": "path", "required": True, "schema": {"type": "string"}}],
            "get": {
                "responses": {
                    "200": {
                        "description": "",
                        "content": {"application/json": {"schema": {"$ref": "#/components/schemas/PetOrRow"}}},
                    }
                }
            },
            "delete": {"responses": {"204": {"description": ""}}},
        },
        "/v3/pets/{pet_id}/report/": {
            "get": {
                "parameters": [{"name": "pet_id", "in": "path", "required": True, "schema": {"type": "string"}}],
                "responses": {"200": {"description": "", "content": {"application/pdf": {"schema": {"type": "string", "format": "binary"}}}}},
            }
        },
        "/v3/device/": {"post": {"responses": {"200": {"description": ""}}}},
    },
    "components": {
        "schemas": {
            "Pet": {
                "type": "object",
                "required": ["name", "builder"],
                "properties": {
                    "name": {"type": "string"},
                    "class": {"type": "string"},
                    "builder": {"type": "string"},
                    "status": {"type": "string", "enum": ["Not Started", "Approved", "approved"], "nullable": True},
                    "born_at": {"type": "string", "format": "date-time"},
                    "owner": {"type": "object", "properties": {"name": {"type": "string"}, "pet": {"type": "string"}}},
                    "extra": {"type": "object", "additionalProperties": True},
                    "scores": {"type": "object", "additionalProperties": {"type": "number"}},
                },
            },
            "PetOrRow": {
                "oneOf": [
                    {"type": "object", "properties": {"kind": {"type": "string", "enum": ["pet"]}, "name": {"type": "string"}}, "required": ["name"]},
                    {"type": "object", "properties": {"kind": {"type": "string", "enum": ["row"]}, "name": {"type": "string"}, "row": {"type": "integer"}}},
                ]
            },
            "Base": {"type": "object", "properties": {"id": {"type": "string"}}},
            "Child": {"allOf": [{"$ref": "#/components/schemas/Base"}, {"type": "object", "properties": {"age": {"type": "integer"}}}]},
        }
    },
}

ACCOUNT: dict[str, Any] = {
    "openapi": "3.0.0",
    "info": {"title": "Account", "version": "1"},
    "paths": {
        "/programmatic/login/": {
            "post": {
                "requestBody": {
                    "content": {"application/json": {"schema": {"type": "object", "properties": {"email": {"type": "string"}}}}}
                },
                "responses": {"200": {"description": "", "content": {"application/json": {"schema": {"type": "object", "properties": {"access_token": {"type": "string"}}}}}}},
            }
        }
    },
    "components": {"schemas": {}},
}

OVERLAY: dict[str, Any] = {
    "description": "test",
    "version": 1,
    "operations": {
        "GET /v3/pets/": {"resource": "pets", "method": "list", "auth": "api_key", "server": True},
        "POST /v3/pets/": {"resource": "pets", "method": "create", "auth": "api_key", "server": True},
        "GET /v3/pets/{pet_id}/": {"resource": "pets", "method": "retrieve", "auth": "api_key", "server": True},
        "DELETE /v3/pets/{pet_id}/": {"resource": "pets", "method": "delete", "auth": "api_key", "server": True},
        "GET /v3/pets/{pet_id}/report/": {"resource": "pets.reports", "method": "download", "auth": "api_key", "server": True},
        "POST /v3/device/": {"resource": "device", "method": "send", "auth": "device_token", "server": False},
        "account POST /programmatic/login/": {"resource": "account", "method": "login", "auth": "none", "server": True},
    },
}


def write(root: Path, verification: dict[str, Any] | None = None, account: dict[str, Any] | None = None,
          overlay: dict[str, Any] | None = None) -> Path:
    openapi = root / "openapi"
    openapi.mkdir(parents=True, exist_ok=True)
    (openapi / "verification.json").write_text(json.dumps(verification or VERIFICATION), encoding="utf-8")
    (openapi / "account.json").write_text(json.dumps(account or ACCOUNT), encoding="utf-8")
    (openapi / "overlay.json").write_text(json.dumps(overlay or OVERLAY, indent=1), encoding="utf-8")
    (openapi / "SOURCE.json").write_text(json.dumps({"verification.json": {}, "account.json": {}}), encoding="utf-8")
    (root / "gradle.properties").write_text("version=0.3.0\n", encoding="utf-8")
    return root


def clone(value: dict[str, Any]) -> dict[str, Any]:
    return copy.deepcopy(value)
