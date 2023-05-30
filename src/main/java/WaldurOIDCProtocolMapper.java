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

    private static final String API_URL_KEY = "url.waldur.api.value";
    private static final String OFFERING_UUID_KEY = "uuid.waldur.offering.value";
    private static final String API_TOKEN_KEY = "token.waldur.value";
    private static final String CLAIM_NAME_KEY = "claim.name";

    static {
        ProviderConfigProperty urlProperty = new ProviderConfigProperty();
        urlProperty.setName(API_URL_KEY);
        urlProperty.setLabel("Waldur API URL");
        urlProperty.setType("String");
        urlProperty.setHelpText(
                "URL to the Waldur API including trailing backslash, e.g. https://waldur.example.com/api/");
        configProperties.add(urlProperty);

        ProviderConfigProperty offeringUuidProperty = new ProviderConfigProperty();
        offeringUuidProperty.setName(OFFERING_UUID_KEY);
        offeringUuidProperty.setLabel("Waldur Offering UUID");
        offeringUuidProperty.setType("String");
        offeringUuidProperty.setHelpText("UUID of the offering in Waldur");
        configProperties.add(offeringUuidProperty);

        ProviderConfigProperty waldurTokenProperty = new ProviderConfigProperty();
        waldurTokenProperty.setName(API_TOKEN_KEY);
        waldurTokenProperty.setLabel("Waldur API token");
        waldurTokenProperty.setType("String");
        waldurTokenProperty.setHelpText("Token for Waldur API");
        configProperties.add(waldurTokenProperty);

        ProviderConfigProperty claimNameProperty = new ProviderConfigProperty();
        claimNameProperty.setName(CLAIM_NAME_KEY);
        claimNameProperty.setLabel("Claim name");
        claimNameProperty.setType("String");
        claimNameProperty.setHelpText("Claim name. e.g. preferred_username");
        configProperties.add(claimNameProperty);

        jacksonMapper = new ObjectMapper();
    }

    @Override
    public AccessToken transformUserInfoToken(
            AccessToken token,
            ProtocolMapperModel mappingModel,
            KeycloakSession session,
            UserSessionModel userSession,
            ClientSessionContext clientSessionCtx) {

        String waldurUserUsername = userSession.getUser().getUsername();

        Map<String, String> config = mappingModel.getConfig();
        final String waldurUrl = config.get(API_URL_KEY);
        final String offeringUuid = config.get(OFFERING_UUID_KEY);
        final String waldurToken = config.get(API_TOKEN_KEY);
        final String claimName = config.get(CLAIM_NAME_KEY);

        final String waldurEndpoint = waldurUrl
                .concat("marketplace-offering-users/?")
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

        token.getOtherClaims().put(claimName, offeringUserDTO.getUsername());

        setClaim(token, mappingModel, userSession, session, clientSessionCtx);
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
                    new TypeReference<List<OfferingUserDTO>>() {
                    });

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
            String claimName,
            boolean accessToken,
            boolean idToken,
            boolean userInfo) {
        ProtocolMapperModel mapper = new ProtocolMapperModel();
        mapper.setName(name);
        mapper.setProtocolMapper(PROVIDER_ID);
        mapper.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);

        Map<String, String> config = new HashMap<String, String>();
        config.put(API_URL_KEY, url);
        config.put(OFFERING_UUID_KEY, offeringUuid);
        config.put(API_TOKEN_KEY, apiToken);
        config.put(CLAIM_NAME_KEY, claimName);

        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, Boolean.toString(accessToken));
        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ID_TOKEN, Boolean.toString(idToken));
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
