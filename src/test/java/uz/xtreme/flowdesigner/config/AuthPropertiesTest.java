package uz.xtreme.flowdesigner.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Login allowlist")
class AuthPropertiesTest {

    @Nested
    @DisplayName("Unrestricted")
    class Unrestricted {

        @Test
        @DisplayName("Empty configuration allows anyone")
        void emptyAllowsAnyone() {
            AuthProperties props = new AuthProperties(List.of(), List.of());

            assertTrue(props.isUnrestricted());
            assertTrue(props.isAllowed("anyone", "anyone@example.com"));
        }

        @Test
        @DisplayName("Null and blank entries are ignored")
        void nullAndBlankIgnored() {
            AuthProperties props = new AuthProperties(Arrays.asList(null, "  ", ""), null);

            assertTrue(props.isUnrestricted());
        }
    }

    @Nested
    @DisplayName("Restricted")
    class Restricted {

        @Test
        @DisplayName("Allows a listed username regardless of case")
        void allowsListedUsername() {
            AuthProperties props = new AuthProperties(List.of("Alisher", "dilnoza"), List.of());

            assertTrue(props.isAllowed("alisher", null));
            assertTrue(props.isAllowed("DILNOZA", null));
        }

        @Test
        @DisplayName("Rejects an unlisted username")
        void rejectsUnlistedUsername() {
            AuthProperties props = new AuthProperties(List.of("alisher"), List.of());

            assertFalse(props.isAllowed("someone-else", "someone@example.com"));
        }

        @Test
        @DisplayName("Allows a listed email domain")
        void allowsListedDomain() {
            AuthProperties props = new AuthProperties(List.of(), List.of("xtreme.uz"));

            assertTrue(props.isAllowed("anyone", "anyone@XTREME.UZ"));
            assertFalse(props.isAllowed("anyone", "anyone@gmail.com"));
        }

        @Test
        @DisplayName("Rejects when the provider gave no email and the username is unlisted")
        void rejectsMissingEmail() {
            AuthProperties props = new AuthProperties(List.of("alisher"), List.of("xtreme.uz"));

            assertFalse(props.isAllowed("someone-else", null));
        }

        @Test
        @DisplayName("Rejects a malformed email")
        void rejectsMalformedEmail() {
            AuthProperties props = new AuthProperties(List.of(), List.of("xtreme.uz"));

            assertFalse(props.isAllowed("anyone", "not-an-email"));
        }
    }
}
