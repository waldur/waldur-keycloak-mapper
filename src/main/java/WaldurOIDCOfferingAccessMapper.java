import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpHeaders;
import org.jboss.logging.Logger;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import com.fasterxml.jackson.databind.ObjectMapper;

public class WaldurOIDCOfferingAccessMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    private static final String GROUP_NAME = "Viewers";

    public static final String PROVIDER_ID = "oidc-waldur-group-mapper";

    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();

    private static final Logger LOGGER = Logger.getLogger(WaldurOIDCOfferingAccessMapper.class.getName());

    private static final String API_URL_KEY = "url.waldur.api.value";
    private static final String API_TOKEN_KEY = "token.waldur.value";
    private static final String OFFERING_UUID_KEY = "uuid.waldur.offering.value";
    private static final String PROVIDER_UUID_KEY = "uuid.waldur.provider.value";
    private static final String GROUP_NAME_KEY = "name.keycloak.group.value";


    static {
        ProviderConfigProperty property;

        property = new ProviderConfigProperty(
            API_URL_KEY,
            "Waldur API URL",
            "URL to the Waldur API including trailing backslash, e.g. https://waldur.example.com/api/",
            ProviderConfigProperty.STRING_TYPE,
            ""
        );
        configProperties.add(property);

        property = new ProviderConfigProperty(
            PROVIDER_UUID_KEY,
            "Waldur provider UUID",
            "UUID of the provider in Waldur",
            ProviderConfigProperty.STRING_TYPE,
            ""
        );
        configProperties.add(property);

        property = new ProviderConfigProperty(
            OFFERING_UUID_KEY,
            "Waldur offering UUID",
            "UUID of the offering in Waldur",
            ProviderConfigProperty.STRING_TYPE,
            ""
        );
        configProperties.add(property);

        property = new ProviderConfigProperty(
            API_TOKEN_KEY,
            "Waldur API token",
            "Token for Waldur API",
            ProviderConfigProperty.STRING_TYPE,
            ""
        );
        configProperties.add(property);

        property = new ProviderConfigProperty(
            GROUP_NAME_KEY,
            "Keycloak group name.",
            "Name of the precreated group in Keycloak.",
            ProviderConfigProperty.STRING_TYPE,
            ""
        );
        configProperties.add(property);

        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, WaldurOIDCOfferingAccessMapper.class);
    }

    private boolean hasRelatedOfferingUser(String waldurUrl, String providerUuid, String waldurToken, String username) {
        HttpClient client = HttpClient.newHttpClient();
        try {
            String waldurEndpoint = waldurUrl
                    .concat("marketplace-service-providers/")
                    .concat(providerUuid)
                    .concat("/users/");

            URI uri = new URI(waldurEndpoint);

            LOGGER.info(uri);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .setHeader(HttpHeaders.AUTHORIZATION, String.format("Token %s", waldurToken))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.error(String.format("The status code is %s", response.statusCode()));
            } else {
                ObjectMapper mapper = new ObjectMapper();
                OfferingUserDTO[] users = mapper.readValue(response.body(), OfferingUserDTO[].class);

                boolean found = Arrays.stream(users).anyMatch(x -> username.equals(x.getUsername()));
                return found;
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
        return false;
    }

    private boolean hasAccessToResource(String waldurUrl, String offeringUuid, String waldurToken, String username) {
        if (offeringUuid.equals("")){
            LOGGER.error("Offering UUID is empty, skipping resource access check");
            return false;
        }

        HttpClient client = HttpClient.newHttpClient();
        try {
            String waldurEndpoint = waldurUrl
                    .concat("marketplace-provider-offerings/")
                    .concat(offeringUuid)
                    .concat("/user_has_resource_access/?username=")
                    .concat(username);
            URI uri = new URI(waldurEndpoint);

            LOGGER.info(waldurEndpoint);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .setHeader(HttpHeaders.AUTHORIZATION, String.format("Token %s", waldurToken))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.error(String.format("The status code is %s", response.statusCode()));
            } else {
                ObjectMapper mapper = new ObjectMapper();
                UserHasAccessDTO userHasAccess = mapper.readValue(response.body(), UserHasAccessDTO.class);
                boolean result = userHasAccess.getHasAccess();
                LOGGER.info(String.format("User has resource access: %s", result));
                return result;
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
        return false;
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
            KeycloakSession keycloakSession,
            ClientSessionContext clientSessionCtx) {

        String username = userSession.getUser().getUsername();

        final String waldurUrl = mappingModel.getConfig().get(API_URL_KEY);
        final String providerUuid = mappingModel.getConfig().get(PROVIDER_UUID_KEY);
        final String offeringUuid = mappingModel.getConfig().get(OFFERING_UUID_KEY);
        final String groupName = mappingModel.getConfig().get(GROUP_NAME_KEY);
        final String waldurToken = mappingModel.getConfig().get(API_TOKEN_KEY);

        boolean hasRelatedOfferingUser = this.hasRelatedOfferingUser(waldurUrl, providerUuid, waldurToken, username);
        if (!hasRelatedOfferingUser)
            return;
        boolean hasAccessToResoure = this.hasAccessToResource(waldurUrl, offeringUuid, waldurToken, username);
        final String groupName = config.get(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME);

        token.getOtherClaims().put(claimName, username);
        if (hasAccessToResoure)
            token.getOtherClaims().put("groups", GROUP_NAME);
    }

    public static ProtocolMapperModel create(
            String name,
            String url,
            String providerUuid,
            String offeringUuid,
            String apiToken,
            String groupName,
            boolean accessToken,
            boolean idToken,
            boolean userInfo) {
        ProtocolMapperModel mapper = new ProtocolMapperModel();
        mapper.setName(name);
        mapper.setProtocolMapper(PROVIDER_ID);
        mapper.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);

        Map<String, String> config = new HashMap<String, String>();
        config.put(API_URL_KEY, url);
        config.put(PROVIDER_UUID_KEY, providerUuid);
        config.put(API_TOKEN_KEY, apiToken);
        config.put(GROUP_NAME_KEY, groupName);

        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, Boolean.toString(accessToken));
        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, Boolean.toString(accessToken));
        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_USERINFO, Boolean.toString(userInfo));

        mapper.setConfig(config);
        return mapper;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getDisplayType() {
        return "Waldur group mapper";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Mapper for group from Waldur";
    }
}
