import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.mappers.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.AccessToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

public class WaldurOIDCProtocolMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    public static final String PROVIDER_ID = "oidc-waldurusernamemapper";

    private static final List<ProviderConfigProperty> configProperties = new ArrayList<ProviderConfigProperty>();

    private static final Logger LOGGER = Logger.getLogger(WaldurOIDCProtocolMapper.class.getName());

    private static final ObjectMapper jacksonMapper;

    static {
        ProviderConfigProperty urlProperty = new ProviderConfigProperty();
        urlProperty.setName("url.waldur.api.value");
        urlProperty.setLabel("Waldur API URL");
        urlProperty.setType("String");
        urlProperty.setHelpText("URL to the Waldur API including trailing backslash, e.g. https://waldur.example.com/api/");
        configProperties.add(urlProperty);

        ProviderConfigProperty offeringUuidProperty = new ProviderConfigProperty();
        offeringUuidProperty.setName("uuid.waldur.offering.value");
        offeringUuidProperty.setLabel("Waldur Offering UUID");
        offeringUuidProperty.setType("String");
        offeringUuidProperty.setHelpText("UUID of the offering in Waldur");
        configProperties.add(offeringUuidProperty);

        ProviderConfigProperty waldurTokenProperty = new ProviderConfigProperty();
        waldurTokenProperty.setName("token.waldur.value");
        waldurTokenProperty.setLabel("Waldur API token");
        waldurTokenProperty.setType("String");
        waldurTokenProperty.setHelpText("Token for Waldur API");
        configProperties.add(waldurTokenProperty);

        jacksonMapper = new ObjectMapper();
    }

    public AccessToken transformAccessToken(
            AccessToken token,
            ProtocolMapperModel mappingModel,
            KeycloakSession keycloakSession,
            UserSessionModel userSession,
            ClientSessionContext clientSessionCtx) {

        String waldurUserUsername = userSession.getUser().getUsername();

        final String waldurUrl = mappingModel.getConfig().get("url.waldur.api.value");
        final String offeringUuid = mappingModel.getConfig().get("uuid.waldur.offering.value");
        final String waldurToken = mappingModel.getConfig().get("token.waldur.value");

        final String waldurEndpoint = waldurUrl
                .concat("/marketplace-offering-users/?")
                .concat("offering_uuid=")
                .concat(offeringUuid)
                .concat("&user_username=")
                .concat(waldurUserUsername)
                .concat("&field=username");

        LOGGER.info(String.format("Processing user %s", waldurUserUsername));
        LOGGER.info(String.format("Waldur URL: %s", waldurEndpoint));

        List<OfferingUserDTO> offeringUserDTOList = fetchUsernames(waldurEndpoint, waldurToken);

        if (offeringUserDTOList.isEmpty()) {
            LOGGER.error("Unable to retrieve a username.");
            return token;
        }

        OfferingUserDTO offeringUserDTO = offeringUserDTOList.get(0);

        LOGGER.info(String.format("Waldur preferred username: %s", offeringUserDTO.getUsername()));

        token.getOtherClaims().put("preferredUsername", offeringUserDTO.getUsername());

        setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);
        return token;
    }

    private List<OfferingUserDTO> fetchUsernames(String url, String waldurToken) {
        HttpGet request = new HttpGet(url);
        request.addHeader(HttpHeaders.AUTHORIZATION, String.format("Token %s", waldurToken));

        try (CloseableHttpClient httpClient = HttpClients.createDefault();
                CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();

            LOGGER.info(String.format("Status Code: %s", statusCode));
            if (statusCode != 200) {
                LOGGER.error(String.format("The status code is %s", statusCode));
                return Collections.emptyList();
            }

            HttpEntity entity = response.getEntity();
            if (entity == null) {
                LOGGER.error("Unable to get entity from the response");
                return Collections.emptyList();
            }

            String result = EntityUtils.toString(entity);
            List<OfferingUserDTO> offeringUsers = jacksonMapper.readValue(
                result,
                new TypeReference<List<OfferingUserDTO>>() {}
            );

            return offeringUsers;
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    public static ProtocolMapperModel create(
            String name,
            String url,
            String offeringUuid,
            String apiToken,
            boolean accessToken,
            boolean idToken,
            boolean userInfo) {
        ProtocolMapperModel mapper = new ProtocolMapperModel();
        mapper.setName(name);
        mapper.setProtocolMapper(PROVIDER_ID);
        mapper.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);

        Map<String, String> config = new HashMap<String, String>();
        config.put("url.waldur.api.value", url);
        config.put("uuid.waldur.offering.value", offeringUuid);
        config.put("token.waldur.value", apiToken);

        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, "true");
        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ID_TOKEN, "true");

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
        return "Waldur preferred username mapper";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Mapper for preferred username from Waldur";
    }
}
