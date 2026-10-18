package dev.fopwoc.julia.transform;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.fopwoc.julia.transform.load.LegacyNamespacePass;
import dev.fopwoc.julia.transform.load.WindowQueryPass;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Rewrites name their targets as strings; a renamed or moved class must fail here, not inside the game. */
class EndpointTest {
    static Stream<String> targets() {
        return Stream.concat(
                Stream.of(Endpoint.values()).map(Endpoint::owner),
                Stream.of(LegacyNamespacePass.TESSELLATOR_STATE, WindowQueryPass.HOST_WINDOW));
    }

    @ParameterizedTest
    @MethodSource("targets")
    void everyRewriteTargetExistsOnTheRuntimeClassPath(String owner) throws IOException {
        try (var resource = EndpointTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(resource, owner);
        }
    }
}
