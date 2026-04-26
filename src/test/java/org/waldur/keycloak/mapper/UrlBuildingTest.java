package org.waldur.keycloak.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlBuildingTest {

    private static final String BASE_URL = "https://waldur.example.com/api/";

    @Test
    void minioMapper_buildsPermissionsUrl_withScopeAndUsername() {
        String url = WaldurOIDCMinIOMapper.buildPermissionsUrl(BASE_URL, "alice", "project");

        assertEquals(
                "https://waldur.example.com/api/user-permissions/?field=scope_uuid&username=alice&scope_type=project",
                url);
    }

    @Test
    void minioMapper_encodesUsernameWithSpecialChars() {
        String url = WaldurOIDCMinIOMapper.buildPermissionsUrl(BASE_URL, "alice+bob@example.com", "project");

        assertTrue(url.contains("username=alice%2Bbob%40example.com"),
                "username with + and @ must be percent-encoded, got: " + url);
    }

    @Test
    void offeringAccessMapper_buildsResourceAccessUrl() {
        String url = WaldurOIDCOfferingAccessMapper.buildHasResourceAccessUrl(
                BASE_URL, "abc-123-uuid", "alice");

        assertEquals(
                "https://waldur.example.com/api/marketplace-provider-offerings/abc-123-uuid/user_has_resource_access/?username=alice",
                url);
    }

    @Test
    void offeringAccessMapper_encodesUsernameWithSpace() {
        String url = WaldurOIDCOfferingAccessMapper.buildHasResourceAccessUrl(
                BASE_URL, "uuid", "alice smith");

        assertTrue(url.contains("username=alice+smith") || url.contains("username=alice%20smith"),
                "username with space must be percent-encoded, got: " + url);
    }

    @Test
    void offeringUserUsernameMapper_buildsOfferingUserUrl() {
        String url = WaldurOIDCOfferingUserUsernameMapper.buildOfferingUserUrl(
                BASE_URL, "off-uuid", "alice");

        assertEquals(
                "https://waldur.example.com/api/marketplace-offering-users/?offering_uuid=off-uuid&user_username=alice&field=username",
                url);
    }

    @Test
    void offeringUserUsernameMapper_encodesAmpersandInUsername() {
        String url = WaldurOIDCOfferingUserUsernameMapper.buildOfferingUserUrl(
                BASE_URL, "uuid", "a&b");

        assertTrue(url.contains("user_username=a%26b"),
                "ampersand in username must be percent-encoded so it doesn't end the query param, got: " + url);
    }
}
