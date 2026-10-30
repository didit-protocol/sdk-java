"""Name conversion from the spec's snake_case to Java idiom (DESIGN 4 and 5.1)."""

from __future__ import annotations

import re
import unicodedata

JAVA_KEYWORDS = frozenset(
    """
    abstract assert boolean break byte case catch char class const continue default do double
    else enum extends final finally float for goto if implements import instanceof int interface
    long native new package private protected public return short static strictfp super switch
    synchronized this throw throws transient try void volatile while true false null var yield
    record sealed permits non-sealed when _
    """.split()
)

# Methods every generated model, union or builder already has. A property whose
# Java name equals one of these gets a trailing underscore.
RESERVED_MODEL_METHODS = frozenset(
    """
    equals hashCode toString getClass notify notifyAll wait clone finalize
    builder toBuilder fromJson build validate
    """.split()
)

# Simple names used unqualified inside generated files. A nested class must not
# shadow them.
RESERVED_TYPE_NAMES = frozenset(
    """
    Builder Optional List Map Set String Long Double Boolean Integer Object Override Deprecated
    Nullable JsonNode ObjectNode Json Conv JsonModel JsonUnion StringEnum MultiStatusResponse
    FileUpload OffsetDateTime LocalDate Collections Objects Class Void Iterable Function
    CompletableFuture RequestOptions RawResponse Page BinaryResponse ApiClient ApiRequest
    Responses NullMarked Generated Arrays Math System Thread Exception RuntimeException
    """.split()
)


def words(name: str) -> list[str]:
    """Splits an identifier or free text into words, keeping inner camelCase."""
    text = unicodedata.normalize("NFKD", name)
    text = "".join(ch for ch in text if ord(ch) < 128)
    parts = re.split(r"[^A-Za-z0-9]+", text)
    out: list[str] = []
    for part in parts:
        if not part:
            continue
        # Split lowerUpper boundaries ("lastName" -> "last", "Name"), keep acronyms.
        out.extend(re.findall(r"[A-Z]+(?=[A-Z][a-z]|[0-9]|$)|[A-Z]?[a-z]+|[A-Z]+|[0-9]+", part))
    return out


def pascal(name: str) -> str:
    """``contact_details`` -> ``ContactDetails``; ``SessionDecision`` stays as is."""
    if re.fullmatch(r"[A-Z][A-Za-z0-9]*", name):
        return name
    result = "".join(w[:1].upper() + w[1:] for w in words(name))
    if not result:
        return "Value"
    if result[0].isdigit():
        result = "V" + result
    return result


def camel(name: str) -> str:
    """``session_id`` -> ``sessionId``; ``lastName`` stays as is."""
    ws = words(name)
    if not ws:
        return "value"
    first = ws[0]
    first = first.lower() if first.isupper() and len(first) > 1 else first[:1].lower() + first[1:]
    result = first + "".join(w[:1].upper() + w[1:] for w in ws[1:])
    if result[0].isdigit():
        result = "_" + result
    return result


def constant(value: str) -> str:
    """Enum constant for a value: ``Not Started`` -> ``NOT_STARTED``."""
    ws = words(value)
    if not ws:
        return "EMPTY" if value == "" else "VALUE"
    result = "_".join(w.upper() for w in ws)
    if result[0].isdigit():
        result = "_" + result
    return result


def safe_member(name: str) -> str:
    """A Java method or parameter name that is not a keyword or a reserved method."""
    if name in JAVA_KEYWORDS or name in RESERVED_MODEL_METHODS:
        return name + "_"
    return name


OBJECT_METHODS = frozenset("equals hashCode toString getClass notify notifyAll wait clone finalize".split())


def safe_method(name: str) -> str:
    """A resource method name: not a keyword and not a method of java.lang.Object."""
    if name in JAVA_KEYWORDS or name in OBJECT_METHODS:
        return name + "_"
    return name


def safe_param(name: str) -> str:
    """A Java parameter name that is not a keyword and not used by generated code."""
    if name in JAVA_KEYWORDS or name in ("params", "options", "client"):
        return name + "_"
    return name


def resource_class(resource: str) -> str:
    """``sessions.imports`` -> ``SessionsImportsResource``."""
    return "".join(pascal(seg) for seg in resource.split(".")) + "Resource"


def type_prefix(resource: str, method: str) -> str:
    """``sessions.imports`` + ``create`` -> ``SessionsImportsCreate`` (DESIGN 5.1)."""
    return "".join(pascal(seg) for seg in resource.split(".")) + pascal(method)
