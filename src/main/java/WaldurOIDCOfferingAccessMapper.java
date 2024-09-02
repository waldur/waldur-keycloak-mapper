import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpHeaders;
import org.jboss.logging.Logger;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.utils.KeycloakModelUtils;
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

    public static final String PROVIDER_ID = "oidc-waldur-offering-access-mapper";

    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();

    private static final Logger LOGGER = Logger.getLogger(WaldurOIDCOfferingAccessMapper.class.getName());

    private static final String API_URL_KEY = "url.waldur.api.value";
    private static final String API_TOKEN_KEY = "token.waldur.value";
    private static final String OFFERING_UUID_KEY = "uuid.waldur.offering.value";
    private static final String USERNAME_SOURCE_KEY = "keycloak.username.source.value";
    private static final String GROUP_NAME_KEY = "name.keycloak.group.value";
    private static final String GROUP_ADD_KEY = "keycloak.group.add";
    private static final String ROLE_NAME_KEY = "name.keycloak.role.value";
    private static final String ROLE_ADD_KEY = "keycloak.role.add";

    static {
        ProviderConfigProperty property;

        property = new ProviderConfigProperty(
                API_URL_KEY,
                "Waldur API URL",
                "URL to the Waldur API including trailing backslash, e.g. https://waldur.example.com/api/",
                ProviderConfigProperty.STRING_TYPE,
                "");
        configProperties.add(property);

        property = new ProviderConfigProperty(
                OFFERING_UUID_KEY,
                "Waldur offering UUID",
                "UUID of the offering in Waldur",
                ProviderConfigProperty.STRING_TYPE,
                "");
        configProperties.add(property);

        property = new ProviderConfigProperty(
                API_TOKEN_KEY,
                "Waldur API token",
                "Token for Waldur API",
                ProviderConfigProperty.STRING_TYPE,
                "");
        configProperties.add(property);

        List<String> usernameSources = List.of("id", "username");
        property = new ProviderConfigProperty(
                USERNAME_SOURCE_KEY,
                "Username source",
                "Source of the keycloak username",
                ProviderConfigProperty.LIST_TYPE,
                "id");
        property.setOptions(usernameSources);
        configProperties.add(property);

        property = new ProviderConfigProperty(
                GROUP_NAME_KEY,
                "Keycloak group name.",
                "Name of the precreated group in Keycloak.",
                ProviderConfigProperty.STRING_TYPE,
                "");
        configProperties.add(property);

        property = new ProviderConfigProperty(
                GROUP_ADD_KEY,
                "Add a user to the group.",
                "Whether to add a user to the specified group.",
                ProviderConfigProperty.BOOLEAN_TYPE,
                false);
        configProperties.add(property);

        property = new ProviderConfigProperty(
                ROLE_NAME_KEY,
                "Keycloak role name.",
                "Name of the precreated role in Keycloak.",
                ProviderConfigProperty.STRING_TYPE,
                "");
        configProperties.add(property);

        property = new ProviderConfigProperty(
                ROLE_ADD_KEY,
                "Grant a role to a user.",
                "Whether to grant the specified role to a user.",
                ProviderConfigProperty.BOOLEAN_TYPE,
                false);
        configProperties.add(property);

        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, WaldurOIDCOfferingAccessMapper.class);
    }

    private boolean hasAccessToResource(String waldurUrl, String offeringUuid, String waldurToken, String username) {
        if (offeringUuid.equals("")) {
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

    private void transformToken(IDToken token, Map<String, String> config, KeycloakSession keycloakSession,
            UserSessionModel userSession) {
        final String waldurUrl = config.get(API_URL_KEY);
        final String offeringUuid = config.get(OFFERING_UUID_KEY);
        final String usernameSource = config.get(USERNAME_SOURCE_KEY);
        final String groupName = config.get(GROUP_NAME_KEY);
        final boolean addGroup = Boolean.parseBoolean(config.get(GROUP_ADD_KEY));
        final String waldurToken = config.get(API_TOKEN_KEY);
        final String roleName = config.get(ROLE_NAME_KEY);
        final boolean grantRole = Boolean.parseBoolean(config.get(ROLE_ADD_KEY));
        final String claimName = config.get(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME);

        UserModel user = userSession.getUser();
        String username = "";

        if (usernameSource.equals("id"))
            username = user.getId();
        if (usernameSource.equals("username"))
            username = user.getUsername();

        RealmModel realm = keycloakSession.getContext().getRealm();
        String groupPath = String.format("/%s", groupName);
        GroupModel group = KeycloakModelUtils.findGroupByPath(realm, groupName);
        RoleModel role = realm.getRole(roleName);

        boolean hasAccessToResource = this.hasAccessToResource(waldurUrl, offeringUuid, waldurToken, username);

        if (addGroup) {
            if (group == null) {
                LOGGER.error(String.format("The group %s (path %s) does not exist, skipping user processing.",
                        groupName,
                        groupPath));
            } else {
                if (hasAccessToResource) {
                    if (user.isMemberOf(group)) {
                        LOGGER.info(
                                String.format("The user %s is already in the group %s", user.getUsername(),
                                        group.getName()));
                    } else {
                        LOGGER.info(String.format("Adding user %s to group %s", user.getUsername(), group.getName()));
                        user.joinGroup(group);
                        token.getOtherClaims().put(claimName, group.getName());
                    }
                } else if (user.isMemberOf(group)) {
                    LOGGER.info(String.format("Removing user %s from group %s", user.getUsername(), group.getName()));
                    user.leaveGroup(group);
                }
            }
        }

        if (grantRole) {
            if (role == null) {
                LOGGER.error(
                        String.format("The role %s does not exist in the realm, skipping user processing", roleName));
            } else {
                if (hasAccessToResource) {
                    if (user.hasRole(role)) {
                        LOGGER.info(
                                String.format("The user %s already has the role %s", user.getUsername(),
                                        role.getName()));
                    } else {
                        LOGGER.info(
                                String.format("Granting a role %s to a user %s", role.getName(), user.getUsername()));
                        user.grantRole(role);
                    }
                } else if (user.hasRole(role)) {
                    LOGGER.info(String.format("Revoking role %s for user %s", role.getName(), user.getUsername()));
                    user.deleteRoleMapping(role);
                }
            }
        }

    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
            KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {
        Map<String, String> config = mappingModel.getConfig();

        this.transformToken(token, config, keycloakSession, userSession);
    }

    public static ProtocolMapperModel create(
            String name,
            String url,
            String offeringUuid,
            String apiToken,
            String usernameSource,
            String groupName,
            boolean groupAdd,
            String roleName,
            boolean roleAdd,
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
        config.put(API_TOKEN_KEY, apiToken);
        config.put(USERNAME_SOURCE_KEY, usernameSource);
        config.put(GROUP_NAME_KEY, groupName);
        config.put(GROUP_ADD_KEY, Boolean.toString(groupAdd));
        config.put(ROLE_NAME_KEY, roleName);
        config.put(ROLE_ADD_KEY, Boolean.toString(roleAdd));
        config.put(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, claimName);

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
        return "Waldur offering access mapper";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Mapper for offering access from Waldur";
    }
}
