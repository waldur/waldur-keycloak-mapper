import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPermissionDTO {
    @JsonProperty("scope_uuid")
    private UUID scopeUUID;

    public UUID getScopeUUID() {
        return scopeUUID;
    }
}
