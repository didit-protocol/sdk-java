package me.didit.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The description of one API call, built by the generated resources. Not meant for direct use; its
 * shape may change between minor versions.
 */
public final class ApiRequest {
  /** Which API the call goes to. */
  public enum Api {
    /** {@code https://verification.didit.me}. */
    VERIFICATION,
    /** {@code https://apx.didit.me/auth/v2}. */
    ACCOUNT
  }

  /** The credentials the call sends. */
  public enum Auth {
    /** {@code x-api-key}. */
    API_KEY,
    /** {@code Authorization: Bearer} with the account token. */
    ACCOUNT_TOKEN,
    /** No credentials. */
    NONE
  }

  /** How the parameters that are not query or header parameters are sent. */
  public enum BodyEncoding {
    /** No body. */
    NONE,
    /** {@code application/json}. */
    JSON,
    /** {@code multipart/form-data}. */
    MULTIPART,
    /** Multipart when a file is set, JSON otherwise. */
    JSON_OR_MULTIPART
  }

  final String method;
  final String pathTemplate;
  final Api api;
  final Auth auth;
  final boolean applicationScoped;
  final Map<String, String> pathParams;
  final @Nullable JsonModel params;
  final Map<String, String> queryFields;
  final Map<String, String> headerFields;
  final BodyEncoding bodyEncoding;
  final @Nullable String accept;

  private ApiRequest(Builder b) {
    this.method = b.method;
    this.pathTemplate = b.pathTemplate;
    this.api = b.api;
    this.auth = b.auth;
    this.applicationScoped = b.applicationScoped;
    this.pathParams = Collections.unmodifiableMap(new LinkedHashMap<>(b.pathParams));
    this.params = b.params;
    this.queryFields = Collections.unmodifiableMap(new LinkedHashMap<>(b.queryFields));
    this.headerFields = Collections.unmodifiableMap(new LinkedHashMap<>(b.headerFields));
    this.bodyEncoding = b.params == null ? BodyEncoding.NONE : b.bodyEncoding;
    this.accept = b.accept;
  }

  /**
   * Starts a request description.
   *
   * @param method the HTTP method
   * @param pathTemplate the path with {@code {name}} placeholders
   * @return a new builder
   */
  public static Builder builder(String method, String pathTemplate) {
    return new Builder(method, pathTemplate);
  }

  /** {@code METHOD /path/template}, for messages and logs. */
  String describe() {
    return method + " " + pathTemplate;
  }

  boolean idempotent() {
    return method.equals("GET")
        || method.equals("HEAD")
        || method.equals("PUT")
        || method.equals("DELETE")
        || method.equals("OPTIONS");
  }

  /** Builds an {@link ApiRequest}. */
  public static final class Builder {
    private final String method;
    private final String pathTemplate;
    private Api api = Api.VERIFICATION;
    private Auth auth = Auth.API_KEY;
    private boolean applicationScoped;
    private final Map<String, String> pathParams = new LinkedHashMap<>();
    private @Nullable JsonModel params;
    private final Map<String, String> queryFields = new LinkedHashMap<>();
    private final Map<String, String> headerFields = new LinkedHashMap<>();
    private BodyEncoding bodyEncoding = BodyEncoding.NONE;
    private @Nullable String accept;

    private Builder(String method, String pathTemplate) {
      this.method = Objects.requireNonNull(method, "method");
      this.pathTemplate = Objects.requireNonNull(pathTemplate, "pathTemplate");
    }

    /**
     * Sends the call to the account management API.
     *
     * @return this builder
     */
    public Builder account() {
      this.api = Api.ACCOUNT;
      return this;
    }

    /**
     * Sets the credentials.
     *
     * @param auth the credentials kind
     * @return this builder
     */
    public Builder auth(Auth auth) {
      this.auth = auth;
      return this;
    }

    /**
     * Fills {@code organization_id} and {@code application_id} from the options.
     *
     * @return this builder
     */
    public Builder applicationScoped() {
      this.applicationScoped = true;
      return this;
    }

    /**
     * Sets a path parameter. Its value is percent-encoded as one path segment.
     *
     * @param name the placeholder name
     * @param value the value
     * @return this builder
     */
    public Builder pathParam(String name, String value) {
      pathParams.put(name, Objects.requireNonNull(value, name));
      return this;
    }

    /**
     * Sets the parameters object; its query and header fields are named separately.
     *
     * @param params the parameters, or {@code null} for none
     * @return this builder
     */
    public Builder params(@Nullable JsonModel params) {
      this.params = params;
      return this;
    }

    /**
     * Names the parameter fields sent in the query string.
     *
     * @param fields JSON field names
     * @return this builder
     */
    public Builder queryFields(String... fields) {
      for (String field : fields) {
        queryFields.put(field, field);
      }
      return this;
    }

    /**
     * Names a parameter field sent in the query string under another name.
     *
     * @param name the query parameter name
     * @param field the JSON field name in the parameters
     * @return this builder
     */
    public Builder queryField(String name, String field) {
      queryFields.put(name, field);
      return this;
    }

    /**
     * Names a parameter field sent as a header.
     *
     * @param header the header name
     * @param field the JSON field name
     * @return this builder
     */
    public Builder headerField(String header, String field) {
      headerFields.put(header, field);
      return this;
    }

    /**
     * Sets the body encoding of the remaining parameter fields.
     *
     * @param encoding the encoding
     * @return this builder
     */
    public Builder body(BodyEncoding encoding) {
      this.bodyEncoding = encoding;
      return this;
    }

    /**
     * Expects a binary response of this content type.
     *
     * @param contentType for example {@code application/pdf}
     * @return this builder
     */
    public Builder accept(String contentType) {
      this.accept = contentType;
      return this;
    }

    /**
     * Builds the description.
     *
     * @return the request
     */
    public ApiRequest build() {
      return new ApiRequest(this);
    }
  }
}
