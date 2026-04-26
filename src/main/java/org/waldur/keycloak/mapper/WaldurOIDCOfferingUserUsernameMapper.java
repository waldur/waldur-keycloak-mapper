package org.waldur.keycloak.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.mappers.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

public class WaldurOIDCOfferingUserUsernameMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    public static final String PROVIDER_ID = "oidc-waldurusernamemapper";

    private static final List<ProviderConfigProperty> configProperties = new ArrayList<ProviderConfigProperty>();

    private static final Logger LOGGER = Logger.getLogger(WaldurOIDCOfferingUserUsernameMapper.class.getName());

    private static final ObjectMapper jacksonMapper;

    private static final String API_URL_KEY = "url.waldur.api.value";
    private static final String OFFERING_UUID_KEY = "uuid.waldur.offering.value";
    private static final String API_TOKEN_KEY = "token.waldur.value";
    private static final String API_TLS_VALIDATE_KEY = "tls.waldur.validate";

    static {
        ProviderConfigProperty urlProperty = new ProviderConfigProperty(
                API_URL_KEY,
                "Waldur API URL",
                "URL to the Waldur API including trailing backslash, e.g. https://waldur.example.com/api/",
                ProviderConfigProperty.STRING_TYPE,
                "");
        configProperties.add(urlProperty);

        ProviderConfigProperty offeringUuidProperty = new ProviderConfigProperty(
                OFFERING_UUID_KEY,
                "Waldur Offering UUID",
                "UUID of the offering in Waldur",
                ProviderConfigProperty.STRING_TYPE,
                "");
        configProperties.add(offeringUuidProperty);

        ProviderConfigProperty waldurTokenProperty = new ProviderConfigProperty(
                API_TOKEN_KEY,
                "Waldur API token",
                "Token for Waldur API",
                ProviderConfigProperty.STRING_TYPE,
                "");
        configProperties.add(waldurTokenProperty);

        ProviderConfigProperty tlsValidationProperty = new ProviderConfigProperty(
                API_TLS_VALIDATE_KEY,
                "TLS validation enabled",
                "Validate the Waldur API server certificate. When disabled, both the certificate chain and hostname are accepted unconditionally — use only for testing or with self-signed certs.",
                ProviderConfigProperty.BOOLEAN_TYPE,
                false);
        configProperties.add(tlsValidationProperty);

        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, WaldurOIDCOfferingUserUsernameMapper.class);

        jacksonMapper = new ObjectMapper();
    }

    static String buildOfferingUserUrl(String waldurUrl, String offeringUuid, String username) {
        return waldurUrl
                + "marketplace-offering-users/?"
                + "offering_uuid=" + URLEncoder.encode(offeringUuid, StandardCharsets.UTF_8)
                + "&user_username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&field=username";
    }

    private List<OfferingUserDTO> fetchUsernames(String url, String waldurToken, boolean tlsValidationEnabled) {
        String body = new WaldurHttpClient(waldurToken, tlsValidationEnabled).get(url);
        if (body.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return jacksonMapper.readValue(body, new TypeReference<List<OfferingUserDTO>>() {});
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private void transformToken(
            IDToken token,
            Map<String, String> config,
            UserSessionModel userSession) {
        final String waldurUrl = config.get(API_URL_KEY);
        final String offeringUuid = config.get(OFFERING_UUID_KEY);
        final String waldurToken = config.get(API_TOKEN_KEY);
        final boolean tlsValidationEnabled = Boolean.parseBoolean(config.get(API_TLS_VALIDATE_KEY));

        String waldurUserUsername = userSession.getUser().getUsername();

        final String waldurEndpoint = buildOfferingUserUrl(waldurUrl, offeringUuid, waldurUserUsername);

        LOGGER.info(String.format("Processing user %s", waldurUserUsername));
        LOGGER.info(String.format("Waldur URL: %s", waldurEndpoint));

        List<OfferingUserDTO> offeringUserDTOList = fetchUsernames(waldurEndpoint, waldurToken, tlsValidationEnabled);

        if (offeringUserDTOList.isEmpty()) {
            LOGGER.error(String.format("Unable to retrieve a username."));
            return;
        }

        OfferingUserDTO offeringUserDTO = offeringUserDTOList.get(0);
        String username = offeringUserDTO.getUsername();

        LOGGER.info(String.format("Waldur preferred username: %s", username));

        final String claimName = config.get(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME);

        token.getOtherClaims().put(claimName, username);
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
            KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {
        Map<String, String> config = mappingModel.getConfig();

        this.transformToken(token, config, userSession);
    }

    public static ProtocolMapperModel create(
            String name,
            String url,
            String offeringUuid,
            String apiToken,
            String claimName,
            boolean tlsValidationEnabled,
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
        config.put(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, claimName);
        config.put(API_TLS_VALIDATE_KEY, Boolean.toString(tlsValidationEnabled));

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
