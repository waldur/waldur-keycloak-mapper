import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
class OfferingUserDTO {
    private String username;

    public String getUsername() {
        return this.username;
    }
}
