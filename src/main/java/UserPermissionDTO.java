import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPermissionDTO {
    @JsonProperty("scope_uuid")
    private String scopeUUID;

    public String getScopeUUID() {
        return scopeUUID;
    }
}
