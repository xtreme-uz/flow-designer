package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.exception.FlowStorageException;
import uz.xtreme.flowdesigner.service.flow.dto.FlowLayout;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Stores canvas layouts as one JSON file per flow under {@code .flowdesigner/flows/}.
 */
@Service
public class FlowLayoutServiceImpl implements FlowLayoutService {

    private static final Logger log = LoggerFactory.getLogger(FlowLayoutServiceImpl.class);

    private static final String FLOWS_DIR = "flows";
    private static final String TEMP_SUFFIX = ".json.tmp";
    /** Flow names are validated before they get here; this is the last line of defence. */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]*$");

    private final ObjectMapper objectMapper;

    public FlowLayoutServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public FlowLayout read(Path basePath, String flowTypeId) {
        // A name this service would never write cannot have a layout. Reading is
        // on the path of every flow open, including flows THUB had before Flow
        // Designer existed, so it answers "none" rather than refusing to serve them.
        if (!isWritableName(flowTypeId)) {
            return FlowLayout.empty();
        }

        Path file = layoutFile(basePath, flowTypeId);
        if (!Files.exists(file)) {
            return FlowLayout.empty();
        }
        try {
            return objectMapper.readValue(file.toFile(), FlowLayout.class);
        } catch (Exception e) {
            // A layout is a convenience: a damaged one must not block opening the flow
            log.warn("Ignoring unreadable layout for flow '{}'", flowTypeId, e);
            return FlowLayout.empty();
        }
    }

    @Override
    public void write(Path basePath, String flowTypeId, FlowLayout layout) {
        Path file = layoutFile(basePath, flowTypeId);
        try {
            Files.createDirectories(file.getParent());
            // Same temp-then-move as the THUB writer: a failed write must not
            // truncate the file that was there
            Path tempFile = file.resolveSibling("." + file.getFileName() + TEMP_SUFFIX);
            try {
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(tempFile.toFile(), layout == null ? FlowLayout.empty() : layout);
                try {
                    Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw new FlowStorageException("Failed to write layout for flow: " + flowTypeId, e);
        }
    }

    @Override
    public void delete(Path basePath, String flowTypeId) {
        if (!isWritableName(flowTypeId)) {
            return;
        }
        try {
            Files.deleteIfExists(layoutFile(basePath, flowTypeId));
        } catch (IOException e) {
            log.warn("Failed to delete layout for flow '{}'", flowTypeId, e);
        }
    }

    private static boolean isWritableName(String flowTypeId) {
        return flowTypeId != null && SAFE_FILE_NAME.matcher(flowTypeId).matches();
    }

    private Path layoutFile(Path basePath, String flowTypeId) {
        if (!isWritableName(flowTypeId)) {
            throw new FlowStorageException("Unsafe flow name for a layout file: " + flowTypeId, null);
        }
        return basePath.resolve(LAYOUT_DIR).resolve(FLOWS_DIR).resolve(flowTypeId + ".json");
    }
}
