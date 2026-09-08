package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.exception.FlowStorageException;
import uz.xtreme.flowdesigner.service.flow.dto.FlowLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FlowLayoutServiceImpl Tests")
class FlowLayoutServiceImplTest {

    @TempDir
    Path repoPath;

    private FlowLayoutService layoutService;

    @BeforeEach
    void setUp() {
        layoutService = new FlowLayoutServiceImpl(new ObjectMapper());
    }

    @Test
    @DisplayName("Should round-trip positions and the flow's node list")
    void roundTrip() {
        FlowLayout layout = new FlowLayout(List.of(
                new FlowLayout.NodePosition("ACCEPTED", 10.5, -20),
                new FlowLayout.NodePosition("ORPHAN", 0, 0)));

        layoutService.write(repoPath, "payment-flow", layout);
        FlowLayout read = layoutService.read(repoPath, "payment-flow");

        assertEquals(layout, read);
        // The node list is what keeps a status with no action and no transition
        assertEquals(List.of("ACCEPTED", "ORPHAN"), read.statusIds());
    }

    @Test
    @DisplayName("Should keep layouts out of the THUB tree the deployer reads")
    void storedUnderFlowDesignerDir() {
        layoutService.write(repoPath, "payment-flow",
                new FlowLayout(List.of(new FlowLayout.NodePosition("ACCEPTED", 1, 2))));

        assertTrue(Files.exists(repoPath.resolve(".flowdesigner/flows/payment-flow.json")));
        assertFalse(Files.exists(repoPath.resolve("THUB")));
    }

    @Test
    @DisplayName("Should report no layout for a flow that has none")
    void missingLayoutIsEmpty() {
        assertTrue(layoutService.read(repoPath, "never-saved").isEmpty());
    }

    @Test
    @DisplayName("Should ignore a damaged layout instead of failing the flow open")
    void damagedLayoutIsIgnored() throws IOException {
        Path file = repoPath.resolve(".flowdesigner/flows/payment-flow.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");

        assertTrue(layoutService.read(repoPath, "payment-flow").isEmpty());
    }

    @Test
    @DisplayName("Should answer 'no layout' for names it would never write")
    void unsafeNamesReadAsEmpty() {
        // Flows THUB had before Flow Designer existed can carry dots and slashes;
        // they must still open, they just have no stored canvas
        assertTrue(layoutService.read(repoPath, "legacy.flow").isEmpty());
        assertTrue(layoutService.read(repoPath, "../escape").isEmpty());
        assertTrue(layoutService.read(repoPath, null).isEmpty());
    }

    @Test
    @DisplayName("Should refuse to write a layout for an unsafe name")
    void unsafeNameIsNotWritten() {
        assertThrows(FlowStorageException.class, () ->
                layoutService.write(repoPath, "../escape",
                        new FlowLayout(List.of(new FlowLayout.NodePosition("ACCEPTED", 1, 2)))));
    }

    @Test
    @DisplayName("Should remove a layout on delete, and stay silent when there is none")
    void deleteRemovesTheLayout() {
        layoutService.write(repoPath, "payment-flow",
                new FlowLayout(List.of(new FlowLayout.NodePosition("ACCEPTED", 1, 2))));

        layoutService.delete(repoPath, "payment-flow");

        assertTrue(layoutService.read(repoPath, "payment-flow").isEmpty());
        assertFalse(Files.exists(repoPath.resolve(".flowdesigner/flows/payment-flow.json")));
        // Deleting again is silent — the flow may never have had a layout
        assertDoesNotThrow(() -> layoutService.delete(repoPath, "payment-flow"));
    }

    @Test
    @DisplayName("Should not leave a temp file behind after a write")
    void writeLeavesNoTempFile() throws IOException {
        layoutService.write(repoPath, "payment-flow", FlowLayout.empty());

        try (var entries = Files.list(repoPath.resolve(".flowdesigner/flows"))) {
            assertEquals(List.of("payment-flow.json"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }
}
