import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
class UserHasAccessDTO {
    @JsonProperty("has_access")
    private boolean hasAccess;

    public boolean getHasAccess() {
        return this.hasAccess;
    }
}
